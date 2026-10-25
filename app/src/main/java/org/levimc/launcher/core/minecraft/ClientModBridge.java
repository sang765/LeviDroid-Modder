package org.levimc.launcher.core.minecraft;

import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dalvik.system.BaseDexClassLoader;

/**
 * Bridge for client/patch APKs whose mod menu lives in the APK's own dex
 * (e.g. Sigma-family clients: a GLSurfaceView subclass backed by an injected
 * .so that renders an ImGui menu). The launcher never runs the imported
 * APK's Java code, so without this bridge the menu never appears. Everything
 * is best effort: any failure logs and leaves the vanilla launch untouched.
 */
public final class ClientModBridge {

    private static final String TAG = "ClientModBridge";

    private ClientModBridge() {
    }

    /** Attaches the imported APK's menu overlay view, if it ships one. */
    public static void attach(MinecraftActivity activity) {
        try {
            String versionDirPath = activity.getIntent().getStringExtra("MC_PATH");
            String profileId = activity.getIntent()
                    .getStringExtra(MinecraftLauncher.EXTRA_STORAGE_PROFILE_ID);
            if (versionDirPath == null || versionDirPath.isEmpty() || profileId == null) {
                return;
            }
            File versionDir = new File(versionDirPath);
            List<File> apks = collectApks(versionDir);
            File libDir = runtimeLibDir(activity, profileId);
            if (apks.isEmpty() || libDir == null || !libDir.isDirectory()) {
                return;
            }
            Class<?> viewClass = findOverlayViewClass(apks, activity);
            if (viewClass == null) {
                return;
            }
            loadExtraLibs(libDir);
            View overlay = createOverlay(activity, viewClass);
            if (overlay == null) {
                return;
            }
            activity.addContentView(overlay,
                    new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT));
            android.util.Log.i(TAG, "Attached client menu overlay: " + viewClass.getName());
        } catch (Throwable error) {
            android.util.Log.w(TAG, "Client menu bridge failed", error);
        }
    }

    private static File runtimeLibDir(MinecraftActivity activity, String profileId) {
        String abi = null;
        for (String candidate : Build.SUPPORTED_ABIS) {
            if (candidate.contains("arm64-v8a") || candidate.contains("armeabi-v7a")
                    || candidate.contains("x86_64") || candidate.contains("x86")) {
                abi = candidate;
                break;
            }
        }
        if (abi == null) return null;
        File dir = MinecraftLauncher.getRuntimeLibAbiDir(activity, profileId, abi);
        return dir != null && dir.isDirectory() ? dir : null;
    }

    private static List<File> collectApks(File versionDir) {
        List<File> apks = new ArrayList<>();
        File base = new File(versionDir, "base.apk.levi");
        if (base.isFile()) apks.add(base);
        File[] splits = new File(versionDir, "splits").listFiles();
        if (splits != null) {
            for (File split : splits) {
                if (split.isFile() && split.getName().endsWith(".apk.levi")) apks.add(split);
            }
        }
        return apks;
    }

    /** Finds a GLES3JNIView-style overlay class in the APK's dex. */
    private static Class<?> findOverlayViewClass(List<File> apks, Context context) throws Exception {
        StringBuilder searchPaths = new StringBuilder();
        for (File apk : apks) searchPaths.append(File.pathSeparatorChar).append(apk.getAbsolutePath());
        DexClassLoader loader = new DexClassLoader(
                searchPaths.substring(1), context.getCodeCacheDir().getAbsolutePath(),
                null, ClientModBridge.class.getClassLoader());
        for (File apk : apks) {
            for (Class<?> candidate : dexClassesEndingWith(loader, apk, "GLES3JNIView")) {
                if (View.class.isAssignableFrom(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static List<Class<?>> dexClassesEndingWith(DexClassLoader loader, File apk, String suffix) throws Exception {
        List<Class<?>> out = new ArrayList<>();
        Object pathList = readField(loader, "pathList");
        Object[] elements = (Object[]) readField(pathList, "dexElements");
        if (elements == null || elements.length == 0) return out;
        Object dexFile = readField(elements[0], "dexFile");
        if (dexFile == null) return out;
        java.util.Enumeration<String> entries =
                (java.util.Enumeration<String>) dexFile.getClass()
                        .getMethod("entries").invoke(dexFile);
        while (entries.hasMoreElements()) {
            String name = entries.nextElement();
            if (!name.endsWith(suffix)) continue;
            try {
                Class<?> clazz = loader.loadClass(name);
                if (View.class.isAssignableFrom(clazz)) out.add(clazz);
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static Object readField(Object target, String name) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static void loadExtraLibs(File nativeLibDir) {
        File[] libs = nativeLibDir.listFiles((dir, name) -> name.endsWith(".so"));
        if (libs == null) return;
        for (File lib : libs) {
            String name = lib.getName();
            if (name.startsWith("libminecraftpe") || name.startsWith("libpreloader")
                    || name.startsWith("libgxcore") || name.startsWith("libinbuiltmods")) {
                continue;
            }
            try {
                System.load(lib.getAbsolutePath());
                android.util.Log.i(TAG, "Loaded client lib " + name);
            } catch (Throwable error) {
                android.util.Log.w(TAG, "Client lib load failed: " + name, error);
            }
        }
    }

    private static View createOverlay(Context context, Class<?> viewClass) throws Exception {
        Exception last = null;
        for (Class<?>[] sig : new Class<?>[][]{
                {Context.class}, {Context.class, android.util.AttributeSet.class}, {}}) {
            try {
                java.lang.reflect.Constructor<?> ctor = viewClass.getDeclaredConstructor(sig);
                ctor.setAccessible(true);
                Object[] args = sig.length == 2
                        ? new Object[]{context, null} : sig.length == 1 ? new Object[]{context} : new Object[]{};
                View view = (View) ctor.newInstance(args);
                // Sigma-family clients compute their config path from the game package.
                try {
                    Method setPkg = viewClass.getMethod("setPackageName", String.class);
                    setPkg.invoke(view, "com.mojang.minecraftpe");
                } catch (NoSuchMethodException ignored) {
                }
                return view;
            } catch (Exception error) {
                last = error;
            }
        }
        if (last != null) throw last;
        return null;
    }
}
