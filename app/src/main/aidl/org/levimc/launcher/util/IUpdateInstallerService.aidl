package org.levimc.launcher.util;

interface IUpdateInstallerService {
    int install(String apkPath);
    String getOutput();
    void destroy();
}
