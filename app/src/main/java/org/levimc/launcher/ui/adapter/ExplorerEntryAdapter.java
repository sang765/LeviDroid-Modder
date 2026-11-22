package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.levimc.launcher.R;
import org.levimc.launcher.core.explorer.ExplorerEntry;
import org.levimc.launcher.core.explorer.ExplorerRepository;

import java.util.ArrayList;
import java.util.List;

public class ExplorerEntryAdapter extends RecyclerView.Adapter<ExplorerEntryAdapter.ViewHolder> {
    public interface OnEntryClickListener {
        void onEntryClick(ExplorerEntry entry);
        void onEntryLongClick(ExplorerEntry entry);
    }

    private final List<ExplorerEntry> entries = new ArrayList<>();
    private final OnEntryClickListener listener;

    public ExplorerEntryAdapter(OnEntryClickListener listener) {
        this.listener = listener;
    }

    public void setEntries(List<ExplorerEntry> values) {
        entries.clear();
        entries.addAll(values);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_explorer_entry, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.bind(entries.get(position), listener);
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView name;
        final TextView meta;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.explorer_entry_icon);
            name = itemView.findViewById(R.id.explorer_entry_name);
            meta = itemView.findViewById(R.id.explorer_entry_meta);
        }

        void bind(ExplorerEntry entry, OnEntryClickListener listener) {
            name.setText(entry.file.getName());
            if (entry.directory) {
                icon.setImageResource(R.drawable.ic_ui_folder);
                meta.setText(itemView.getContext().getString(R.string.explorer_entry_folder));
            } else {
                icon.setImageResource(ExplorerRepository.isTextFile(entry.file.getName())
                        ? R.drawable.ic_code : R.drawable.ic_misc);
                meta.setText(ExplorerRepository.formatSize(entry.size) + " · "
                        + ExplorerRepository.formatDate(entry.modified));
            }
            itemView.setOnClickListener(v -> listener.onEntryClick(entry));
            itemView.setOnLongClickListener(v -> {
                listener.onEntryLongClick(entry);
                return true;
            });
        }
    }
}
