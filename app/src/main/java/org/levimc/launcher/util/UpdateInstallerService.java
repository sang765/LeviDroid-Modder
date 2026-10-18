package org.levimc.launcher.util;

import android.os.Parcel;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Runs inside the Shizuku user service process with shell (or root) privileges.
 * Referenced by class name through {@link rikka.shizuku.Shizuku.UserServiceArgs},
 * so it must survive R8 renaming.
 */
@Keep
public class UpdateInstallerService extends IUpdateInstallerService.Stub {

    private String output = "";

    @Override
    public int install(String apkPath) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "sh", "-c", "pm install -r '" + apkPath + "' 2>&1"});
            output = readAll(process);
            return process.waitFor();
        } catch (Exception e) {
            output = String.valueOf(e.getMessage());
            return -1;
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
