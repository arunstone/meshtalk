package store.anybodycan.meshtalk;

import android.content.Context;

/**
 * Process-wide holder for the one running mesh + our identity, so they survive moving
 * between the contacts screen and a chat screen (creating a new MeshManager per Activity
 * would tear down and re-discover the network on every navigation).
 *
 * Link status is fanned out to whichever Activity is currently on screen via a single
 * swappable {@link MeshManager.MeshListener}; chat/contact data flows through {@link PeerStore}.
 */
public final class Mesh {

    private static Identity identity;
    private static MeshManager manager;
    private static boolean started = false;

    private static final MeshManager.MeshListener relay = new MeshManager.MeshListener() {
        @Override
        public void onStatus(String status) {
            MeshManager.MeshListener l = uiListener;
            if (l != null) l.onStatus(status);
        }

        @Override
        public void onPeerCountChanged(int count) {
            connectedCount = count;
            MeshManager.MeshListener l = uiListener;
            if (l != null) l.onPeerCountChanged(count);
        }
    };

    private static volatile MeshManager.MeshListener uiListener;
    private static volatile int connectedCount = 0;

    private Mesh() {
    }

    public static synchronized Identity identity(Context ctx) {
        if (identity == null) identity = new Identity(ctx.getApplicationContext());
        // Open the on-device store the first time anyone asks for identity (i.e. on first screen),
        // so contacts + history are loaded from "phone memory" before any UI reads them.
        PeerStore.get().attach(ctx);
        return identity;
    }

    /** Start advertising/discovery once for the whole app. Safe to call repeatedly. */
    public static synchronized void start(Context ctx) {
        if (started) return;
        if (manager == null) {
            manager = new MeshManager(ctx.getApplicationContext(), identity(ctx), relay);
        }
        manager.start();
        started = true;
    }

    /** Restart after an identity (display-name) change. */
    public static synchronized void restart(Context ctx) {
        if (manager != null) {
            manager.stop();
            started = false;
        }
        // Rebuild so the new display name is advertised.
        manager = new MeshManager(ctx.getApplicationContext(), identity(ctx), relay);
        manager.start();
        started = true;
    }

    public static MeshManager manager() {
        return manager;
    }

    public static boolean isStarted() {
        return started;
    }

    public static int connectedCount() {
        return connectedCount;
    }

    /** The on-screen Activity registers here to receive link-status updates. */
    public static void setUiListener(MeshManager.MeshListener l) {
        uiListener = l;
    }

    public static void clearUiListener(MeshManager.MeshListener l) {
        if (uiListener == l) uiListener = null;
    }
}
