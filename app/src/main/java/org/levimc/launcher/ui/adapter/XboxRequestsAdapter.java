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

public class XboxRequestsAdapter extends RecyclerView.Adapter<XboxRequestsAdapter.ViewHolder> {

    public interface Listener {
        void onAccept(XboxLiveClient.XboxFriend friend);

        void onDecline(XboxLiveClient.XboxFriend friend);
    }

    private final List<XboxLiveClient.XboxFriend> incoming = new ArrayList<>();
    private final Listener listener;

    public XboxRequestsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> incomingList) {
        incoming.clear();
        incoming.addAll(incomingList);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_xbox_request, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        XboxLiveClient.XboxFriend friend = incoming.get(position);
        holder.gamertag.setText(friend.gamertag);
        holder.direction.setText(R.string.xbox_friends_incoming);
        Glide.with(holder.avatar)
                .load(friend.displayPicRaw)
                .circleCrop()
                .into(holder.avatar);
        holder.accept.setOnClickListener(v -> listener.onAccept(friend));
        holder.decline.setOnClickListener(v -> listener.onDecline(friend));
    }

    @Override
    public int getItemCount() {
        return incoming.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView avatar;
        final TextView gamertag;
        final TextView direction;
        final TextView accept;
        final TextView decline;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.request_avatar);
            gamertag = itemView.findViewById(R.id.request_gamertag);
            direction = itemView.findViewById(R.id.request_direction);
            accept = itemView.findViewById(R.id.request_accept);
            decline = itemView.findViewById(R.id.request_decline);
        }
    }
}
