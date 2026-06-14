package store.anybodycan.meshtalk;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * A single conversation — either one peer (convoKey = their id) or the shared broadcast thread
 * (convoKey = {@link Message#GROUP}). Reads existing history from {@link PeerStore} (now disk-backed)
 * and listens for new messages in THIS conversation only, so each person has their own window.
 *
 * The 📎 button sends a file over a DIRECT link (files don't relay across the mesh); tapping a
 * received file opens it.
 */
public class ChatActivity extends AppCompatActivity
        implements PeerStore.Observer, MeshManager.MeshListener {

    public static final String EXTRA_CONVO = "convo";
    public static final String EXTRA_TITLE = "title";

    private String convoKey;
    private boolean isGroup;
    private Identity identity;
    private ChatAdapter adapter;
    private RecyclerView list;
    private EditText input;
    private TextView status;

    private final ActivityResultLauncher<String> pickFile =
            registerForActivityResult(new ActivityResultContracts.GetContent(), this::onFilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        convoKey = getIntent().getStringExtra(EXTRA_CONVO);
        if (convoKey == null) convoKey = Message.GROUP;
        isGroup = TextUtils.isEmpty(convoKey);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title != null ? title : "Chat");

        identity = Mesh.identity(this);
        status = findViewById(R.id.status);
        list = findViewById(R.id.messages);
        input = findViewById(R.id.input);
        Button send = findViewById(R.id.send);
        Button attach = findViewById(R.id.attach);

        adapter = new ChatAdapter(identity.getId(), this::openFile);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        send.setOnClickListener(v -> sendMessage());
        attach.setOnClickListener(v -> pickFile.launch("*/*"));

        status.setText(isGroup
                ? "Broadcast — everyone nearby sees this"
                : "Private — only " + (title != null ? title : "this person") + " sees this");

        loadHistory();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Mesh.setUiListener(this);
        PeerStore.get().addObserver(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Mesh.clearUiListener(this);
        PeerStore.get().removeObserver(this);
    }

    private void loadHistory() {
        List<Message> history = PeerStore.get().conversation(convoKey);
        adapter.setAll(history);
        if (adapter.size() > 0) list.scrollToPosition(adapter.size() - 1);
    }

    private void sendMessage() {
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        MeshManager mgr = Mesh.manager();
        if (mgr == null) {
            Toast.makeText(this, "Not ready yet (permissions?)", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isGroup) {
            mgr.sendGroup(text);
        } else {
            mgr.sendDirect(convoKey, text);
        }
        // The mesh files our own copy into PeerStore, which calls us back via onConversation.
        input.setText("");
    }

    // ---- file attachments ----

    private void onFilePicked(Uri uri) {
        if (uri == null) return;
        MeshManager mgr = Mesh.manager();
        if (mgr == null) {
            Toast.makeText(this, "Not ready yet (permissions?)", Toast.LENGTH_SHORT).show();
            return;
        }
        // Copy out of the content provider into our own storage (off the main thread), then send.
        // Files travel only over a direct link, unlimited size — never relayed across the mesh.
        final String name = displayName(uri);
        Toast.makeText(this, "Preparing “" + name + "”…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File staged = copyToCache(uri, name);
            if (staged == null) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Couldn't read that file", Toast.LENGTH_SHORT).show());
                return;
            }
            runOnUiThread(() -> mgr.sendFile(convoKey, staged, name, staged.length()));
        }).start();
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) {
        }
        if (TextUtils.isEmpty(name)) name = "file-" + System.currentTimeMillis();
        return name;
    }

    private File copyToCache(Uri uri, String name) {
        File outDir = new File(getCacheDir(), "outgoing");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File out = new File(outDir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** Tap a received attachment to open it; sent files live on the other device. */
    private void openFile(Message m) {
        File dir = new File(getExternalFilesDir(null), "MeshTalk");
        File f = new File(dir, m.text);
        if (!f.exists()) {
            Toast.makeText(this, "“" + m.text + "” isn't stored on this phone", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, getContentResolver().getType(uri));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, "Open with"));
        } catch (Exception e) {
            Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- PeerStore.Observer ----

    @Override
    public void onPeersChanged() {
    }

    @Override
    public void onConversation(String key, Message msg) {
        if (!key.equals(convoKey)) return; // only messages in THIS window
        runOnUiThread(() -> {
            adapter.add(msg);
            list.scrollToPosition(adapter.size() - 1);
        });
    }

    // ---- MeshListener (link status header) ----

    @Override
    public void onStatus(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    @Override
    public void onPeerCountChanged(int count) {
        runOnUiThread(() -> {
            if (count == 0 && !isGroup) {
                status.setText("No devices connected right now");
            }
        });
    }
}
