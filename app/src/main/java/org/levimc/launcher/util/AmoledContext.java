package org.levimc.launcher.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;

/**
 * Hands out a Resources instance pinned to the values-appliance-night (pure black)
 * palette. The configuration is built directly on the wrapped Resources so that
 * neither AppCompat night-mode reconfiguration nor the framework can rewrite the
 * uiMode bits before resource matching happens.
 */
public final class AmoledContext extends ContextWrapper {
    private static final String TAG = "AmoledContext";
    private Resources resources;

    public AmoledContext(Context base) {
        super(base);
    }

    @Override
    public Resources getResources() {
        if (resources == null) {
            Resources base = super.getResources();
            Configuration config = new Configuration(base.getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_TYPE_MASK)
                    | Configuration.UI_MODE_TYPE_APPLIANCE
                    | Configuration.UI_MODE_NIGHT_YES;
            Resources amoled = new Resources(base.getAssets(), base.getDisplayMetrics(), config);
            // Self-check: if the appliance qualifier is not honored, degrade to the
            // M3 dark palette instead of showing a mixed screen.
            if (amoled.getColor(R.color.background, null) != 0xFF000000) {
                Log.w(TAG, "AMOLED palette did not resolve, falling back to base resources");
                resources = base;
            } else {
                resources = amoled;
            }
        }
        return resources;
    }
}
