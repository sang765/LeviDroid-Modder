package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class XboxPeopleAdapter extends RecyclerView.Adapter<XboxPeopleAdapter.ViewHolder> {

    public interface Listener {
        void onOpen(XboxLiveClient.XboxFriend friend);

        void onAdd(XboxLiveClient.XboxFriend friend);
    }

    private final List<XboxLiveClient.XboxFriend> items = new ArrayList<>();
    private final List<XboxLiveClient.XboxFriend> visible = new ArrayList<>();
    private final Set<String> myFriends;
    private final Listener listener;

    public XboxPeopleAdapter(Set<String> myFriends, Listener listener) {
        this.myFriends = myFriends;
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> people) {
        items.clear();
        items.addAll(people);
        refilter(null);
    }

    public void refilter(String query) {
        visible.clear();
        String needle = query == null ? "" : query.trim().toLowerCase();
        for (XboxLiveClient.XboxFriend friend : items) {
            if (needle.isEmpty()
                    || (XboxLiveClient.displayName(friend) != null
                    && XboxLiveClient.displayName(friend).toLowerCase().contains(needle))) {
                visible.add(friend);
            }
        }
        notifyDataSetChanged();
    }

    /** Called after the local friends set changes so add buttons update. */
    public void refreshFriendState() {
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_xbox_person, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        XboxLiveClient.XboxFriend friend = visible.get(position);
        holder.gamertag.setText(XboxLiveClient.displayName(friend));
        holder.presence.setText(XboxLiveClient.presenceLabel(holder.itemView.getContext(), friend));
        holder.presence.setTextColor(XboxLiveClient.presenceColor(holder.itemView.getContext(), friend));

        Glide.with(holder.avatar)
                .load(friend.displayPicRaw)
                .circleCrop()
                .into(holder.avatar);

        boolean mine = friend.xuid != null && myFriends.contains(friend.xuid);
        holder.add.setVisibility(mine ? View.GONE : View.VISIBLE);
        holder.add.setOnClickListener(v -> listener.onAdd(friend));
        holder.itemView.setOnClickListener(v -> listener.onOpen(friend));
    }

    @Override
    public int getItemCount() {
        return visible.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView gamertag;
        final TextView presence;
        final Button add;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.person_avatar);
            gamertag = itemView.findViewById(R.id.person_gamertag);
            presence = itemView.findViewById(R.id.person_presence);
            add = itemView.findViewById(R.id.person_add);
        }
    }
}
