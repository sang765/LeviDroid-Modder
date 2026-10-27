package org.levimc.launcher.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.Log;

import org.levimc.launcher.R;

/**
 * Hands out a Resources instance pinned to the values-appliance-night (pure black)
 * palette via createConfigurationContext, so the configuration stays scoped to the
 * derived Resources instead of being pushed into the shared AssetManager.
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
            Resources baseRes = super.getResources();
            Configuration config = new Configuration(baseRes.getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_TYPE_MASK)
                    | Configuration.UI_MODE_TYPE_APPLIANCE
                    | Configuration.UI_MODE_NIGHT_YES;
            // The deprecated new Resources(...) + updateConfiguration() route pushes
            // the configuration into the shared AssetManager; the game session runs
            // in this same process and overwrites it, so AMOLED degraded to the M3
            // dark palette after returning to the launcher.
            Resources amoled = getBaseContext().createConfigurationContext(config).getResources();
            // Self-check: if the appliance qualifier is not honored, degrade to the
            // M3 dark palette instead of showing a mixed screen.
            int background = amoled.getColor(R.color.background, null);
            if (background != 0xFF000000) {
                Log.w(TAG, "AMOLED palette did not resolve (background=#"
                        + Integer.toHexString(background) + "), falling back to base resources");
                resources = baseRes;
            } else {
                resources = amoled;
                Log.i(TAG, "AMOLED palette active (background=#"
                        + Integer.toHexString(background) + ")");
            }
        }
        return resources;
    }

    @Override
    public Context createConfigurationContext(Configuration override) {
        // AppCompat applies night mode through applyOverrideConfiguration(); the framework
        // ContextThemeWrapper then rebuilds its Resources via createConfigurationContext(),
        // which would drop this wrapper (and the appliance qualifier) and fall back to the
        // plain M3 dark palette.
        return new AmoledContext(super.createConfigurationContext(override));
    }
}
