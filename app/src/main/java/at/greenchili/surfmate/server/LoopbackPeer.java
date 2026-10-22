package at.greenchili.surfmate.server;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Tells a real same-device caller apart from a tailnet peer relayed by tailscaled.
 *
 * tailscaled runs with {@code --tun userspace-networking}. A tailnet connection
 * to a port that no Serve rule claims is dialed by tailscaled itself to the same
 * port on 127.0.0.1, so it arrives with a loopback source and no proxy headers —
 * exactly what AuthMiddleware's Tier-2 safety net trusts. The client end of such
 * a connection is a socket owned by the tailscaled process, which /proc exposes
 * to the daemon (both run as uid 2000).
 *
 * Fails closed: when the client socket cannot be found, the caller is treated as
 * relayed and has to present a JWT like any remote client.
 */
final class LoopbackPeer {

    private static final String TAILSCALED_COMM = "tailscaled";
    private static final Path PROC = Paths.get("/proc");

    // Cached tailscaled pid; revalidated through /proc/<pid>/comm on every use.
    private static volatile int cachedTailscaledPid = -1;

    private LoopbackPeer() {}

    /**
     * @param clientPort source port of the accepted loopback connection
     * @param serverPort port the HTTP server accepted it on
     * @return true unless the client socket is positively owned by another process
     */
    static boolean isRelayedByTailscaled(int clientPort, int serverPort) {
        long inode = -1;
        for (String table : new String[] {"/proc/net/tcp", "/proc/net/tcp6"}) {
            inode = findClientInode(readOrEmpty(Paths.get(table)), clientPort, serverPort);
            if (inode > 0) break;
        }
        if (inode <= 0) {
            return true;
        }
        int pid = tailscaledPid();
        return pid > 0 && ownsSocket(PROC.resolve(Integer.toString(pid)).resolve("fd"), inode);
    }

    /**
     * Find the inode of the client end of a loopback connection in a
     * /proc/net/tcp{,6} table: local = loopback:clientPort, remote = loopback:serverPort.
     *
     * @return the inode, or -1 if there is no such row
     */
    static long findClientInode(String table, int clientPort, int serverPort) {
        String[] lines = table.split("\n");
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].trim().split("\\s+");
            if (f.length < 10) continue;
            if (isLoopbackEndpoint(f[1], clientPort) && isLoopbackEndpoint(f[2], serverPort)) {
                try {
                    long inode = Long.parseLong(f[9]);
                    if (inode > 0) return inode;
                } catch (NumberFormatException ignored) {
                    // malformed row
                }
            }
        }
        return -1;
    }

    /** "0100007F:1FF4", IPv4-mapped "...FFFF00000100007F:1FF4" or ::1 "...01000000:1FF4". */
    static boolean isLoopbackEndpoint(String endpoint, int port) {
        int colon = endpoint.indexOf(':');
        if (colon <= 0) return false;
        String addr = endpoint.substring(0, colon).toUpperCase(Locale.ROOT);
        int p;
        try {
            p = Integer.parseInt(endpoint.substring(colon + 1), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        if (p != port) return false;
        if (addr.length() == 8) {
            // Little-endian IPv4; any 127.0.0.0/8 address ends in "7F".
            return addr.endsWith("7F");
        }
        return addr.length() == 32
                && (addr.startsWith("0000000000000000FFFF0000") && addr.endsWith("7F")
                    || addr.equals("00000000000000000000000001000000"));
    }

    static boolean ownsSocket(Path fdDir, long inode) {
        String target = "socket:[" + inode + "]";
        File[] fds = fdDir.toFile().listFiles();
        if (fds == null) return false;
        for (File fd : fds) {
            try {
                if (target.equals(Files.readSymbolicLink(fd.toPath()).toString())) {
                    return true;
                }
            } catch (Exception ignored) {
                // fd closed while scanning
            }
        }
        return false;
    }

    private static int tailscaledPid() {
        int cached = cachedTailscaledPid;
        if (cached > 0 && isTailscaled(cached)) {
            return cached;
        }
        File[] entries = PROC.toFile().listFiles();
        if (entries != null) {
            for (File entry : entries) {
                String name = entry.getName();
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                try {
                    int pid = Integer.parseInt(name);
                    if (isTailscaled(pid)) {
                        cachedTailscaledPid = pid;
                        return pid;
                    }
                } catch (NumberFormatException ignored) {
                    // not a pid directory
                }
            }
        }
        cachedTailscaledPid = -1;
        return -1;
    }

    private static boolean isTailscaled(int pid) {
        return TAILSCALED_COMM.equals(
                readOrEmpty(PROC.resolve(Integer.toString(pid)).resolve("comm")).trim());
    }

    private static String readOrEmpty(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
