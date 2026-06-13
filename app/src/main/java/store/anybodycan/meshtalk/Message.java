package store.anybodycan.meshtalk;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A mesh message. Carries its own unique id so every hop can de-duplicate, and a
 * ttl (hop budget) that is decremented on each relay so messages don't loop forever.
 */
public class Message {
    public final String id;
    public final String senderId;
    public final String senderName;
    public final String text;
    public int ttl;
    public final long timestamp;

    public Message(String id, String senderId, String senderName, String text, int ttl, long timestamp) {
        this.id = id;
        this.senderId = senderId;
        this.senderName = senderName;
        this.text = text;
        this.ttl = ttl;
        this.timestamp = timestamp;
    }

    public static Message create(String senderId, String senderName, String text, int ttl) {
        return new Message(UUID.randomUUID().toString(), senderId, senderName, text, ttl,
                System.currentTimeMillis());
    }

    public byte[] toBytes() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("sid", senderId);
            o.put("sname", senderName);
            o.put("text", text);
            o.put("ttl", ttl);
            o.put("ts", timestamp);
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
                    o.getString("sid"),
                    o.getString("sname"),
                    o.getString("text"),
                    o.getInt("ttl"),
                    o.getLong("ts"));
        } catch (JSONException e) {
            return null;
        }
    }
}
