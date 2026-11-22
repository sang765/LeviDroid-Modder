package org.levimc.launcher.core.explorer;

import java.io.File;

public final class ExplorerEntry {
    public final File file;
    public final boolean directory;
    public final long size;
    public final long modified;

    public ExplorerEntry(File file, boolean directory, long size, long modified) {
        this.file = file;
        this.directory = directory;
        this.size = size;
        this.modified = modified;
    }
}
