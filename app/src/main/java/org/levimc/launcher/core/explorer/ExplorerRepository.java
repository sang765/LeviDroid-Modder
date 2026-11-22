package org.levimc.launcher.core.explorer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ExplorerRepository {
    public static final long MAX_TEXT_BYTES = 2L * 1024 * 1024;
    private static final String[] HIDDEN_NAMES = {"xal"};
    private static final String[] TEXT_EXTENSIONS = {
            "txt", "json", "cfg", "conf", "properties", "toml", "ini", "yaml", "yml",
            "xml", "lang", "log", "csv", "md", "lua", "js", "java", "kt", "sh", "bat", "ps1", "nbt"
    };

    private ExplorerRepository() {
    }

    public static File child(File root, File current, String name) throws IOException {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\")
                || ".".equals(name) || "..".equals(name)) {
            throw new IOException("Invalid name");
        }
        File target = new File(current, name);
        if (!isInside(root, target)) throw new IOException("Path outside instance root");
        return target;
    }

    public static boolean isInside(File root, File target) throws IOException {
        String rootPath = root.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        return targetPath.equals(rootPath) || targetPath.startsWith(rootPath + File.separator);
    }

    public static List<ExplorerEntry> list(File root, File dir) throws IOException {
        if (!isInside(root, dir)) throw new IOException("Path outside instance root");
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("Cannot list directory");
        List<ExplorerEntry> entries = new ArrayList<>();
        for (File file : children) {
            if (isHiddenName(file.getName())) continue;
            boolean directory = file.isDirectory();
            long size = directory ? 0L : file.length();
            entries.add(new ExplorerEntry(file, directory, size, file.lastModified()));
        }
        entries.sort(Comparator.comparing((ExplorerEntry e) -> !e.directory)
                .thenComparing(e -> e.file.getName().toLowerCase(Locale.ROOT)));
        return entries;
    }

    private static boolean isHiddenName(String name) {
        for (String hidden : HIDDEN_NAMES) if (hidden.equals(name)) return true;
        return false;
    }

    public static boolean isTextFile(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.asList(TEXT_EXTENSIONS).contains(ext);
    }

    public static String readText(File file) throws IOException {
        long length = file.length();
        if (length > MAX_TEXT_BYTES) throw new IOException("File too large to edit");
        byte[] bytes = new byte[(int) length];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            return new String(bytes, 0, offset, StandardCharsets.UTF_8);
        }
    }

    public static void writeText(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent == null) throw new IOException("Invalid path");
        File temp = new File(parent, file.getName() + ".explorer-tmp");
        try (OutputStream out = new FileOutputStream(temp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void createDirectory(File dir) throws IOException {
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create directory");
    }

    public static void rename(File from, File to) throws IOException {
        if (to.exists()) throw new IOException("Destination already exists");
        if (!from.renameTo(to)) throw new IOException("Cannot rename");
    }

    public static void delete(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) delete(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Cannot delete " + file.getName());
    }

    public static void copy(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            createDirectory(destination);
            File[] children = source.listFiles();
            if (children != null) for (File child : children) {
                if (isHiddenName(child.getName())) continue;
                copy(child, new File(destination, child.getName()));
            }
            return;
        }
        File parent = destination.getParentFile();
        if (parent == null) throw new IOException("Invalid path");
        createDirectory(parent);
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    public static void move(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            copy(source, destination);
            delete(source);
            return;
        }
        File parent = destination.getParentFile();
        if (parent == null) throw new IOException("Invalid path");
        createDirectory(parent);
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static File uniqueDestination(File dir, String name) throws IOException {
        File target = child(dir, dir, name);
        if (!target.exists()) return target;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; i < 1000; i++) {
            target = child(dir, dir, base + " (" + i + ")" + ext);
            if (!target.exists()) return target;
        }
        throw new IOException("Too many copies");
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String formatDate(long millis) {
        if (millis <= 0) return "";
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis));
    }
}
