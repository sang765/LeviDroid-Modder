package org.levimc.launcher.core.minecraft;

import android.content.Context;
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
            File nativeLibDir = MinecraftLauncher.getRuntimeLibAbiDir(activity);
            List<File> apks = collectApks(activity);
            if (apks.isEmpty() || nativeLibDir == null || !nativeLibDir.isDirectory()) {
                return;
            }
            Class<?> viewClass = findOverlayViewClass(apks, activity, nativeLibDir);
            if (viewClass == null) {
                return;
            }
            loadExtraLibs(nativeLibDir);
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

    private static List<File> collectApks(MinecraftActivity activity) {
        List<File> apks = new ArrayList<>();
        File root = activity.getFilesDir().getParentFile();
        scanApks(root, apks, 0);
        return apks;
    }

    private static void scanApks(File dir, List<File> out, int depth) {
        if (dir == null || depth > 4 || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if (child.isFile() && (name.equals("base.apk.levi")
                    || (name.endsWith(".apk.levi") && !name.equals("base.apk.levi")))) {
                out.add(child);
            } else if (child.isDirectory() && !name.equals("splits")) {
                scanApks(child, out, depth + 1);
            } else if (child.isDirectory()) {
                File[] splits = child.listFiles();
                if (splits != null) for (File split : splits) {
                    if (split.isFile() && split.getName().endsWith(".apk.levi")) out.add(split);
                }
            }
        }
    }

    /** Finds a GLES3JNIView-style overlay class in the APK's dex. */
    private static Class<?> findOverlayViewClass(List<File> apks, Context context, File nativeLibDir) throws Exception {
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
