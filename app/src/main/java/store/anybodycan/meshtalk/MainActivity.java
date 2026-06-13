package store.anybodycan.meshtalk;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements MeshManager.MeshListener {

    private static final int REQ_PERMS = 1001;

    private Identity identity;
    private MeshManager mesh;
    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private TextView status;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        identity = new Identity(this);
        status = findViewById(R.id.status);
        list = findViewById(R.id.messages);
        input = findViewById(R.id.input);
        Button send = findViewById(R.id.send);
        Button name = findViewById(R.id.nameBtn);

        adapter = new ChatAdapter(identity.getId());
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        send.setOnClickListener(v -> sendMessage());
        name.setOnClickListener(v -> changeName());

        setTitle("MeshTalk — " + identity.getName());
        requestNeededPermissions();
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        if (mesh == null) {
            Toast.makeText(this, "Not ready yet (permissions?)", Toast.LENGTH_SHORT).show();
            return;
        }
        mesh.sendChat(text);
        // Show our own message immediately.
        adapter.add(Message.create(identity.getId(), identity.getName(), text, 0));
        list.scrollToPosition(adapter.size() - 1);
        input.setText("");
    }

    private void changeName() {
        EditText field = new EditText(this);
        field.setText(identity.getName());
        new AlertDialog.Builder(this)
                .setTitle("Your display name")
                .setView(field)
                .setPositiveButton("Save", (d, w) -> {
                    identity.setName(field.getText().toString());
                    setTitle("MeshTalk — " + identity.getName());
                    restartMesh();
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
            startMesh();
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
            startMesh();
        } else {
            status.setText("Permissions denied — can't find nearby devices. Grant them in Settings.");
        }
    }

    // ---- Mesh lifecycle ----

    private void startMesh() {
        if (started) return;
        mesh = new MeshManager(this, identity, this);
        mesh.start();
        started = true;
    }

    private void restartMesh() {
        if (mesh != null) {
            mesh.stop();
            started = false;
        }
        startMesh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mesh != null) mesh.stop();
    }

    // ---- MeshListener callbacks ----

    @Override
    public void onMessage(Message msg) {
        runOnUiThread(() -> {
            adapter.add(msg);
            list.scrollToPosition(adapter.size() - 1);
        });
    }

    @Override
    public void onStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    public void onPeerCountChanged(int count) {
        runOnUiThread(() -> {
            String base = count == 0 ? "No one nearby yet"
                    : count + " device" + (count == 1 ? "" : "s") + " connected";
            status.setText(base);
        });
    }
}
