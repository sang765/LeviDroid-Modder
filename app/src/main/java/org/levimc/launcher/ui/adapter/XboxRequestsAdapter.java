package org.levimc.launcher.ui.adapter;

import android.content.Context;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;

import java.util.ArrayList;
import java.util.List;

public class XboxRequestsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int TYPE_SECTION = 0;
    public static final int TYPE_REQUEST = 1;

    public interface Listener {
        void onAccept(XboxLiveClient.XboxFriend friend);

        void onDecline(XboxLiveClient.XboxFriend friend);

        void onCancelOutgoing(XboxLiveClient.XboxFriend friend);
    }

    private static class Item {
        final int type;
        final XboxLiveClient.XboxFriend friend;
        final boolean outgoing;

        private Item(int type, XboxLiveClient.XboxFriend friend, boolean outgoing) {
            this.type = type;
            this.friend = friend;
            this.outgoing = outgoing;
        }

        static Item section() {
            return new Item(TYPE_SECTION, null, false);
        }

        static Item request(XboxLiveClient.XboxFriend friend, boolean outgoing) {
            return new Item(TYPE_REQUEST, friend, outgoing);
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final Listener listener;

    public XboxRequestsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> incomingList) {
        submit(incomingList, new ArrayList<>());
    }

    public void submit(List<XboxLiveClient.XboxFriend> incomingList,
                      List<XboxLiveClient.XboxFriend> outgoingList) {
        items.clear();
        if (incomingList != null) {
            for (XboxLiveClient.XboxFriend friend : incomingList) {
                items.add(Item.request(friend, false));
            }
        }
        if (outgoingList != null && !outgoingList.isEmpty()) {
            items.add(Item.section());
            for (XboxLiveClient.XboxFriend friend : outgoingList) {
                items.add(Item.request(friend, true));
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_SECTION) {
            Context context = parent.getContext();
            TextView header = new TextView(context);
            float density = context.getResources().getDisplayMetrics().density;
            int pad = (int) (4 * density);
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            header.setLayoutParams(params);
            header.setPadding(pad, (int) (16 * density), pad, (int) (6 * density));
            header.setText(context.getString(R.string.xbox_friends_outgoing_section));
            header.setTextColor(context.getColor(R.color.text_secondary));
            header.setTextSize(13);
            header.setTypeface(ResourcesCompat.getFont(context, R.font.misans), Typeface.BOLD);
            return new SectionHolder(header);
        }

        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_xbox_request, parent, false);
        return new RequestHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item item = items.get(position);
        if (holder instanceof SectionHolder) {
            return;
        }
        RequestHolder requestHolder = (RequestHolder) holder;
        XboxLiveClient.XboxFriend friend = item.friend;
        requestHolder.gamertag.setText(friend.gamertag);
        Glide.with(requestHolder.avatar)
                .load(friend.displayPicRaw)
                .circleCrop()
                .into(requestHolder.avatar);
        applyOnlineBorder(requestHolder.avatar, friend);

        if (item.outgoing) {
            requestHolder.direction.setText(R.string.xbox_friends_outgoing);
            requestHolder.accept.setVisibility(View.GONE);
            requestHolder.decline.setVisibility(View.VISIBLE);
            requestHolder.decline.setText(R.string.xbox_friends_cancel_request);
            requestHolder.decline.setOnClickListener(v -> listener.onCancelOutgoing(friend));
        } else {
            requestHolder.direction.setText(R.string.xbox_friends_incoming);
            requestHolder.accept.setVisibility(View.VISIBLE);
            requestHolder.accept.setText(R.string.xbox_friends_accept);
            requestHolder.decline.setVisibility(View.VISIBLE);
            requestHolder.decline.setText(R.string.xbox_friends_decline);
            requestHolder.accept.setOnClickListener(v -> listener.onAccept(friend));
            requestHolder.decline.setOnClickListener(v -> listener.onDecline(friend));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        SectionHolder(View itemView) {
            super(itemView);
        }
    }

    static class RequestHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView gamertag;
        final TextView direction;
        final TextView accept;
        final TextView decline;

        RequestHolder(View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.request_avatar);
            gamertag = itemView.findViewById(R.id.request_gamertag);
            direction = itemView.findViewById(R.id.request_direction);
            accept = itemView.findViewById(R.id.request_accept);
            decline = itemView.findViewById(R.id.request_decline);
        }
    }

    private static void applyOnlineBorder(ImageView avatar, XboxLiveClient.XboxFriend friend) {
        if ("Online".equals(friend.presenceState)) {
            avatar.setBackgroundResource(R.drawable.xbox_avatar_online_border);
        } else {
            avatar.setBackground(null);
        }
    }
}
