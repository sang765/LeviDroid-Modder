package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import org.levimc.launcher.R;
import org.levimc.launcher.core.xbox.XboxLiveClient;

import java.util.ArrayList;
import java.util.List;

public class XboxSearchAdapter extends RecyclerView.Adapter<XboxSearchAdapter.ViewHolder> {

    public interface Listener {
        void onAdd(XboxLiveClient.XboxFriend friend);
    }

    private final List<XboxLiveClient.XboxFriend> items = new ArrayList<>();
    private final Listener listener;

    public XboxSearchAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> results) {
        items.clear();
        items.addAll(results);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_xbox_search_result, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        XboxLiveClient.XboxFriend friend = items.get(position);
        holder.gamertag.setText(friend.gamertag);
        holder.presence.setText(XboxLiveClient.presenceLabel(holder.itemView.getContext(), friend));
        holder.presence.setTextColor(XboxLiveClient.presenceColor(holder.itemView.getContext(), friend));
        Glide.with(holder.avatar)
                .load(friend.displayPicRaw)
                .circleCrop()
                .into(holder.avatar);
        holder.itemView.setOnClickListener(v -> listener.onAdd(friend));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView gamertag;
        final TextView presence;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.search_avatar);
            gamertag = itemView.findViewById(R.id.search_gamertag);
            presence = itemView.findViewById(R.id.search_presence);
        }
    }
}
