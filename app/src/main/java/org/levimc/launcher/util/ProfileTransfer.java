package org.levimc.launcher.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ProfileTransfer {

    public interface ProgressListener {
        void onProgress(int done, int total);
    }

    public static class Result {
        public int copied;
        public int skipped;
    }

    public static Result transferDir(File src, File dst, boolean move) {
        return transferDir(src, dst, move, null);
    }

    public static Result transferDir(File src, File dst, boolean move, ProgressListener listener) {
        Result result = new Result();
        int total = countFiles(src);
        int[] state = {0, total};
        if (listener != null) listener.onProgress(0, total);
        transferInto(src, dst, move, result, listener, state);
        return result;
    }

    public static int countFiles(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) {
            count += child.isDirectory() ? countFiles(child) : 1;
        }
        return count;
    }

    private static void transferInto(File src, File dst, boolean move, Result result,
                                     ProgressListener listener, int[] state) {
        File[] children = src.listFiles();
        if (children == null) return;
        if (!dst.exists() && !dst.mkdirs()) return;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                transferInto(child, target, move, result, listener, state);
            } else if (!target.exists()) {
                if (copyFile(child, target)) {
                    result.copied++;
                    if (move) child.delete();
                } else {
                    target.delete();
                }
            } else {
                result.skipped++;
            }
            state[0]++;
            if (listener != null) listener.onProgress(state[0], state[1]);
        }
        if (move) src.delete();
    }

    private static boolean copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
