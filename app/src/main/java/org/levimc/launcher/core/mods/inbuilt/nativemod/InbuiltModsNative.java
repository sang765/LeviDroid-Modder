package org.levimc.launcher.core.mods.inbuilt.nativemod;

public class InbuiltModsNative {
    private static boolean libraryLoaded = false;
    private static volatile boolean preferNewSignatures = false;

    public static void setPreferNewSignatures(boolean prefer) {
        preferNewSignatures = prefer;
    }

    public static synchronized boolean loadLibrary() {
        if (libraryLoaded) return true;
        try {
            System.loadLibrary(preferNewSignatures ? "inbuiltmods2660" : "inbuiltmods");
            libraryLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            return false;
        }
    }

    public static boolean isLoaded() {
        return libraryLoaded;
    }
}
