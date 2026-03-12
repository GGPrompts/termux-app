package com.termux.app.codefactory;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Observability layer for the codefactory backend (Rust/Axum).
 *
 * Responsibilities:
 * 1. Log routing: backend output to logcat (tag "CodefactoryBackend") and a rotated log file.
 * 2. Crash-loop detection: tracks restart count + timestamps, stops retrying after 3 crashes in 60s.
 * 3. Stale cleanup: checks for stale PID files and orphaned processes on app start.
 * 4. Log tail: provides last N log lines for error UI / debug panel.
 *
 * Thread safety: All public methods are synchronized or use volatile/atomic fields.
 */
public class BackendObserver {

    private static final String TAG = "CodefactoryBackend";
    private static final String LOG_TAG = "BackendObserver";

    /** Maximum log file size before rotation (1 MB). */
    private static final long MAX_LOG_SIZE_BYTES = 1024 * 1024;

    /** Maximum number of crashes within the crash window before giving up. */
    private static final int MAX_CRASH_COUNT = 3;

    /** Time window for crash-loop detection in milliseconds (60 seconds). */
    private static final long CRASH_WINDOW_MS = 60_000;

    /** Number of log lines to retain in memory for the debug panel / error dialog. */
    public static final int LOG_RING_BUFFER_SIZE = 200;

    /** Number of log lines shown in the error dialog. */
    public static final int ERROR_DIALOG_LINE_COUNT = 20;

    // -- Log file paths --
    private final File mLogFile;
    private final File mLogFileOld;
    private final File mPidFile;
    private final File mSocketFile;
    private final File mCodefactoryDir;

    // -- Crash-loop tracking --
    private final LinkedList<Long> mCrashTimestamps = new LinkedList<>();
    private volatile boolean mCrashLoopDetected = false;

    // -- In-memory log ring buffer --
    private final LinkedList<String> mLogRing = new LinkedList<>();

    // -- Log file writer (lazy init, closed on shutdown) --
    private PrintWriter mLogWriter;
    private long mCurrentLogSize;

    /**
     * Create a BackendObserver rooted at the given home directory.
     * The log and PID files will be under {@code $HOME/.codefactory/}.
     *
     * @param homePath The Termux home directory path
     */
    public BackendObserver(String homePath) {
        mCodefactoryDir = new File(homePath, ".codefactory");
        mLogFile = new File(mCodefactoryDir, "backend.log");
        mLogFileOld = new File(mCodefactoryDir, "backend.log.1");
        mPidFile = new File(mCodefactoryDir, "backend.pid");
        mSocketFile = new File(mCodefactoryDir, "backend.sock");
    }

    // -----------------------------------------------------------------------
    // 1. Log routing
    // -----------------------------------------------------------------------

    /**
     * Log a debug-level message from the backend. Routes to both logcat and the log file.
     */
    public void logDebug(String message) {
        Log.d(TAG, message);
        writeToLogFile("D", message);
        addToRingBuffer("D " + message);
    }

    /**
     * Log an info-level message from the backend.
     */
    public void logInfo(String message) {
        Log.i(TAG, message);
        writeToLogFile("I", message);
        addToRingBuffer("I " + message);
    }

    /**
     * Log a warning-level message from the backend.
     */
    public void logWarn(String message) {
        Log.w(TAG, message);
        writeToLogFile("W", message);
        addToRingBuffer("W " + message);
    }

    /**
     * Log an error-level message from the backend.
     */
    public void logError(String message) {
        Log.e(TAG, message);
        writeToLogFile("E", message);
        addToRingBuffer("E " + message);
    }

    /**
     * Log an error-level message with an exception.
     */
    public void logError(String message, Throwable t) {
        Log.e(TAG, message, t);
        writeToLogFile("E", message + ": " + t.getMessage());
        addToRingBuffer("E " + message + ": " + t.getMessage());
    }

    /**
     * Write a line to the log file, handling rotation when it exceeds MAX_LOG_SIZE_BYTES.
     */
    private synchronized void writeToLogFile(String level, String message) {
        try {
            ensureLogWriter();
            String timestamp = java.text.DateFormat.getDateTimeInstance().format(new java.util.Date());
            String line = timestamp + " " + level + " " + message;
            mLogWriter.println(line);
            mLogWriter.flush();
            mCurrentLogSize += line.length() + 1; // approximate

            if (mCurrentLogSize >= MAX_LOG_SIZE_BYTES) {
                rotateLogFile();
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to write to log file", e);
        }
    }

    /**
     * Ensure the log directory and writer exist.
     */
    private void ensureLogWriter() throws IOException {
        if (mLogWriter != null) return;

        if (!mCodefactoryDir.exists()) {
            mCodefactoryDir.mkdirs();
        }

        mCurrentLogSize = mLogFile.exists() ? mLogFile.length() : 0;
        mLogWriter = new PrintWriter(new FileWriter(mLogFile, true), false);
    }

    /**
     * Rotate the log file: current -> .log.1, start fresh.
     */
    private synchronized void rotateLogFile() {
        try {
            if (mLogWriter != null) {
                mLogWriter.close();
                mLogWriter = null;
            }

            if (mLogFileOld.exists()) {
                mLogFileOld.delete();
            }

            if (mLogFile.exists()) {
                mLogFile.renameTo(mLogFileOld);
            }

            mCurrentLogSize = 0;
            // Writer will be re-created on next write via ensureLogWriter()
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to rotate log file", e);
        }
    }

    /**
     * Add a line to the in-memory ring buffer.
     */
    private synchronized void addToRingBuffer(String line) {
        mLogRing.add(line);
        while (mLogRing.size() > LOG_RING_BUFFER_SIZE) {
            mLogRing.remove(0);
        }
    }

    /**
     * Get the last N lines from the in-memory log ring buffer.
     *
     * @param count Maximum number of lines to return
     * @return List of log lines, most recent last
     */
    public synchronized List<String> getRecentLogLines(int count) {
        int size = mLogRing.size();
        int start = Math.max(0, size - count);
        return new ArrayList<>(mLogRing.subList(start, size));
    }

    /**
     * Read the last N lines from the log file on disk.
     * Falls back to the ring buffer if the file is unreadable.
     *
     * @param count Maximum number of lines to return
     * @return List of log lines, most recent last
     */
    public List<String> readLastLogLines(int count) {
        // Try ring buffer first (most recent, always available)
        List<String> ringLines = getRecentLogLines(count);
        if (!ringLines.isEmpty()) {
            return ringLines;
        }

        // Fall back to reading from file
        List<String> lines = new ArrayList<>();
        try {
            if (mLogFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(mLogFile))) {
                    LinkedList<String> tail = new LinkedList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        tail.add(line);
                        if (tail.size() > count) {
                            tail.remove(0);
                        }
                    }
                    lines.addAll(tail);
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Failed to read log file", e);
        }
        return lines;
    }

    // -----------------------------------------------------------------------
    // 2. Crash-loop detection
    // -----------------------------------------------------------------------

    /**
     * Record a backend crash. Returns true if a crash loop has been detected
     * (3+ crashes within 60 seconds), meaning retries should stop.
     *
     * @return true if crash loop detected and retries should stop
     */
    public synchronized boolean recordCrash() {
        long now = System.currentTimeMillis();
        mCrashTimestamps.add(now);

        // Evict timestamps outside the crash window
        while (!mCrashTimestamps.isEmpty()
                && (now - mCrashTimestamps.get(0)) > CRASH_WINDOW_MS) {
            mCrashTimestamps.remove(0);
        }

        if (mCrashTimestamps.size() >= MAX_CRASH_COUNT) {
            mCrashLoopDetected = true;
            logError("Crash loop detected: " + mCrashTimestamps.size()
                    + " crashes in " + CRASH_WINDOW_MS + "ms, stopping retries");
        }

        return mCrashLoopDetected;
    }

    /**
     * Returns true if a crash loop has been detected.
     */
    public boolean isCrashLoopDetected() {
        return mCrashLoopDetected;
    }

    /**
     * Reset crash-loop detection state. Called when user manually retries.
     */
    public synchronized void resetCrashLoop() {
        mCrashTimestamps.clear();
        mCrashLoopDetected = false;
        logInfo("Crash loop state reset by user");
    }

    /**
     * Returns the number of crashes recorded within the current window.
     */
    public synchronized int getCrashCount() {
        long now = System.currentTimeMillis();
        // Evict old timestamps before counting
        while (!mCrashTimestamps.isEmpty()
                && (now - mCrashTimestamps.get(0)) > CRASH_WINDOW_MS) {
            mCrashTimestamps.remove(0);
        }
        return mCrashTimestamps.size();
    }

    // -----------------------------------------------------------------------
    // 3. Stale cleanup
    // -----------------------------------------------------------------------

    /**
     * Clean up stale state from a previous run. Call on app start before
     * launching the backend.
     *
     * Checks for:
     * - Stale PID file (kills orphaned process if PID exists but doesn't match)
     * - Stale Unix socket file
     */
    public void cleanupStaleState() {
        logInfo("Checking for stale backend state");

        // Clean up stale PID file
        cleanupStalePidFile();

        // Clean up stale socket file
        cleanupStaleSocket();
    }

    /**
     * Check for a stale PID file. If the PID exists but the process is not
     * the backend (or is dead), remove the file and kill the orphan.
     */
    private void cleanupStalePidFile() {
        if (!mPidFile.exists()) {
            logDebug("No stale PID file found");
            return;
        }

        try {
            String pidStr = readFileContents(mPidFile).trim();
            if (pidStr.isEmpty()) {
                logWarn("PID file exists but is empty, removing");
                mPidFile.delete();
                return;
            }

            int pid;
            try {
                pid = Integer.parseInt(pidStr);
            } catch (NumberFormatException e) {
                logWarn("PID file contains invalid PID: " + pidStr + ", removing");
                mPidFile.delete();
                return;
            }

            // Check if the process is still alive
            File procDir = new File("/proc/" + pid);
            if (procDir.exists()) {
                // Process exists -- check if it is actually the codefactory backend
                File cmdlineFile = new File(procDir, "cmdline");
                String cmdline = "";
                if (cmdlineFile.exists() && cmdlineFile.canRead()) {
                    cmdline = readFileContents(cmdlineFile);
                }

                // /proc/pid/cmdline uses NUL separators; normalize for matching
                String cmdlineReadable = cmdline.replace('\0', ' ').trim();

                if (cmdlineReadable.contains("codefactory")) {
                    // Confirmed: this is our backend process -- kill it
                    logWarn("Killing orphaned backend process with PID " + pid
                            + " (cmdline: " + cmdlineReadable + ")");
                    try {
                        android.os.Process.killProcess(pid);
                    } catch (Exception e) {
                        logError("Failed to kill orphaned process " + pid, e);
                    }
                } else {
                    // PID was recycled to a different process -- do NOT kill it
                    logWarn("Stale PID " + pid + " was recycled to another process"
                            + " (cmdline: " + cmdlineReadable + "), removing PID file without killing");
                }
            } else {
                logDebug("Stale PID " + pid + " is no longer running");
            }

            mPidFile.delete();
            logInfo("Stale PID file cleaned up");

        } catch (IOException e) {
            logError("Failed to read PID file", e);
            mPidFile.delete();
        }
    }

    /**
     * Remove stale Unix socket file if it exists.
     */
    private void cleanupStaleSocket() {
        if (mSocketFile.exists()) {
            logInfo("Removing stale Unix socket: " + mSocketFile.getAbsolutePath());
            mSocketFile.delete();
        }

        // Also check for any other socket files in the directory
        if (mCodefactoryDir.exists()) {
            File[] files = mCodefactoryDir.listFiles((dir, name) -> name.endsWith(".sock"));
            if (files != null) {
                for (File f : files) {
                    logInfo("Removing stale socket: " + f.getName());
                    f.delete();
                }
            }
        }
    }

    /**
     * Read the full contents of a small file as a String.
     */
    private String readFileContents(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            char[] buf = new char[1024];
            int read;
            while ((read = reader.read(buf)) != -1) {
                sb.append(buf, 0, read);
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Close the log file writer. Call when the service is being destroyed.
     */
    public synchronized void shutdown() {
        logInfo("BackendObserver shutting down");
        if (mLogWriter != null) {
            mLogWriter.close();
            mLogWriter = null;
        }
    }
}
