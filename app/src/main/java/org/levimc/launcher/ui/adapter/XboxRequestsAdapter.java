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

public class XboxRequestsAdapter extends RecyclerView.Adapter<XboxRequestsAdapter.ViewHolder> {

    private static final int TYPE_INCOMING = 0;
    private static final int TYPE_OUTGOING = 1;

    public interface Listener {
        void onAccept(XboxLiveClient.XboxFriend friend);

        void onDecline(XboxLiveClient.XboxFriend friend);

        void onCancel(XboxLiveClient.XboxFriend friend);
    }

    private final List<XboxLiveClient.XboxFriend> incoming = new ArrayList<>();
    private final List<XboxLiveClient.XboxFriend> outgoing = new ArrayList<>();
    private final Listener listener;

    public XboxRequestsAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<XboxLiveClient.XboxFriend> incomingList,
                       List<XboxLiveClient.XboxFriend> outgoingList) {
        incoming.clear();
        incoming.addAll(incomingList);
        outgoing.clear();
        outgoing.addAll(outgoingList);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return position < incoming.size() ? TYPE_INCOMING : TYPE_OUTGOING;
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
        boolean isIncoming = getItemViewType(position) == TYPE_INCOMING;
        XboxLiveClient.XboxFriend friend = isIncoming
                ? incoming.get(position)
                : outgoing.get(position - incoming.size());

        holder.gamertag.setText(friend.gamertag);
        holder.direction.setText(isIncoming
                ? R.string.xbox_friends_incoming
                : R.string.xbox_friends_outgoing);

        if (isIncoming) {
            holder.accept.setVisibility(View.VISIBLE);
            holder.decline.setVisibility(View.VISIBLE);
            holder.cancel.setVisibility(View.GONE);
            holder.accept.setOnClickListener(v -> listener.onAccept(friend));
            holder.decline.setOnClickListener(v -> listener.onDecline(friend));
        } else {
            holder.accept.setVisibility(View.GONE);
            holder.decline.setVisibility(View.GONE);
            holder.cancel.setVisibility(View.VISIBLE);
            holder.cancel.setOnClickListener(v -> listener.onCancel(friend));
        }
    }

    @Override
    public int getItemCount() {
        return incoming.size() + outgoing.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView gamertag;
        final TextView direction;
        final TextView accept;
        final TextView decline;
        final TextView cancel;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            gamertag = itemView.findViewById(R.id.request_gamertag);
            direction = itemView.findViewById(R.id.request_direction);
            accept = itemView.findViewById(R.id.request_accept);
            decline = itemView.findViewById(R.id.request_decline);
            cancel = itemView.findViewById(R.id.request_cancel);
        }
    }
}
