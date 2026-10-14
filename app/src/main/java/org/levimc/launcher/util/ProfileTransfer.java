package org.levimc.launcher.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ProfileTransfer {

    public static class Result {
        public int copied;
        public int skipped;
    }

    public static Result transferDir(File src, File dst, boolean move) {
        Result result = new Result();
        transferInto(src, dst, move, result);
        return result;
    }

    private static void transferInto(File src, File dst, boolean move, Result result) {
        File[] children = src.listFiles();
        if (children == null) return;
        if (!dst.exists() && !dst.mkdirs()) return;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                transferInto(child, target, move, result);
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
