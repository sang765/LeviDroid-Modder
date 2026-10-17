package org.levimc.launcher.ui.dialogs;

import android.content.Context;

import androidx.appcompat.app.AlertDialog;

import org.levimc.launcher.R;
import org.levimc.launcher.util.PersonalizationManager;

/**
 * Shared dialog design kit: one accent source for every dialog in the
 * launcher. Layout-based dialogs take {@link #accent(Context)} directly;
 * alert dialogs get their buttons tinted through {@link #applyAccent}.
 */
public final class DialogStyler {

    private DialogStyler() {
    }

    public static int accent(Context context) {
        try {
            int accent = new PersonalizationManager(context).getAccentColor();
            if (accent != 0) {
                return accent;
            }
        } catch (Exception ignored) {
        }
        return context.getResources().getColor(R.color.accent_text, context.getTheme());
    }

    /** Call right after show(); buttons do not exist before that. */
    public static void applyAccent(AlertDialog dialog) {
        int accent = accent(dialog.getContext());
        for (int id : new int[]{
                AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            if (dialog.getButton(id) != null) {
                dialog.getButton(id).setTextColor(accent);
            }
        }
    }

    /** Framework variant used by the in-game mod menu dialogs. */
    public static void applyAccent(android.app.AlertDialog dialog) {
        int accent = accent(dialog.getContext());
        for (int id : new int[]{
                android.app.AlertDialog.BUTTON_POSITIVE,
                android.app.AlertDialog.BUTTON_NEGATIVE,
                android.app.AlertDialog.BUTTON_NEUTRAL}) {
            if (dialog.getButton(id) != null) {
                dialog.getButton(id).setTextColor(accent);
            }
        }
    }
}
