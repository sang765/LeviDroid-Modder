package org.levimc.launcher.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.ItemTouchHelper;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.Mod;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public class ModsAdapter extends RecyclerView.Adapter<ModsAdapter.ModViewHolder> {

    private List<Mod> mods = new ArrayList<>();
    private OnModEnableChangeListener onModEnableChangeListener;
    private OnModReorderListener onModReorderListener;
    private OnModClickListener onModClickListener;
    private ItemTouchHelper itemTouchHelper;
    private String currentVersionCode;


    public interface OnModEnableChangeListener {
        void onEnableChanged(Mod mod, boolean enabled);
    }

    public interface OnModReorderListener {
        void onModsReordered(List<Mod> reorderedMods);
    }

    public interface OnModClickListener {
        void onModClick(Mod mod, int position, View sharedView);
    }

    public ModsAdapter(List<Mod> mods) {
        this.mods = mods;
    }


    public void setOnModEnableChangeListener(OnModEnableChangeListener l) {
        this.onModEnableChangeListener = l;
    }

    public void setOnModReorderListener(OnModReorderListener listener) {
        this.onModReorderListener = listener;
    }

    public void setOnModClickListener(OnModClickListener listener) {
        this.onModClickListener = listener;
    }

    public void setItemTouchHelper(ItemTouchHelper itemTouchHelper) {
        this.itemTouchHelper = itemTouchHelper;
    }

    @NonNull
    @Override
    public ModViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod, parent, false);
        return new ModViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ModViewHolder holder, int position) {
        Mod mod = mods.get(position);
        holder.name.setText(mod.getDisplayName());
        holder.authorText.setText(holder.itemView.getContext().getString(
                R.string.mod_author_byline,
                fallbackText(mod.getAuthor(), holder.itemView.getContext().getString(R.string.mod_unknown_author))));
        holder.versionText.setText(holder.itemView.getContext().getString(
                R.string.mod_version_chip,
                fallbackText(mod.getVersion(), holder.itemView.getContext().getString(R.string.mod_unknown_version))));

        bindIcon(holder, mod);
        bindMeta(holder, mod);
        bindCompatWarning(holder, mod);

        if (holder.orderText != null) {
            holder.orderText.setText(holder.itemView.getContext().getString(R.string.mod_load_order, position + 1));
            holder.orderText.setVisibility(View.VISIBLE);
        }

        if (holder.configBadge != null) {
            holder.configBadge.setVisibility(View.GONE);
        }

        holder.switchBtn.setOnCheckedChangeListener(null);
        holder.switchBtn.setChecked(mod.isEnabled());

        holder.switchBtn.setOnCheckedChangeListener((btn, isChecked) -> {
            if (isChecked == mod.isEnabled()) return;

            mod.setEnabled(isChecked);

            if (onModEnableChangeListener != null) {
                onModEnableChangeListener.onEnableChanged(mod, isChecked);
            }
        });

        if (holder.dragHandle != null) {
            holder.dragHandle.setVisibility(View.GONE);
            holder.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                    v.setVisibility(View.VISIBLE);
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });
        }

        ViewCompat.setTransitionName(holder.itemView, "mod_card_" + mod.getId());

        holder.itemView.setOnClickListener(v -> {
            if (onModClickListener != null) {
                onModClickListener.onModClick(mod, position, holder.itemView);
            }
        });

        android.content.Context context = holder.itemView.getContext();
        org.levimc.launcher.util.PersonalizationManager pm = new org.levimc.launcher.util.PersonalizationManager(context);
        pm.applyGlassToView(holder.itemView);
        pm.applyAccentToView(holder.itemView, context);
    }

    @Override
    public int getItemCount() {
        return mods != null ? mods.size() : 0;
    }

    public Mod getItem(int pos) {
        return mods.get(pos);
    }

    public void removeAt(int pos) {
        mods.remove(pos);
        notifyItemRemoved(pos);
    }

    public void updateMods(List<Mod> list) {
        this.mods = list;
        notifyDataSetChanged();
    }

    public void moveItem(int fromPosition, int toPosition) {
        if (mods == null || mods.isEmpty()) return;
        if (fromPosition < 0 || toPosition < 0) return;
        if (fromPosition >= mods.size() || toPosition >= mods.size()) return;
        if (fromPosition == toPosition) return;

        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(mods, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(mods, i, i - 1);
            }
        }
        notifyItemMoved(fromPosition, toPosition);
    }

    public void commitReorder() {
        if (onModReorderListener != null) {
            onModReorderListener.onModsReordered(new ArrayList<>(mods));
        }
    }

    public void setCurrentVersionCode(String versionCode) {
        this.currentVersionCode = versionCode;
    }

    public void setDragHandleVisible(RecyclerView.ViewHolder holder, boolean visible) {
        if (holder instanceof ModViewHolder && ((ModViewHolder) holder).dragHandle != null) {
            ((ModViewHolder) holder).dragHandle.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void bindIcon(ModViewHolder holder, Mod mod) {
        if (holder.modIcon == null) return;
        File iconFile = mod.getIconPath() == null ? null : new File(mod.getIconPath());
        int radiusPx = Math.round(8f * holder.itemView.getResources().getDisplayMetrics().density);
        Glide.with(holder.itemView.getContext())
                .load(iconFile)
                .apply(RequestOptions.bitmapTransform(new RoundedCorners(radiusPx)))
                .placeholder(R.drawable.ic_plugin)
                .error(R.drawable.ic_plugin)
                .into(holder.modIcon);
    }

    private void bindMeta(ModViewHolder holder, Mod mod) {
        if (holder.metaText == null) return;
        File entry = mod.getEntryPath() == null ? null : new File(mod.getEntryPath());
        if (entry == null || !entry.isFile()) {
            holder.metaText.setVisibility(View.GONE);
            return;
        }
        String size = android.text.format.Formatter.formatShortFileSize(
                holder.itemView.getContext(), entry.length());
        String date = DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(entry.lastModified()));
        holder.metaText.setText(size + " · " + date);
        holder.metaText.setVisibility(View.VISIBLE);
    }

    private void bindCompatWarning(ModViewHolder holder, Mod mod) {
        if (holder.compatWarning == null) return;
        List<String> mcVersions = mod.getMinecraftVersions();
        boolean incompatible = currentVersionCode != null && !mcVersions.isEmpty();
        if (incompatible) {
            for (String required : mcVersions) {
                if (matchesMinecraftVersion(required, currentVersionCode)) {
                    incompatible = false;
                    break;
                }
            }
        }
        if (incompatible) {
            holder.compatWarning.setText(holder.itemView.getContext().getString(
                    R.string.mod_compat_warning, String.join(", ", mcVersions)));
            holder.compatWarning.setVisibility(View.VISIBLE);
        } else {
            holder.compatWarning.setVisibility(View.GONE);
        }
    }

    // shortcut: matches exact, prefix ("1.21") and ".x" patterns only; extend if the catalog starts emitting range syntax like "[1.20,1.21)"
    private boolean matchesMinecraftVersion(String pattern, String current) {
        String p = pattern == null ? "" : pattern.trim();
        if (p.isEmpty() || p.equalsIgnoreCase("any")) return true;
        if (p.endsWith(".x")) return current.startsWith(p.substring(0, p.length() - 2));
        return current.equals(p) || current.startsWith(p + ".");
    }

    private String fallbackText(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    static class ModViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView orderText;
        TextView configBadge;
        TextView authorText;
        TextView versionText;
        TextView metaText;
        TextView compatWarning;
        Switch switchBtn;
        android.widget.ImageView dragHandle;
        ImageView modIcon;

        public ModViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.mod_name);
            orderText = itemView.findViewById(R.id.mod_order);
            configBadge = itemView.findViewById(R.id.mod_config_badge);
            authorText = itemView.findViewById(R.id.mod_author);
            versionText = itemView.findViewById(R.id.mod_version);
            metaText = itemView.findViewById(R.id.mod_meta);
            compatWarning = itemView.findViewById(R.id.mod_compat_warning);
            switchBtn = itemView.findViewById(R.id.mod_switch);
            dragHandle = itemView.findViewById(R.id.drag_handle);
            modIcon = itemView.findViewById(R.id.mod_icon);
        }
    }
}
