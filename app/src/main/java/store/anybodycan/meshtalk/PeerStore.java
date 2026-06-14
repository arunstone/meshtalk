package store.anybodycan.meshtalk;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App-scoped store of who we've met and what was said with each of them. A process-wide
 * singleton so the data (and the running mesh) survive moving between the contacts screen and a
 * chat screen — nothing here touches the network.
 *
 * As of v0.3 it is backed by on-device SQLite ({@link MeshDb}, "use phone memory") so contacts
 * and history survive an app restart. An in-memory cache sits in front of the DB for speed: the
 * full peer list and a last-message-per-conversation map are loaded once on {@link #attach};
 * individual conversations are loaded lazily and capped in RAM (older history stays on disk).
 *
 * Conversations are keyed by the OTHER party's id: a 1:1 thread is keyed by that peer's id, and
 * the shared broadcast thread is keyed by {@link Message#GROUP}.
 */
public class PeerStore {

    /** Cap on messages held in RAM per conversation; older ones live only on disk. */
    private static final int MAX_IN_MEMORY = 200;

    private static final PeerStore INSTANCE = new PeerStore();

    public static PeerStore get() {
        return INSTANCE;
    }

    /** Notified on the UI thread by callers (Mesh) when peers or a conversation change. */
    public interface Observer {
        void onPeersChanged();
        /** A new message landed in the conversation keyed by {@code convoKey}. */
        void onConversation(String convoKey, Message msg);
    }

    private MeshDb db;
    private final Map<String, Peer> peers = new LinkedHashMap<>();
    private final Map<String, List<Message>> conversations = new LinkedHashMap<>();
    private final Map<String, Message> lastByConvo = new LinkedHashMap<>();
    private final List<Observer> observers = new ArrayList<>();

    private PeerStore() {
    }

    /** Open (once) the on-device database and warm the caches. Safe to call repeatedly. */
    public synchronized void attach(Context ctx) {
        if (db != null) return;
        db = new MeshDb(ctx.getApplicationContext());
        for (Peer p : db.loadPeers()) peers.put(p.id, p);
        lastByConvo.putAll(db.loadLastByConvo());
    }

    // ---- observers ----

    public synchronized void addObserver(Observer o) {
        if (!observers.contains(o)) observers.add(o);
    }

    public synchronized void removeObserver(Observer o) {
        observers.remove(o);
    }

    // ---- peers ----

    /** Record that we heard from a peer (any chat or presence message), with their hop distance. */
    public void touchPeer(String id, String name, long when, int hops) {
        boolean changed;
        Peer snapshot;
        synchronized (this) {
            Peer p = peers.get(id);
            if (p == null) {
                p = new Peer(id, name, when, hops);
                peers.put(id, p);
                changed = true;
            } else {
                changed = !p.name.equals(name) || when > p.lastSeen || p.hops != hops;
                if (name != null && !name.isEmpty()) p.name = name;
                if (when > p.lastSeen) p.lastSeen = when;
                p.hops = hops; // reflect the current topology (latest observed path wins)
            }
            snapshot = changed ? new Peer(p.id, p.name, p.lastSeen, p.hops) : null;
        }
        if (changed) {
            if (db != null) db.upsertPeer(snapshot);
            notifyPeers();
        }
    }

    /** Snapshot of known peers, most-recently-seen first. */
    public synchronized List<Peer> peersByRecency() {
        List<Peer> out = new ArrayList<>(peers.values());
        Collections.sort(out, new Comparator<Peer>() {
            @Override
            public int compare(Peer a, Peer b) {
                return Long.compare(b.lastSeen, a.lastSeen);
            }
        });
        return out;
    }

    public synchronized String nameOf(String id) {
        Peer p = peers.get(id);
        return p != null ? p.name : id.substring(0, Math.min(6, id.length()));
    }

    // ---- conversations ----

    /** Append a message to the conversation keyed by {@code convoKey} and notify observers. */
    public void addToConversation(@NonNull String convoKey, @NonNull Message msg) {
        boolean isNew = true;
        synchronized (this) {
            if (db != null) {
                isNew = db.insertMessage(convoKey, msg);
            }
            if (!isNew) return; // already stored (e.g. a flooded duplicate) — don't double-show
            lastByConvo.put(convoKey, msg);
            List<Message> list = conversations.get(convoKey);
            if (list != null) {
                list.add(msg);
                while (list.size() > MAX_IN_MEMORY) list.remove(0);
            }
        }
        notifyConversation(convoKey, msg);
    }

    public synchronized List<Message> conversation(String convoKey) {
        List<Message> list = conversations.get(convoKey);
        if (list == null) {
            list = (db != null) ? db.loadConversation(convoKey, MAX_IN_MEMORY) : new ArrayList<>();
            conversations.put(convoKey, list);
        }
        return new ArrayList<>(list);
    }

    public synchronized Message lastMessage(String convoKey) {
        return lastByConvo.get(convoKey);
    }

    // ---- notify (snapshot observers to avoid holding the lock during callbacks) ----

    private void notifyPeers() {
        List<Observer> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(observers);
        }
        for (Observer o : snapshot) o.onPeersChanged();
    }

    private void notifyConversation(String convoKey, Message msg) {
        List<Observer> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(observers);
        }
        for (Observer o : snapshot) o.onConversation(convoKey, msg);
    }
}
