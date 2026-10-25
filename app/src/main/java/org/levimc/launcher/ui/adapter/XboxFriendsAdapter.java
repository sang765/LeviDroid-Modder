package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;

import java.util.ArrayList;
import java.util.List;

public class XboxFriendsAdapter extends RecyclerView.Adapter<XboxFriendsAdapter.ViewHolder> {

    private static final int MENU_PROFILE = 1;
    private static final int MENU_REMOVE = 2;

    public interface Listener {
        void onViewProfile(XboxLiveClient.XboxFriend friend);

        void onRemoveFriend(XboxLiveClient.XboxFriend friend);
    }

    private final List<XboxLiveClient.XboxFriend> items = new ArrayList<>();
    private final Listener listener;

    public XboxFriendsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> friends) {
        items.clear();
        items.addAll(friends);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_xbox_friend, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        XboxLiveClient.XboxFriend friend = items.get(position);
        holder.gamertag.setText(XboxLiveClient.displayName(friend));
        holder.presence.setText(XboxLiveClient.presenceLabel(holder.itemView.getContext(), friend));
        holder.presence.setTextColor(XboxLiveClient.presenceColor(holder.itemView.getContext(), friend));
        applyOnlineBorder(holder.avatar, friend);

        Glide.with(holder.avatar)
                .load(friend.displayPicRaw)
                .circleCrop()
                .into(holder.avatar);

        View.OnClickListener showMenu = v -> showMenu(v, friend);
        holder.more.setOnClickListener(showMenu);
        holder.itemView.setOnClickListener(showMenu);
    }

    private void showMenu(View anchor, XboxLiveClient.XboxFriend friend) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(Menu.NONE, MENU_PROFILE, Menu.NONE, R.string.xbox_friends_view_profile);
        menu.getMenu().add(Menu.NONE, MENU_REMOVE, Menu.NONE, R.string.xbox_friends_remove_title);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_PROFILE:
                    listener.onViewProfile(friend);
                    return true;
                case MENU_REMOVE:
                    listener.onRemoveFriend(friend);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView gamertag;
        final TextView presence;
        final View more;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.friend_avatar);
            gamertag = itemView.findViewById(R.id.friend_gamertag);
            presence = itemView.findViewById(R.id.friend_presence);
            more = itemView.findViewById(R.id.friend_more);
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
