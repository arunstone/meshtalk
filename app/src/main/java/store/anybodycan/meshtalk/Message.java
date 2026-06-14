package store.anybodycan.meshtalk;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A mesh packet. Carries its own unique id so every hop can de-duplicate, a ttl (hop budget)
 * that is decremented on each relay so packets don't loop forever, and a hopCount that is
 * incremented on each relay so the receiver can tell how many hops away the sender is.
 *
 * Kinds, distinguished by {@link #type}:
 *  - TYPE_CHAT  : a chat message. If {@link #toId} is empty it is a group/broadcast message
 *                 everyone shows; if set, it is a 1:1 message only that peer shows.
 *  - TYPE_HELLO : a presence announcement so peers appear in each other's contact list before
 *                 any chatting happens. Never shown as a chat bubble.
 *  - TYPE_FILE  : metadata for a file attachment. The bytes themselves travel as a separate
 *                 Nearby FILE payload (referenced by {@link #payloadId}); this packet just
 *                 describes it (name + size). Files are DIRECT-ONLY and never relayed.
 *
 * Hop policy (user decision 2026-06-14): messages + presence flood the whole mesh (high ttl);
 * calls relay only a short distance (ttl ~3); files do not relay at all (ttl 1, direct link).
 */
public class Message {
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_HELLO = "hello";
    public static final String TYPE_FILE = "file";

    /** Conversation key used for the shared group/broadcast thread. */
    public static final String GROUP = "";

    public final String id;
    public final String type;
    public final String senderId;
    public final String senderName;
    /** Empty string = group/broadcast; otherwise the recipient peer's id. */
    public final String toId;
    public final String text;
    public int ttl;
    public final long timestamp;

    /** Relay distance: 0 at the sender, +1 on every forward. distance-in-hops = hopCount + 1. */
    public int hopCount;

    // ---- file attachments only (0 / empty otherwise) ----
    /** Id of the companion Nearby FILE payload carrying the bytes. */
    public final long payloadId;
    /** Size of the attachment in bytes (for the progress UI). */
    public final long fileSize;

    public Message(String id, String type, String senderId, String senderName,
                   String toId, String text, int ttl, long timestamp,
                   int hopCount, long payloadId, long fileSize) {
        this.id = id;
        this.type = type;
        this.senderId = senderId;
        this.senderName = senderName;
        this.toId = toId == null ? GROUP : toId;
        this.text = text;
        this.ttl = ttl;
        this.timestamp = timestamp;
        this.hopCount = hopCount;
        this.payloadId = payloadId;
        this.fileSize = fileSize;
    }

    /** A broadcast chat message shown in everyone's Group thread. */
    public static Message createGroup(String senderId, String senderName, String text, int ttl) {
        return new Message(UUID.randomUUID().toString(), TYPE_CHAT, senderId, senderName,
                GROUP, text, ttl, System.currentTimeMillis(), 0, 0, 0);
    }

    /** A 1:1 chat message only {@code toId} displays. */
    public static Message createDirect(String senderId, String senderName, String toId,
                                       String text, int ttl) {
        return new Message(UUID.randomUUID().toString(), TYPE_CHAT, senderId, senderName,
                toId, text, ttl, System.currentTimeMillis(), 0, 0, 0);
    }

    /** A presence announcement so this device shows up in nearby contact lists. */
    public static Message createHello(String senderId, String senderName, int ttl) {
        return new Message(UUID.randomUUID().toString(), TYPE_HELLO, senderId, senderName,
                GROUP, "", ttl, System.currentTimeMillis(), 0, 0, 0);
    }

    /**
     * Metadata for a file attachment. {@code toId} = GROUP for a broadcast attachment to direct
     * neighbours, or a peer id for a 1:1 send. {@code fileName} is shown in the bubble; the bytes
     * arrive separately as the FILE payload identified by {@code payloadId}. ttl is forced to 1 by
     * the caller — files never relay across the mesh.
     */
    public static Message createFile(String senderId, String senderName, String toId,
                                     String fileName, long payloadId, long fileSize, int ttl) {
        return new Message(UUID.randomUUID().toString(), TYPE_FILE, senderId, senderName,
                toId, fileName, ttl, System.currentTimeMillis(), 0, payloadId, fileSize);
    }

    public boolean isHello() {
        return TYPE_HELLO.equals(type);
    }

    public boolean isFile() {
        return TYPE_FILE.equals(type);
    }

    public boolean isGroup() {
        return TextUtils.isEmpty(toId);
    }

    public byte[] toBytes() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("type", type);
            o.put("sid", senderId);
            o.put("sname", senderName);
            o.put("to", toId);
            o.put("text", text);
            o.put("ttl", ttl);
            o.put("ts", timestamp);
            o.put("hops", hopCount);
            o.put("pid", payloadId);
            o.put("fsize", fileSize);
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    public static Message fromBytes(byte[] bytes) {
        try {
            JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return new Message(
                    o.getString("id"),
                    o.optString("type", TYPE_CHAT),
                    o.getString("sid"),
                    o.getString("sname"),
                    o.optString("to", GROUP),
                    o.optString("text", ""),
                    o.getInt("ttl"),
                    o.getLong("ts"),
                    o.optInt("hops", 0),
                    o.optLong("pid", 0),
                    o.optLong("fsize", 0));
        } catch (JSONException e) {
            return null;
        }
    }
}
