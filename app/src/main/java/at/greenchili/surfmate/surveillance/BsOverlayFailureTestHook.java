package at.greenchili.surfmate.surveillance;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.nio.charset.StandardCharsets;

import at.greenchili.surfmate.BuildConfig;
import at.greenchili.surfmate.logging.DaemonLogger;
import at.greenchili.surfmate.monitor.GearMonitor;

/**
 * One-shot fault injection for the stationary physical cluster fail-safe test.
 *
 * <p>This is deliberately not an API, setting, intent or persisted config value. It is compiled
 * dormant unless an explicit build property enables it, and even then accepts only a fresh,
 * shell-owned marker in {@code /data/local/tmp}. The marker is consumed before an exception is
 * raised. A stale marker therefore cannot affect a later drive or a daemon restart.</p>
 */
final class BsOverlayFailureTestHook {
    static final String MARKER_PATH =
            "/data/local/tmp/panoptes-bs-overlay-fail-once-v1";
    static final String MARKER_CONTENT = "panoptes-bs-overlay-fail-once-v1\n";
    static final long MAX_AGE_MS = 15_000L;
    static final int SHELL_UID = 2000;
    static final int PRIVATE_MODE = 0600;

    private static final DaemonLogger logger =
            DaemonLogger.getInstance("BsOverlayFailureTestHook");

    private BsOverlayFailureTestHook() {}

    static final class Gates {
        final boolean enabled;
        final boolean legacyPlatform;
        final boolean castAppHeld;
        final boolean laneActive;
        final boolean projectionOpen;
        final int freshGear;

        Gates(boolean enabled, boolean legacyPlatform, boolean castAppHeld,
              boolean laneActive, boolean projectionOpen, int freshGear) {
            this.enabled = enabled;
            this.legacyPlatform = legacyPlatform;
            this.castAppHeld = castAppHeld;
            this.laneActive = laneActive;
            this.projectionOpen = projectionOpen;
            this.freshGear = freshGear;
        }
    }

    static final class Marker {
        final boolean exists;
        final boolean regular;
        final boolean symbolicLink;
        final int uid;
        final int mode;
        final long modifiedMs;
        final String content;

        Marker(boolean exists, boolean regular, boolean symbolicLink, int uid, int mode,
               long modifiedMs, String content) {
            this.exists = exists;
            this.regular = regular;
            this.symbolicLink = symbolicLink;
            this.uid = uid;
            this.mode = mode;
            this.modifiedMs = modifiedMs;
            this.content = content;
        }

        static Marker absent() {
            return new Marker(false, false, false, -1, 0, 0L, "");
        }
    }

    interface MarkerAccess {
        Marker read();
        boolean delete();
    }

    /**
     * Raises the one injected placement error only after every runtime and marker gate passes.
     * The caller deliberately invokes this inside the normal placement try/catch, so the injected
     * failure exercises the exact same {@code bs-overlay-failed} recovery as a real transaction
     * failure.
     */
    static void failOnceIfArmed(boolean legacyPlatform, boolean castAppHeld,
                                boolean laneActive, boolean projectionOpen, int freshGear) {
        Gates gates = new Gates(BuildConfig.PHYSICAL_SAFETY_TEST_HOOKS,
                legacyPlatform, castAppHeld, laneActive, projectionOpen, freshGear);
        if (consume(gates, new ShellMarkerAccess(), System.currentTimeMillis())) {
            logger.warn("injecting one stationary cluster overlay placement failure");
            throw new IllegalStateException("physical-test: injected cluster overlay failure");
        }
    }

    /** Pure decision/consume seam used by JVM tests. */
    static boolean consume(Gates gates, MarkerAccess access, long nowMs) {
        if (gates == null || access == null || !gates.enabled) return false;

        Marker marker = access.read();
        if (marker == null || !marker.exists) return false;

        long ageMs = nowMs - marker.modifiedMs;
        boolean validMarker = marker.regular
                && !marker.symbolicLink
                && marker.uid == SHELL_UID
                && (marker.mode & 0777) == PRIVATE_MODE
                && ageMs >= 0L
                && ageMs <= MAX_AGE_MS
                && MARKER_CONTENT.equals(marker.content);
        boolean validRuntime = gates.legacyPlatform
                && gates.castAppHeld
                && gates.laneActive
                && gates.projectionOpen
                && gates.freshGear == GearMonitor.GEAR_P;

        // Every marker observed by an enabled test build is one-shot, including malformed,
        // stale and wrongly timed ones. Most importantly, deletion MUST succeed before the
        // injected failure is allowed to happen.
        boolean deleted = access.delete();
        return validMarker && validRuntime && deleted;
    }

    /** Reads one marker without following symlinks and verifies the opened inode. */
    private static final class ShellMarkerAccess implements MarkerAccess {
        @Override
        public Marker read() {
            StructStat linkStat;
            try {
                linkStat = Os.lstat(MARKER_PATH);
            } catch (ErrnoException e) {
                return Marker.absent();
            }

            boolean symbolicLink = OsConstants.S_ISLNK(linkStat.st_mode);
            boolean regular = OsConstants.S_ISREG(linkStat.st_mode);
            int mode = linkStat.st_mode & 0777;
            long modifiedMs = linkStat.st_mtime * 1000L;
            if (symbolicLink || !regular) {
                return new Marker(true, regular, symbolicLink, linkStat.st_uid, mode,
                        modifiedMs, "");
            }

            byte[] expected = MARKER_CONTENT.getBytes(StandardCharsets.UTF_8);
            if (linkStat.st_size != expected.length) {
                return new Marker(true, true, false, linkStat.st_uid, mode,
                        modifiedMs, "");
            }

            FileDescriptor fd = null;
            try {
                fd = Os.open(MARKER_PATH,
                        OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW,
                        0);
                StructStat opened = Os.fstat(fd);
                if (!OsConstants.S_ISREG(opened.st_mode)
                        || opened.st_dev != linkStat.st_dev
                        || opened.st_ino != linkStat.st_ino
                        || opened.st_size != expected.length) {
                    return new Marker(true, false, false, opened.st_uid,
                            opened.st_mode & 0777, opened.st_mtime * 1000L, "");
                }
                byte[] bytes = new byte[expected.length];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = Os.read(fd, bytes, offset, bytes.length - offset);
                    if (read <= 0) break;
                    offset += read;
                }
                String content = offset == bytes.length
                        ? new String(bytes, StandardCharsets.UTF_8) : "";
                return new Marker(true, true, false, opened.st_uid,
                        opened.st_mode & 0777, opened.st_mtime * 1000L, content);
            } catch (Throwable t) {
                logger.warn("physical test marker read failed: " + t.getMessage());
                return new Marker(true, false, false, linkStat.st_uid, mode,
                        modifiedMs, "");
            } finally {
                if (fd != null) {
                    try { Os.close(fd); } catch (Throwable ignored) {}
                }
            }
        }

        @Override
        public boolean delete() {
            try {
                boolean deleted = new File(MARKER_PATH).delete();
                if (!deleted) logger.warn("physical test marker delete returned false");
                return deleted;
            } catch (Throwable t) {
                logger.warn("physical test marker delete failed: " + t.getMessage());
                return false;
            }
        }
    }
}
