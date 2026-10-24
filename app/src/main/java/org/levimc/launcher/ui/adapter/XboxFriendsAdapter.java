package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;

import java.util.ArrayList;
import java.util.List;

public class XboxFriendsAdapter extends RecyclerView.Adapter<XboxFriendsAdapter.ViewHolder> {

    public interface Listener {
        void onFriendClick(XboxLiveClient.XboxFriend friend);
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
        holder.gamertag.setText(friend.gamertag);
        holder.presence.setText(XboxLiveClient.presenceLabel(holder.itemView.getContext(), friend));
        holder.presence.setTextColor(XboxLiveClient.presenceColor(holder.itemView.getContext(), friend));
        holder.itemView.setOnClickListener(v -> listener.onFriendClick(friend));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView gamertag;
        final TextView presence;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            gamertag = itemView.findViewById(R.id.friend_gamertag);
            presence = itemView.findViewById(R.id.friend_presence);
        }
    }
}
