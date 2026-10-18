package org.levimc.launcher.util;

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Runs inside the Shizuku user service process with shell (or root) privileges.
 * Referenced by class name through {@link rikka.shizuku.Shizuku.UserServiceArgs},
 * so it must survive R8 renaming.
 */
@Keep
public class UpdateInstallerService extends IUpdateInstallerService.Stub {

    private String output = "";

    @Override
    public int install(ParcelFileDescriptor apk, long size) {
        Process process = null;
        try {
            // This process runs as shell/root, which cannot read the app-private
            // file the update was downloaded to, so the APK is streamed straight
            // into pm's stdin the same way `adb install` does.
            process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "pm install -r -S " + size + " 2>&1"});
            try (InputStream in = new FileInputStream(apk.getFileDescriptor());
                 OutputStream out = process.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            } catch (IOException ignored) {
                // pm can exit before the whole APK lands; its own message says
                // more than a broken-pipe exception would.
            }
            output = readAll(process);
            return process.waitFor();
        } catch (Exception e) {
            output = String.valueOf(e.getMessage());
            return -1;
        } finally {
            if (process != null) process.destroy();
        }
    }

    @Override
    public String getOutput() {
        return output;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        // The Shizuku server invokes destroy with fixed codes outside the
        // normal AIDL transaction range.
        if (code == 16777114 || code == 16777115) {
            destroy();
            return true;
        }
        return super.onTransact(code, data, reply, flags);
    }

    public static String readAll(Process process) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
