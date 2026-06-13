package store.anybodycan.meshtalk;

import android.content.Context;
import android.util.Log;

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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps Google Nearby Connections to form an offline mesh. Uses P2P_CLUSTER so each
 * device can hold many simultaneous links (many-to-many), which is what lets messages
 * flood-relay hop by hop. Nearby itself transparently upgrades a link from Bluetooth/BLE
 * to a WiFi hotspot when it needs more bandwidth (e.g. for media) — same idea as Quick Share.
 */
public class MeshManager {
    private static final String TAG = "MeshTalk";
    private static final String SERVICE_ID = "store.anybodycan.meshtalk.SERVICE";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int DEFAULT_TTL = 8;
    private static final int SEEN_CACHE_LIMIT = 500;

    public interface MeshListener {
        void onMessage(Message msg);
        void onStatus(String status);
        void onPeerCountChanged(int count);
    }

    private final ConnectionsClient client;
    private final Identity identity;
    private final MeshListener listener;

    private final Set<String> connectedEndpoints = ConcurrentHashMap.newKeySet();
    // Bounded LRU of message ids we've already handled, for de-duplication.
    private final Map<String, Boolean> seen =
            Collections.synchronizedMap(new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SEEN_CACHE_LIMIT;
                }
            });

    public MeshManager(Context ctx, Identity identity, MeshListener listener) {
        this.client = Nearby.getConnectionsClient(ctx.getApplicationContext());
        this.identity = identity;
        this.listener = listener;
    }

    public void start() {
        startAdvertising();
        startDiscovery();
    }

    public void stop() {
        client.stopAllEndpoints();
        client.stopAdvertising();
        client.stopDiscovery();
        connectedEndpoints.clear();
        listener.onPeerCountChanged(0);
    }

    private void startAdvertising() {
        AdvertisingOptions opts = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        client.startAdvertising(identity.getName(), SERVICE_ID, connectionLifecycle, opts)
                .addOnSuccessListener(u -> listener.onStatus("Advertising as " + identity.getName()))
                .addOnFailureListener(e -> listener.onStatus("Advertise failed: " + e.getMessage()));
    }

    private void startDiscovery() {
        DiscoveryOptions opts = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        client.startDiscovery(SERVICE_ID, endpointDiscovery, opts)
                .addOnSuccessListener(u -> listener.onStatus("Looking for people nearby..."))
                .addOnFailureListener(e -> listener.onStatus("Discovery failed: " + e.getMessage()));
    }

    /** Send a brand-new chat message from this device into the mesh. */
    public void sendChat(String text) {
        Message msg = Message.create(identity.getId(), identity.getName(), text, DEFAULT_TTL);
        seen.put(msg.id, true);
        broadcast(msg, null);
    }

    /** Send a message to all connected peers, optionally skipping the one we got it from. */
    private void broadcast(Message msg, String exceptEndpoint) {
        byte[] data = msg.toBytes();
        for (String endpoint : connectedEndpoints) {
            if (endpoint.equals(exceptEndpoint)) continue;
            client.sendPayload(endpoint, Payload.fromBytes(data));
        }
    }

    private final EndpointDiscoveryCallback endpointDiscovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            // Request a connection to every peer we see.
            client.requestConnection(identity.getName(), endpointId, connectionLifecycle);
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
        }
    };

    private final ConnectionLifecycleCallback connectionLifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            // Auto-accept. (A production app would show/verify info.getAuthenticationDigits().)
            client.acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connectedEndpoints.add(endpointId);
                listener.onPeerCountChanged(connectedEndpoints.size());
                listener.onStatus("Connected to a nearby device");
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            connectedEndpoints.remove(endpointId);
            listener.onPeerCountChanged(connectedEndpoints.size());
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() != Payload.Type.BYTES || payload.asBytes() == null) return;
            Message msg = Message.fromBytes(payload.asBytes());
            if (msg == null) return;

            // De-duplicate: if we've already seen this message id, drop it.
            if (seen.containsKey(msg.id)) return;
            seen.put(msg.id, true);

            // Don't echo our own messages back to the UI.
            if (!msg.senderId.equals(identity.getId())) {
                listener.onMessage(msg);
            }

            // Relay onward (flood) if it still has hops left, skipping the sender link.
            if (msg.ttl > 1) {
                msg.ttl -= 1;
                broadcast(msg, endpointId);
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {
        }
    };
}
