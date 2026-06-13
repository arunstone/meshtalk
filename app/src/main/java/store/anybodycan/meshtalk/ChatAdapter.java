package store.anybodycan.meshtalk;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders chat messages as left (others) / right (me) bubbles, built in code to keep layouts minimal. */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.VH> {

    private final List<Message> items = new ArrayList<>();
    private final String myId;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    public ChatAdapter(String myId) {
        this.myId = myId;
    }

    public void add(Message m) {
        items.add(m);
        notifyItemInserted(items.size() - 1);
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        FrameLayout frame = new FrameLayout(parent.getContext());
        frame.setLayoutParams(new RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int pad = dp(parent, 6);
        frame.setPadding(pad, pad, pad, pad);

        LinearLayout bubble = new LinearLayout(parent.getContext());
        bubble.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bubble.setLayoutParams(blp);
        int bp = dp(parent, 10);
        bubble.setPadding(bp, bp, bp, bp);

        TextView sender = new TextView(parent.getContext());
        sender.setTextSize(11);
        sender.setTypeface(null, Typeface.BOLD);

        TextView body = new TextView(parent.getContext());
        body.setTextSize(16);

        TextView time = new TextView(parent.getContext());
        time.setTextSize(10);

        bubble.addView(sender);
        bubble.addView(body);
        bubble.addView(time);
        frame.addView(bubble);
        return new VH(frame, bubble, sender, body, time);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Message m = items.get(position);
        boolean mine = m.senderId.equals(myId);

        h.body.setText(m.text);
        h.time.setText(fmt.format(new Date(m.timestamp)));

        FrameLayout.LayoutParams blp = (FrameLayout.LayoutParams) h.bubble.getLayoutParams();
        if (mine) {
            blp.gravity = Gravity.END;
            h.bubble.setBackgroundColor(Color.parseColor("#DCF8C6"));
            h.sender.setVisibility(android.view.View.GONE);
            h.body.setTextColor(Color.parseColor("#111111"));
            h.time.setTextColor(Color.parseColor("#557755"));
            h.time.setGravity(Gravity.END);
        } else {
            blp.gravity = Gravity.START;
            h.bubble.setBackgroundColor(Color.parseColor("#FFFFFF"));
            h.sender.setVisibility(android.view.View.VISIBLE);
            h.sender.setText(m.senderName);
            h.sender.setTextColor(Color.parseColor("#1565C0"));
            h.body.setTextColor(Color.parseColor("#111111"));
            h.time.setTextColor(Color.parseColor("#888888"));
            h.time.setGravity(Gravity.START);
        }
        h.bubble.setLayoutParams(blp);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private static int dp(ViewGroup parent, int v) {
        return Math.round(v * parent.getResources().getDisplayMetrics().density);
    }

    static class VH extends RecyclerView.ViewHolder {
        final LinearLayout bubble;
        final TextView sender, body, time;

        VH(@NonNull FrameLayout root, LinearLayout bubble, TextView sender, TextView body, TextView time) {
            super(root);
            this.bubble = bubble;
            this.sender = sender;
            this.body = body;
            this.time = time;
        }
    }
}
