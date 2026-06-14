package store.anybodycan.meshtalk;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * Home screen: the list of active devices nearby. Tap one to open a 1:1 chat, or tap the
 * pinned "Everyone (Group)" row to broadcast. The mesh runs app-wide (see {@link Mesh}) so
 * it keeps discovering while you're inside a chat. Fully offline — no server anywhere.
 */
public class MainActivity extends AppCompatActivity
        implements MeshManager.MeshListener, PeerStore.Observer {

    private static final int REQ_PERMS = 1001;

    private Identity identity;
    private PeerListAdapter adapter;
    private RecyclerView list;
    private TextView status;
    private TextView empty;
    private TextView me;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        identity = Mesh.identity(this);
        status = findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        me = findViewById(R.id.me);
        list = findViewById(R.id.peers);
        Button name = findViewById(R.id.nameBtn);

        adapter = new PeerListAdapter((convoKey, title) -> {
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra(ChatActivity.EXTRA_CONVO, convoKey);
            i.putExtra(ChatActivity.EXTRA_TITLE, title);
            startActivity(i);
        });
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        name.setOnClickListener(v -> changeName());

        setTitle("MeshTalk");
        me.setText("You: " + identity.getName());
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Mesh.setUiListener(this);
        PeerStore.get().addObserver(this);
        me.setText("You: " + identity.getName());
        refreshPeers();
        onPeerCountChanged(Mesh.connectedCount());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Mesh.clearUiListener(this);
        PeerStore.get().removeObserver(this);
    }

    private void refreshPeers() {
        List<Peer> peers = PeerStore.get().peersByRecency();
        adapter.setPeers(peers);
        empty.setVisibility(peers.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void changeName() {
        EditText field = new EditText(this);
        field.setText(identity.getName());
        new AlertDialog.Builder(this)
                .setTitle("Your display name")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    identity.setName(field.getText().toString());
                    me.setText("You: " + identity.getName());
                    Mesh.restart(this);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ---- Permissions ----

    private String[] neededPermissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return p.toArray(new String[0]);
    }

    private void requestNeededPermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : neededPermissions()) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (missing.isEmpty()) {
            Mesh.start(this);
        } else {
            ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        boolean allGranted = grantResults.length > 0;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
        }
        if (allGranted) {
            Mesh.start(this);
        } else {
            status.setText("Permissions denied — can't find nearby devices. Grant them in Settings.");
        }
    }

    // ---- MeshListener (link status) ----

    @Override
    public void onStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    public void onPeerCountChanged(int count) {
        runOnUiThread(() -> status.setText(count == 0
                ? "Looking for people nearby..."
                : count + " device" + (count == 1 ? "" : "s") + " connected"));
    }

    // ---- PeerStore.Observer ----

    @Override
    public void onPeersChanged() {
        runOnUiThread(this::refreshPeers);
    }

    @Override
    public void onConversation(String convoKey, Message msg) {
        // A new message changes the preview/ordering on the home list.
        runOnUiThread(this::refreshPeers);
    }
}
