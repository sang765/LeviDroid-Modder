package org.levimc.launcher.util;

import android.os.ParcelFileDescriptor;

interface IUpdateInstallerService {
    int install(in ParcelFileDescriptor apk, long size);
    String getOutput();
    void destroy();
}
