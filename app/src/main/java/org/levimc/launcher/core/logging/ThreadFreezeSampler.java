package org.levimc.launcher.core.logging;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Samples /proc/self/task once per second while the game runs and reports,
 * through the app-level log ring, which previously-active threads stop
 * progressing and what they are waiting on (wchan/state). The game process
 * is this process, so no injection is needed to watch its threads.
 */
public final class ThreadFreezeSampler {

    private static final String TAG = "FreezeWatch";
    private static final long SAMPLE_INTERVAL_MS = 1000;
    private static final int STALL_SAMPLES_TO_REPORT = 3;
    private static final int TRACK_HISTORY_SAMPLES = 12;

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
        int tick = 0;
        while (running) {
            long startedAt = SystemClock.uptimeMillis();
            File[] taskDirs = new File("/proc/self/task").listFiles();
            if (taskDirs != null) {
                tick++;
                for (File taskDir : taskDirs) {
                    int tid = parsePositiveInt(taskDir.getName());
                    if (tid < 0) continue;
                    ProcSample sample = readSample(taskDir, tid);
                    if (sample == null) continue;
                    names.computeIfAbsent(tid, k -> readComm(taskDir, k));
                    long cpu = sample.cpuTime();
                    Long previous = lastCpu.put(tid, cpu);
                    long delta = previous == null ? 0 : cpu - previous;
                    int[] history = activity.computeIfAbsent(tid, k -> new int[TRACK_HISTORY_SAMPLES]);
                    int activeCount = 0;
                    for (int value : history) activeCount += value;
                    boolean tracked = activeCount >= 2;
                    int quiet = quietSamples.getOrDefault(tid, 0);
                    if (delta > 0) {
                        if (tracked && quiet >= STALL_SAMPLES_TO_REPORT) {
                            Log.i(TAG, names.get(tid) + " tid=" + tid + " resumed after "
                                    + (quiet) + "s stall (wchan=" + sample.wchan + ")");
                        }
                        quietSamples.put(tid, 0);
                        history[tick % TRACK_HISTORY_SAMPLES] = 1;
                    } else {
                        quiet++;
                        quietSamples.put(tid, quiet);
                        history[tick % TRACK_HISTORY_SAMPLES] = 0;
                        if (tracked && quiet == STALL_SAMPLES_TO_REPORT) {
                            Log.i(TAG, names.get(tid) + " tid=" + tid + " stalled "
                                    + quiet + "s (wchan=" + sample.wchan
                                    + " state=" + sample.state + ")");
                        } else if (tracked && quiet > STALL_SAMPLES_TO_REPORT
                                && (quiet - STALL_SAMPLES_TO_REPORT) % 10 == 0) {
                            Log.i(TAG, names.get(tid) + " tid=" + tid + " still stalled "
                                    + quiet + "s (wchan=" + sample.wchan
                                    + " state=" + sample.state + ")");
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

    private static ProcSample readSample(File taskDir, int tid) {
        String stat = readSmallFile(new File(taskDir, "stat"));
        if (stat == null) return null;
        int close = stat.lastIndexOf(')');
        if (close < 0 || close + 2 >= stat.length()) return null;
        String[] fields = stat.substring(close + 2).split(" ");
        if (fields.length < 13) return null;
        long utime = parsePositiveLong(fields[11]);
        long stime = parsePositiveLong(fields[12]);
        String state = fields[0];
        String wchan = readSmallFile(new File(taskDir, "wchan"));
        return new ProcSample(state, wchan == null ? "?" : wchan, utime + stime);
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

    private record ProcSample(String state, String wchan, long cpuTime) {}
}
