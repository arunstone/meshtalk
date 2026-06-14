package store.anybodycan.meshtalk;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-device SQLite storage so contacts and chat history survive an app restart
 * ("use phone memory" — user request 2026-06-14). Plain {@link SQLiteOpenHelper} on purpose:
 * no Room annotation processor to wire into this Gradle build, and the schema is tiny.
 *
 * Message ids are the primary key, so re-hearing a flooded packet after a restart is a no-op
 * (INSERT OR IGNORE) — the same de-dup guarantee the in-memory {@code seen} cache gives at runtime.
 * Nothing here touches the network; {@link PeerStore} is the only caller.
 */
public class MeshDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "meshtalk.db";
    private static final int DB_VERSION = 1;

    private static final String T_PEERS = "peers";
    private static final String T_MSGS = "messages";

    MeshDb(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_PEERS + " ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT,"
                + "last_seen INTEGER,"
                + "hops INTEGER)");
        db.execSQL("CREATE TABLE " + T_MSGS + " ("
                + "id TEXT PRIMARY KEY,"
                + "convo TEXT,"
                + "type TEXT,"
                + "sid TEXT,"
                + "sname TEXT,"
                + "toid TEXT,"
                + "text TEXT,"
                + "ts INTEGER,"
                + "pid INTEGER,"
                + "fsize INTEGER)");
        db.execSQL("CREATE INDEX idx_msgs_convo ON " + T_MSGS + " (convo, ts)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v0.3 ships schema v1; future migrations go here. For now, rebuild on any bump.
        db.execSQL("DROP TABLE IF EXISTS " + T_PEERS);
        db.execSQL("DROP TABLE IF EXISTS " + T_MSGS);
        onCreate(db);
    }

    // ---- peers ----

    void upsertPeer(@NonNull Peer p) {
        ContentValues v = new ContentValues();
        v.put("id", p.id);
        v.put("name", p.name);
        v.put("last_seen", p.lastSeen);
        v.put("hops", p.hops);
        getWritableDatabase().insertWithOnConflict(T_PEERS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @NonNull
    List<Peer> loadPeers() {
        List<Peer> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(T_PEERS,
                new String[]{"id", "name", "last_seen", "hops"},
                null, null, null, null, "last_seen DESC")) {
            while (c.moveToNext()) {
                out.add(new Peer(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3)));
            }
        }
        return out;
    }

    // ---- messages ----

    /** @return true if the row was newly inserted (false if this id was already stored). */
    boolean insertMessage(@NonNull String convoKey, @NonNull Message m) {
        ContentValues v = new ContentValues();
        v.put("id", m.id);
        v.put("convo", convoKey);
        v.put("type", m.type);
        v.put("sid", m.senderId);
        v.put("sname", m.senderName);
        v.put("toid", m.toId);
        v.put("text", m.text);
        v.put("ts", m.timestamp);
        v.put("pid", m.payloadId);
        v.put("fsize", m.fileSize);
        long rowId = getWritableDatabase()
                .insertWithOnConflict(T_MSGS, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        return rowId != -1;
    }

    /** The most recent {@code limit} messages of one conversation, oldest-first for display. */
    @NonNull
    List<Message> loadConversation(@NonNull String convoKey, int limit) {
        List<Message> out = new ArrayList<>();
        // Grab the newest `limit` rows, then reverse to chronological order for the chat list.
        try (Cursor c = getReadableDatabase().query(T_MSGS, null,
                "convo = ?", new String[]{convoKey}, null, null, "ts DESC", String.valueOf(limit))) {
            while (c.moveToNext()) {
                out.add(0, readMessage(c));
            }
        }
        return out;
    }

    /** The single most recent message per conversation, for home-screen previews. */
    @NonNull
    Map<String, Message> loadLastByConvo() {
        Map<String, Message> last = new LinkedHashMap<>();
        // Oldest-first so the last write per convo wins.
        try (Cursor c = getReadableDatabase().query(T_MSGS, null,
                null, null, null, null, "ts ASC")) {
            int idxConvo = c.getColumnIndexOrThrow("convo");
            while (c.moveToNext()) {
                last.put(c.getString(idxConvo), readMessage(c));
            }
        }
        return last;
    }

    private static Message readMessage(Cursor c) {
        return new Message(
                c.getString(c.getColumnIndexOrThrow("id")),
                c.getString(c.getColumnIndexOrThrow("type")),
                c.getString(c.getColumnIndexOrThrow("sid")),
                c.getString(c.getColumnIndexOrThrow("sname")),
                c.getString(c.getColumnIndexOrThrow("toid")),
                c.getString(c.getColumnIndexOrThrow("text")),
                0, // ttl is a transient network field, not persisted
                c.getLong(c.getColumnIndexOrThrow("ts")),
                0, // hopCount is transient too
                c.getLong(c.getColumnIndexOrThrow("pid")),
                c.getLong(c.getColumnIndexOrThrow("fsize")));
    }
}
