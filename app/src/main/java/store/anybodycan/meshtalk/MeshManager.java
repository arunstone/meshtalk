package store.anybodycan.meshtalk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps Google Nearby Connections to form an offline mesh. Uses P2P_CLUSTER so each device can
 * hold many simultaneous links (many-to-many), which is what lets traffic flood-relay hop by hop.
 * Nearby transparently upgrades a link from Bluetooth/BLE to a WiFi hotspot when it needs more
 * bandwidth — same idea as Quick Share.
 *
 * <h3>Hop policy (user decision 2026-06-14)</h3>
 * <ul>
 *   <li><b>Messages + presence</b> flood the whole mesh ({@link #CHAT_TTL}).</li>
 *   <li><b>Calls</b> relay only a short distance ({@link #CALL_TTL} = 3 hops) — quality degrades
 *       per hop, so they are deliberately capped rather than flooded. (Real-time call media is the
 *       next milestone; the routing budget is wired in here ready for it.)</li>
 *   <li><b>Files</b> are DIRECT-ONLY ({@link #FILE_TTL} = 1): they go straight to a directly
 *       connected peer and are never relayed across hops (a big file multi-hop is battery/bandwidth
 *       death). Size is unlimited on that direct link.</li>
 * </ul>
 *
 * Received traffic is fed into the on-device-backed {@link PeerStore}: presence "hello" packets
 * populate the contact list (with how many hops away the peer is), and chat/file packets land in
 * the right 1:1 or group conversation. The {@link MeshListener} carries link status + transfer
 * progress for whichever screen is on top.
 */
public class MeshManager {
    private static final String SERVICE_ID = "store.anybodycan.meshtalk.SERVICE";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    /** Hop budgets per traffic class — see class javadoc. */
    private static final int CHAT_TTL = 8;
    static final int CALL_TTL = 3;
    private static final int FILE_TTL = 1;

    private static final int SEEN_CACHE_LIMIT = 500;
    /**
     * Presence "hello" cadence. Battery principle: be chatty only while the network is changing,
     * go quiet when it's stable. We start at MIN and double the gap up to MAX each tick while
     * nothing changes; any peer joining/leaving snaps us back to MIN so a new arrival still shows
     * up in contact lists within a few seconds.
     */
    private static final long PRESENCE_MIN_MS = 5000;
    private static final long PRESENCE_MAX_MS = 60000;

    public interface MeshListener {
        void onStatus(String status);
        void onPeerCountChanged(int count);
    }

    private final Context ctx;
    private final ConnectionsClient client;
    private final Identity identity;
    private final MeshListener listener;
    private final PeerStore store = PeerStore.get();
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean running = false;
    private long presenceInterval = PRESENCE_MIN_MS;

    private final Set<String> connectedEndpoints = ConcurrentHashMap.newKeySet();
    /** Learned from direct (hopCount == 0) packets: which endpoint link is which peer id. */
    private final Map<String, String> endpointToPeer = new ConcurrentHashMap<>();
    /** Incoming file transfers, awaiting both their metadata packet and the byte payload. */
    private final Map<Long, Payload> incomingFilePayloads = new ConcurrentHashMap<>();
    private final Map<Long, Message> incomingFileMeta = new ConcurrentHashMap<>();

    // Bounded LRU of message ids we've already handled, for de-duplication.
    private final Map<String, Boolean> seen =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CACHE_LIMIT;
                }
            });

    public MeshManager(Context ctx, Identity identity, MeshListener listener) {
        this.ctx = ctx.getApplicationContext();
        this.client = Nearby.getConnectionsClient(this.ctx);
        this.identity = identity;
        this.listener = listener;
    }

    public void start() {
        running = true;
        presenceInterval = PRESENCE_MIN_MS;
        startAdvertising();
        startDiscovery();
        main.postDelayed(presenceTick, presenceInterval);
    }

    public void stop() {
        running = false;
        main.removeCallbacks(presenceTick);
        client.stopAllEndpoints();
        client.stopAdvertising();
        client.stopDiscovery();
        connectedEndpoints.clear();
        endpointToPeer.clear();
        notifyCount();
    }

    private void startAdvertising() {
        AdvertisingOptions opts = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client.startAdvertising(identity.getName(), SERVICE_ID, connectionLifecycle, opts)
                .addOnSuccessListener(u -> listener.onStatus("Visible as " + identity.getName()))
                .addOnFailureListener(e -> listener.onStatus("Advertise failed: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions opts = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client.startDiscovery(SERVICE_ID, endpointDiscovery, opts)
                .addOnSuccessListener(u -> listener.onStatus("Looking for people nearby..."))
                .addOnFailureListener(e -> listener.onStatus("Discovery failed: " + e.getMessage()));
    }

    // ---- presence: keep announcing ourselves so we appear in nearby contact lists ----

    private final Runnable presenceTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            announcePresence();
            // Network looks stable this tick — wait longer before the next announce.
            presenceInterval = Math.min(presenceInterval * 2, PRESENCE_MAX_MS);
            main.postDelayed(this, presenceInterval);
        }
    };

    /** A peer joined or left: become chatty again so contact lists update promptly. */
    private void resetPresenceBackoff() {
        if (!running) return;
        presenceInterval = PRESENCE_MIN_MS;
        main.removeCallbacks(presenceTick);
        main.postDelayed(presenceTick, presenceInterval);
    }

    private void announcePresence() {
        if (connectedEndpoints.isEmpty()) return;
        Message hello = Message.createHello(identity.getId(), identity.getName(), CHAT_TTL);
        seen.put(hello.id, true);
        broadcast(hello, null);
    }

    // ---- sending chat ----

    /** Send a broadcast message everyone nearby sees in their Group thread. */
    public void sendGroup(String text) {
        Message msg = Message.createGroup(identity.getId(), identity.getName(), text, CHAT_TTL);
        seen.put(msg.id, true);
        store.addToConversation(Message.GROUP, msg);
        broadcast(msg, null);
    }

    /** Send a 1:1 message only {@code toId} will display. Flooded, but addressed. */
    public void sendDirect(String toId, String text) {
        Message msg = Message.createDirect(identity.getId(), identity.getName(), toId, text, CHAT_TTL);
        seen.put(msg.id, true);
        // For our own outgoing copy, the conversation key is the OTHER party (the recipient).
        store.addToConversation(toId, msg);
        broadcast(msg, null);
    }

    // ---- sending files (DIRECT-ONLY, unlimited size, never relayed) ----

    /**
     * Send a file over a direct link. For a 1:1 chat the recipient MUST be a directly connected
     * neighbour (files don't hop); for the group thread it goes to every directly connected peer.
     * The bytes travel as a Nearby FILE payload alongside a small metadata packet.
     */
    public void sendFile(String convoKey, File file, String displayName, long size) {
        boolean group = TextUtils.isEmpty(convoKey);
        List<String> targets;
        if (group) {
            targets = new ArrayList<>(connectedEndpoints);
        } else {
            String ep = endpointForPeer(convoKey);
            targets = ep != null ? Collections.singletonList(ep) : Collections.emptyList();
        }
        if (targets.isEmpty()) {
            listener.onStatus(group
                    ? "No device in direct range to send the file to"
                    : "“" + displayName + "” needs the recipient in direct range (files don't relay)");
            return;
        }

        Payload payload;
        try {
            payload = Payload.fromFile(file);
        } catch (Exception e) {
            listener.onStatus("Couldn't read file: " + e.getMessage());
            return;
        }

        Message meta = Message.createFile(identity.getId(), identity.getName(),
                group ? Message.GROUP : convoKey, displayName, payload.getId(), size, FILE_TTL);
        seen.put(meta.id, true);
        store.addToConversation(convoKey, meta); // our own copy in the thread

        // Metadata first (so the receiver can name/route the bytes), then the payload itself.
        client.sendPayload(targets, Payload.fromBytes(meta.toBytes()));
        client.sendPayload(targets, payload);
        listener.onStatus("Sending “" + displayName + "”…");
    }

    /** Send a packet to all connected peers, optionally skipping the one we got it from. */
    private void broadcast(Message msg, String exceptEndpoint) {
        byte[] data = msg.toBytes();
        for (String endpoint : connectedEndpoints) {
            if (endpoint.equals(exceptEndpoint)) continue;
            client.sendPayload(endpoint, Payload.fromBytes(data));
        }
    }

    private String endpointForPeer(String peerId) {
        for (Map.Entry<String, String> e : endpointToPeer.entrySet()) {
            if (e.getValue().equals(peerId) && connectedEndpoints.contains(e.getKey())) {
                return e.getKey();
            }
        }
        return null;
    }

    private final EndpointDiscoveryCallback endpointDiscovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            client.requestConnection(identity.getName(), endpointId, connectionLifecycle);
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            // Auto-accept. (A production app would verify info.getAuthenticationDigits().)
            client.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connectedEndpoints.add(endpointId);
                notifyCount();
                listener.onStatus("Connected to a nearby device");
                // Greet the new link immediately so we appear in their contacts fast,
                // and reset the backoff so the next few announces stay frequent.
                announcePresence();
                resetPresenceBackoff();
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedEndpoints.remove(endpointId);
            endpointToPeer.remove(endpointId);
            notifyCount();
            // Topology changed — become chatty again briefly.
            resetPresenceBackoff();
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.FILE) {
                // The bytes of an attachment. Hold it until its metadata packet arrives (or it
                // already has), then file it into the conversation. Files are never relayed.
                incomingFilePayloads.put(payload.getId(), payload);
                maybeCompleteFile(payload.getId());
                return;
            }
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null) return;
            Message msg = Message.fromBytes(payload.asBytes());
            if (msg == null) return;

            // De-duplicate: if we've already seen this packet id, drop it.
            if (seen.containsKey(msg.id)) return;
            seen.put(msg.id, true);

            // A hopCount of 0 means this came straight from its author over this very link —
            // so we now know which endpoint is which peer (needed to send files directly).
            if (msg.hopCount == 0 && !msg.senderId.equals(identity.getId())) {
                endpointToPeer.put(endpointId, msg.senderId);
            }

            // Anything we hear reveals a peer — keep the contact list fresh, recording how many
            // hops away they are (distance = hopCount + 1; a direct neighbour is 1).
            if (!msg.senderId.equals(identity.getId())) {
                store.touchPeer(msg.senderId, msg.senderName, System.currentTimeMillis(),
                        msg.hopCount + 1);
                deliverToUi(msg);
            }

            // Relay onward (flood) if it still has hops left, skipping the sender link. Files carry
            // ttl 1 so this is naturally skipped for them; calls carry a low ttl so they stop early.
            if (msg.ttl > 1) {
                msg.ttl -= 1;
                msg.hopCount += 1;
                broadcast(msg, endpointId);
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
            if (update.getStatus() == PayloadTransferUpdate.Status.IN_PROGRESS
                    && update.getTotalBytes() > 0) {
                int pct = (int) (100 * update.getBytesTransferred() / update.getTotalBytes());
                listener.onStatus("Transferring file… " + pct + "%");
            } else if (update.getStatus() == PayloadTransferUpdate.Status.SUCCESS) {
                maybeCompleteFile(update.getPayloadId());
            }
        }
    };

    /** Decide whether an incoming chat/file packet is for us and, if so, file it in a conversation. */
    private void deliverToUi(Message msg) {
        if (msg.isHello()) return; // presence only updates the contact list
        if (msg.isFile()) {
            // Only the addressee (or anyone, for a group file) keeps it; we never relay files.
            boolean forUs = msg.isGroup() || msg.toId.equals(identity.getId());
            if (!forUs) return;
            incomingFileMeta.put(msg.payloadId, msg);
            maybeCompleteFile(msg.payloadId);
            return;
        }
        if (msg.isGroup()) {
            store.addToConversation(Message.GROUP, msg);
        } else if (msg.toId.equals(identity.getId())) {
            // Addressed to us: the conversation is keyed by the other party (the sender).
            store.addToConversation(msg.senderId, msg);
        }
        // Otherwise it's a 1:1 message for someone else — we already relayed it, nothing to show.
    }

    /** Once BOTH the metadata and the fully-transferred bytes are here, save + show the file. */
    private void maybeCompleteFile(long payloadId) {
        Payload p = incomingFilePayloads.get(payloadId);
        Message meta = incomingFileMeta.get(payloadId);
        if (p == null || meta == null) return;
        if (p.asFile() == null) return; // not materialised on disk yet

        File received = p.asFile().asJavaFile();
        if (received == null) return;

        File dir = new File(ctx.getExternalFilesDir(null), "MeshTalk");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File dest = uniqueDest(dir, meta.text);
        String savedName = received.renameTo(dest) ? dest.getName() : meta.text;

        incomingFilePayloads.remove(payloadId);
        incomingFileMeta.remove(payloadId);

        // Reuse the metadata's id so re-hearing it after a restart de-dups against the DB.
        Message shown = new Message(meta.id, Message.TYPE_FILE, meta.senderId, meta.senderName,
                meta.toId, savedName, 0, meta.timestamp, 0, payloadId, meta.fileSize);
        String convo = meta.isGroup() ? Message.GROUP : meta.senderId;
        store.addToConversation(convo, shown);
        listener.onStatus("Received “" + savedName + "”");
    }

    /** Avoid clobbering an existing download of the same name: foo.jpg → foo (1).jpg, etc. */
    private static File uniqueDest(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return f;
    }

    private void notifyCount() {
        listener.onPeerCountChanged(connectedEndpoints.size());
    }
}
