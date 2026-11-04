package df.root;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Resolves both traditional and renamed manager daemon payloads without executing them. */
final class KsudResolver {
    // Bootstrap helpers also mention ksud and late-load; require daemon commands too.
    private static final byte[][] MARKERS = {
            "/data/adb/ksud".getBytes(StandardCharsets.US_ASCII),
            "late-load".getBytes(StandardCharsets.US_ASCII),
            "package-name".getBytes(StandardCharsets.US_ASCII),
            "post-fs-data".getBytes(StandardCharsets.US_ASCII),
            "boot-completed".getBytes(StandardCharsets.US_ASCII)
    };

    private KsudResolver() {}

    static File find(File nativeLibraryDir) {
        if (nativeLibraryDir == null) return null;

        File legacy = new File(nativeLibraryDir, "libksud.so");
        if (legacy.isFile()) return legacy;

        // SukiSU release builds randomize the daemon and JNI library names.
        // A largest-library heuristic would also match ordinary installed apps.
        File[] libraries = nativeLibraryDir.listFiles(file -> file.isFile()
                && file.getName().startsWith("lib") && file.getName().endsWith(".so"));
        if (libraries == null) return null;

        File daemon = null;
        for (File library : libraries) {
            if (!isDaemon(library)) continue;
            if (daemon != null) return null; // Do not guess between multiple payloads.
            daemon = library;
        }
        return daemon;
    }

    private static boolean isDaemon(File file) {
        try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            input.readFully(buffer, 0, 64);
            if (!isArm64Executable(buffer)) return false;

            boolean[] matched = new boolean[MARKERS.length];
            int remaining = MARKERS.length;
            int maxMarkerLength = 0;
            for (byte[] marker : MARKERS) maxMarkerLength = Math.max(maxMarkerLength, marker.length);
            int length = 64;
            while (true) {
                int read = input.read(buffer, length, buffer.length - length);
                if (read > 0) length += read;
                for (int i = 0; i < MARKERS.length; i++) {
                    if (!matched[i] && contains(buffer, length, MARKERS[i])) {
                        matched[i] = true;
                        remaining--;
                    }
                }
                if (remaining == 0) return true;
                if (read == -1) return false;

                // Preserve enough bytes for the longest marker to cross a read boundary.
                int overlap = Math.min(length, maxMarkerLength - 1);
                System.arraycopy(buffer, length - overlap, buffer, 0, overlap);
                length = overlap;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isArm64Executable(byte[] header) {
        if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F'
                || header[4] != 2 || header[5] != 1 // ELF64, little-endian
                || (header[16] != 2 && header[16] != 3) || header[17] != 0
                || (header[18] & 0xff) != 0xb7 || header[19] != 0) { // EM_AARCH64
            return false;
        }
        // Android PIE executables use ET_DYN too. JNI libraries have no entry point,
        // and may themselves contain daemon strings, so reject them here.
        for (int i = 24; i < 32; i++) {
            if (header[i] != 0) return true;
        }
        return false;
    }

    private static boolean contains(byte[] buffer, int length, byte[] marker) {
        for (int i = 0; i <= length - marker.length; i++) {
            int j = 0;
            while (j < marker.length && buffer[i + j] == marker[j]) j++;
            if (j == marker.length) return true;
        }
        return false;
    }
}
