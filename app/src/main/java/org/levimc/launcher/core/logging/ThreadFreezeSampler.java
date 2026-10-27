package org.levimc.launcher.core.logging;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Samples /proc/self/task once per second while the game runs and reports,
 * through the app-level log ring, when a consistently-active thread (a game
 * loop thread) stops progressing. Uses schedstat (nanosecond CPU time) and
 * also tracks process-wide major page faults to expose swap storms. The game
 * runs in this process, so no injection is needed to watch its threads.
 */
public final class ThreadFreezeSampler {

    private static final String TAG = "FreezeWatch";
    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final long ACTIVE_DELTA_NS = 2_000_000L;
    private static final int STALL_SAMPLES_TO_REPORT = 3;
    private static final int HISTORY_SAMPLES = 12;
    private static final int TRACKED_MIN_ACTIVE_SAMPLES = 6;

    private static volatile Thread samplerThread;
    private static volatile boolean running;

    private ThreadFreezeSampler() {}

    public static synchronized void start() {
        if (samplerThread != null || running) return;
        running = true;
        samplerThread = new Thread(ThreadFreezeSampler::run, TAG);
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    public static synchronized void stop() {
        running = false;
        samplerThread = null;
    }

    private static void run() {
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, Long> lastCpu = new HashMap<>();
        Map<Integer, int[]> activity = new HashMap<>();
        Map<Integer, Integer> quietSamples = new HashMap<>();
        long prevMajorFaults = readMajorFaults();
        int tick = 0;
        while (running) {
            long startedAt = SystemClock.uptimeMillis();
            File[] taskDirs = new File("/proc/self/task").listFiles();
            if (taskDirs != null) {
                tick++;
                long majorFaults = readMajorFaults();
                long majorDelta = majorFaults - prevMajorFaults;
                prevMajorFaults = majorFaults;
                for (File taskDir : taskDirs) {
                    int tid = parsePositiveInt(taskDir.getName());
                    if (tid < 0) continue;
                    long nanos = readSchedstatNanos(taskDir);
                    if (nanos < 0) continue;
                    Long previous = lastCpu.put(tid, nanos);
                    if (previous == null) continue;
                    long delta = nanos - previous;
                    boolean active = delta >= ACTIVE_DELTA_NS;
                    String name = names.computeIfAbsent(tid, k -> readComm(taskDir, k));
                    int[] history = activity.computeIfAbsent(tid, k -> new int[HISTORY_SAMPLES]);
                    int activeCount = 0;
                    for (int value : history) activeCount += value;
                    boolean tracked = activeCount >= TRACKED_MIN_ACTIVE_SAMPLES;
                    int quiet = quietSamples.getOrDefault(tid, 0);
                    if (active) {
                        if (tracked && quiet >= STALL_SAMPLES_TO_REPORT) {
                            Log.i(TAG, name + " tid=" + tid + " resumed after "
                                    + quiet + "s stall");
                        }
                        quietSamples.put(tid, 0);
                        history[tick % HISTORY_SAMPLES] = 1;
                    } else {
                        quiet++;
                        quietSamples.put(tid, quiet);
                        history[tick % HISTORY_SAMPLES] = 0;
                        if (tracked && quiet == STALL_SAMPLES_TO_REPORT) {
                            String wchan = readSmallFile(new File(taskDir, "wchan"));
                            String state = readState(taskDir);
                            Log.i(TAG, name + " tid=" + tid + " stalled " + quiet
                                    + "s (wchan=" + (wchan == null ? "?" : wchan)
                                    + " state=" + (state == null ? "?" : state)
                                    + " majflt+" + majorDelta + ")");
                        } else if (tracked && quiet > STALL_SAMPLES_TO_REPORT
                                && (quiet - STALL_SAMPLES_TO_REPORT) % 10 == 0) {
                            String wchan = readSmallFile(new File(taskDir, "wchan"));
                            Log.i(TAG, name + " tid=" + tid + " still stalled " + quiet
                                    + "s (wchan=" + (wchan == null ? "?" : wchan)
                                    + " majflt+" + majorDelta + ")");
                        }
                    }
                }
            }
            long elapsed = SystemClock.uptimeMillis() - startedAt;
            long sleepMs = Math.max(0, SAMPLE_INTERVAL_MS - elapsed);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static long readSchedstatNanos(File taskDir) {
        String schedstat = readSmallFile(new File(taskDir, "schedstat"));
        if (schedstat != null && !schedstat.isEmpty()) {
            String[] fields = schedstat.split(" ");
            return parsePositiveLong(fields[0]);
        }
        String stat = readSmallFile(new File(taskDir, "stat"));
        if (stat == null) return -1;
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return -1;
        String[] fields = stat.substring(close + 2).split(" ");
        if (fields.length < 13) return -1;
        return (parsePositiveLong(fields[11]) + parsePositiveLong(fields[12])) * 10_000_000L;
    }

    private static String readState(File taskDir) {
        String stat = readSmallFile(new File(taskDir, "stat"));
        if (stat == null) return null;
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return null;
        return stat.substring(close + 2, close + 3);
    }

    private static long readMajorFaults() {
        String stat = readSmallFile(new File("/proc/self/stat"));
        if (stat == null) return 0;
        int close = stat.lastIndexOf(')');
        if (close < 0) return 0;
        String[] fields = stat.substring(close + 2).split(" ");
        if (fields.length < 10) return 0;
        return parsePositiveLong(fields[9]);
    }

    private static String readComm(File taskDir, int tid) {
        String comm = readSmallFile(new File(taskDir, "comm"));
        return comm == null || comm.isEmpty() ? ("tid" + tid) : comm;
    }

    private static String readSmallFile(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[512];
            int read = in.read(buffer);
            if (read <= 0) return null;
            return new String(buffer, 0, read).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static int parsePositiveInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long parsePositiveLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
