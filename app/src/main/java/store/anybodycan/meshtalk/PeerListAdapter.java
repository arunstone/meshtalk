package store.anybodycan.meshtalk;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The home screen list: a pinned "Everyone (Group)" row on top, then every active device
 * we've heard from, most-recent first. Each row shows name, the last message preview, the
 * last-seen time and a green dot if they were heard from in the last few seconds.
 */
public class PeerListAdapter extends RecyclerView.Adapter<PeerListAdapter.VH> {

    /** A peer is considered "online" if heard from within this window. */
    private static final long ONLINE_WINDOW_MS = 15000;

    public interface OnPeerClick {
        /** convoKey is {@link Message#GROUP} for the group row, else the peer's id. */
        void open(String convoKey, String title);
    }

    private final PeerStore store = PeerStore.get();
    private final OnPeerClick click;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private List<Peer> peers = new ArrayList<>();

    public PeerListAdapter(OnPeerClick click) {
        this.click = click;
    }

    public void setPeers(List<Peer> peers) {
        this.peers = peers;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout row = new LinearLayout(parent.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(parent, 14);
        row.setPadding(pad, dp(parent, 12), pad, dp(parent, 12));
        row.setClickable(true);
        row.setBackgroundColor(Color.WHITE);

        TextView dot = new TextView(parent.getContext());
        dot.setTextSize(14);
        dot.setText("●"); // ●
        dot.setPadding(0, 0, dp(parent, 12), 0);

        LinearLayout mid = new LinearLayout(parent.getContext());
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(parent.getContext());
        name.setTextSize(16);
        name.setTextColor(Color.parseColor("#111111"));
        name.setTypeface(null, Typeface.BOLD);

        TextView preview = new TextView(parent.getContext());
        preview.setTextSize(13);
        preview.setTextColor(Color.parseColor("#777777"));
        preview.setMaxLines(1);

        mid.addView(name);
        mid.addView(preview);

        TextView time = new TextView(parent.getContext());
        time.setTextSize(11);
        time.setTextColor(Color.parseColor("#999999"));

        row.addView(dot);
        row.addView(mid);
        row.addView(time);
        return new VH(row, dot, name, preview, time);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        if (position == 0) {
            bindGroup(h);
            return;
        }
        Peer p = peers.get(position - 1);
        boolean online = (System.currentTimeMillis() - p.lastSeen) < ONLINE_WINDOW_MS;

        h.dot.setText("●");
        h.dot.setTextColor(online ? Color.parseColor("#25D366") : Color.parseColor("#CCCCCC"));
        h.name.setText(p.name + hopSuffix(p.hops));

        Message last = store.lastMessage(p.id);
        if (last != null) {
            h.preview.setVisibility(View.VISIBLE);
            String who = last.senderId.equals(p.id) ? "" : "You: ";
            h.preview.setText(who + previewText(last));
            h.time.setText(fmt.format(new Date(last.timestamp)));
        } else {
            h.preview.setVisibility(View.VISIBLE);
            h.preview.setText(online ? "Online — tap to chat" : "Tap to chat");
            h.time.setText(fmt.format(new Date(p.lastSeen)));
        }

        h.itemView.setOnClickListener(v -> click.open(p.id, p.name));
    }

    private void bindGroup(VH h) {
        h.dot.setText("▣"); // ▣ a group glyph
        h.dot.setTextColor(Color.parseColor("#075E54"));
        h.name.setText("Everyone (Group)");

        Message last = store.lastMessage(Message.GROUP);
        if (last != null) {
            h.preview.setVisibility(View.VISIBLE);
            h.preview.setText(last.senderName + ": " + previewText(last));
            h.time.setText(fmt.format(new Date(last.timestamp)));
        } else {
            h.preview.setVisibility(View.VISIBLE);
            h.preview.setText("Broadcast to everyone nearby");
            h.time.setText("");
        }
        h.itemView.setOnClickListener(v -> click.open(Message.GROUP, "Everyone (Group)"));
    }

    @Override
    public int getItemCount() {
        return peers.size() + 1; // +1 for the pinned Group row
    }

    private static String previewText(Message m) {
        return m.isFile() ? "📎 " + m.text : m.text;
    }

    /** "  · 1 hop" / "  · 3 hops away" shown next to the name; nothing if distance is unknown. */
    private static String hopSuffix(int hops) {
        if (hops <= 0) return "";
        if (hops == 1) return "  · 1 hop";
        return "  · " + hops + " hops away";
    }

    private static int dp(ViewGroup parent, int v) {
        return Math.round(v * parent.getResources().getDisplayMetrics().density);
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView dot, name, preview, time;

        VH(@NonNull View root, TextView dot, TextView name, TextView preview, TextView time) {
            super(root);
            this.dot = dot;
            this.name = name;
            this.preview = preview;
            this.time = time;
        }
    }
}
