package store.anybodycan.meshtalk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * Stable per-install identity. We deliberately do NOT use the Bluetooth/WiFi MAC
 * address: modern Android hides/randomizes it and returns a fake value to apps.
 * Instead each install generates its own random ID once and reuses it.
 */
public class Identity {
    private static final String PREFS = "meshtalk_identity";
    private static final String KEY_ID = "device_id";
    private static final String KEY_NAME = "display_name";

    private final String id;
    private String name;
    private final SharedPreferences prefs;

    public Identity(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = prefs.getString(KEY_ID, null);
        if (existing == null) {
            existing = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_ID, existing).apply();
        }
        id = existing;
        name = prefs.getString(KEY_NAME, "User-" + id.substring(0, 4));
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        if (newName == null || newName.trim().isEmpty()) return;
        name = newName.trim();
        prefs.edit().putString(KEY_NAME, name).apply();
    }
}
