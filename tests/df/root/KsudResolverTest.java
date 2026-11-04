package df.root;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Host-side regression tests; no Android SDK or device is needed. */
public final class KsudResolverTest {
    private static final String[] MARKERS = {
            "/data/adb/ksud", "late-load", "package-name", "post-fs-data", "boot-completed"
    };
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length != 0 && args.length != 2) {
            throw new IllegalArgumentException("Usage: KsudResolverTest [manager.apk expected-daemon.so]");
        }
        Path root = Files.createTempDirectory("ksud-resolver-test-");
        try {
            expect(null, KsudResolver.find(null), "null directory");
            expect(null, KsudResolver.find(root.resolve("missing").toFile()), "missing directory");
            Path empty = Files.createDirectory(root.resolve("empty"));
            expect(null, KsudResolver.find(empty.toFile()), "empty directory");

            Path legacyDir = Files.createDirectory(root.resolve("legacy"));
            File legacy = write(legacyDir, "libksud.so", new byte[] {1});
            write(legacyDir, "librandom.so", daemon());
            expect(legacy, KsudResolver.find(legacyDir.toFile()), "legacy name takes precedence");

            Path renamedDir = Files.createDirectory(root.resolve("renamed"));
            File renamed = write(renamedDir, "libabcdefghij.so", daemon());
            byte[] jni = daemon();
            jni[24] = 0; // JNI shared libraries have no executable entry point.
            write(renamedDir, "libklmnopqrstu.so", jni);
            byte[] largeJni = withMarker(withMarker(elf(1_100_000), 128, "ksud"), 256, "late-load");
            largeJni[24] = 0;
            write(renamedDir, "libordinary.so", largeJni);
            expect(renamed, KsudResolver.find(renamedDir.toFile()), "random name beside JNI and large library");

            Path namesDir = Files.createDirectory(root.resolve("names"));
            write(namesDir, "daemon.so", daemon());
            write(namesDir, "libdaemon.txt", daemon());
            Files.createDirectory(namesDir.resolve("libksud.so"));
            expect(null, KsudResolver.find(namesDir.toFile()), "only regular lib*.so files qualify");

            assertRejected(root, "bad-magic", mutate(daemon(), 0, 0));
            assertRejected(root, "elf32", mutate(daemon(), 4, 1));
            assertRejected(root, "big-endian", mutate(daemon(), 5, 2));
            assertRejected(root, "wrong-architecture", mutate(daemon(), 18, 62));
            assertRejected(root, "relocatable", mutate(daemon(), 16, 1));
            assertRejected(root, "no-entry", mutate(daemon(), 24, 0));
            assertRejected(root, "truncated", new byte[] {0x7f, 'E', 'L', 'F', 2, 1});
            assertRejected(root, "empty-file", new byte[0]);
            assertRejected(root, "header-only", elf(64));
            assertRejected(root, "ordinary-executable", elf(1_200_000));
            assertRejected(root, "only-ksud", withMarker(elf(512), 128, "ksud"));
            assertRejected(root, "only-late-load", withMarker(elf(512), 128, "late-load"));
            for (int i = 0; i < MARKERS.length; i++) {
                byte[] incomplete = daemon();
                Arrays.fill(incomplete, 128 + i * 64, 128 + i * 64 + MARKERS[i].length(), (byte) 0);
                assertRejected(root, "missing-" + i, incomplete);
            }

            // DFRoot's bootstrap has the first three strings, but is not a manager daemon.
            byte[] bootstrap = withMarker(withMarker(withMarker(elf(512), 128,
                    "/data/adb/ksud"), 192, "late-load"), 256, "package-name");
            Path bootstrapDir = Files.createDirectory(root.resolve("bootstrap"));
            File helper = write(bootstrapDir, "libbootstrap.so", bootstrap);
            expect(null, KsudResolver.find(bootstrapDir.toFile()), "bootstrap is not a daemon");
            Files.move(helper.toPath(), bootstrapDir.resolve("libabcdefghij.so"));
            expect(null, KsudResolver.find(bootstrapDir.toFile()), "renamed bootstrap is not a daemon");
            File actualDaemon = write(bootstrapDir, "libsukid.so", daemon());
            expect(actualDaemon, KsudResolver.find(bootstrapDir.toFile()), "daemon beside bootstrap with unrestricted name");

            Path execDir = Files.createDirectory(root.resolve("exec"));
            File executable = write(execDir, "libexecutable.so", mutate(daemon(), 16, 2));
            expect(executable, KsudResolver.find(execDir.toFile()), "ET_EXEC daemon");

            // Each marker must survive every split across the first and a later read boundary.
            for (int boundary : new int[] {8192, 16_384 - 13}) {
                for (int i = 0; i < MARKERS.length; i++) {
                    for (int split = 1; split < MARKERS[i].length(); split++) {
                        String name = "boundary-" + boundary + "-" + i + "-" + split;
                        Path boundaryDir = Files.createDirectory(root.resolve(name));
                        byte[] bytes = Arrays.copyOf(daemon(), 25_000);
                        Arrays.fill(bytes, 128 + i * 64, 128 + i * 64 + MARKERS[i].length(), (byte) 0);
                        withMarker(bytes, boundary - split, MARKERS[i]);
                        File candidate = write(boundaryDir, "libboundary.so", bytes);
                        expect(candidate, KsudResolver.find(boundaryDir.toFile()), name);
                    }
                }
            }

            Path ambiguousDir = Files.createDirectory(root.resolve("ambiguous"));
            write(ambiguousDir, "libfirst.so", daemon());
            write(ambiguousDir, "libsecond.so", daemon());
            expect(null, KsudResolver.find(ambiguousDir.toFile()), "ambiguous daemon candidates");

            File notDir = write(root, "not-a-directory", daemon());
            expect(null, KsudResolver.find(notDir), "file in place of library directory");

            if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Path unreadable = Files.createDirectory(root.resolve("unreadable"));
                write(unreadable, "libhidden.so", daemon());
                Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(unreadable);
                Files.setPosixFilePermissions(unreadable, Collections.emptySet());
                try {
                    if (!unreadable.toFile().canRead()) {
                        expect(null, KsudResolver.find(unreadable.toFile()), "unreadable directory");
                    }
                } finally {
                    Files.setPosixFilePermissions(unreadable, permissions);
                }
            }
            if (args.length == 2) verifyApk(root, args[0], args[1]);
            System.out.println("KsudResolver: " + checks + " checks passed");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void verifyApk(Path root, String apkPath, String expectedName) throws Exception {
        Path directory = Files.createDirectory(root.resolve("apk"));
        Map<Path, byte[]> hashes = new HashMap<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String prefix = "lib/arm64-v8a/";
        try (ZipFile apk = new ZipFile(apkPath)) {
            var entries = apk.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().startsWith(prefix) || entry.isDirectory()) continue;
                String name = entry.getName().substring(prefix.length());
                if (name.contains("/") || !name.startsWith("lib") || !name.endsWith(".so")) continue;
                byte[] bytes;
                try (var input = apk.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                Path library = directory.resolve(name);
                Files.write(library, bytes);
                hashes.put(library, digest.digest(bytes));
            }
        }
        Path expected = directory.resolve(expectedName);
        if (!hashes.containsKey(expected)) throw new AssertionError("Expected daemon missing from APK");
        expect(expected.toFile(), KsudResolver.find(directory.toFile()), "official APK daemon");
        for (var entry : hashes.entrySet()) {
            if (!Arrays.equals(entry.getValue(), digest.digest(Files.readAllBytes(entry.getKey())))) {
                throw new AssertionError("Discovery modified " + entry.getKey());
            }
            checks++;
        }
        System.out.println("APK fixture: resolved " + expectedName + "; all library hashes unchanged");
    }

    private static void assertRejected(Path root, String name, byte[] bytes) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        write(directory, "libcandidate.so", bytes);
        expect(null, KsudResolver.find(directory.toFile()), name);
    }

    private static byte[] elf(int size) {
        byte[] bytes = new byte[size];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2; // ELFCLASS64
        bytes[5] = 1; // ELFDATA2LSB
        bytes[6] = 1; // EV_CURRENT
        bytes[16] = 3; // ET_DYN (PIE)
        bytes[18] = (byte) 183; // EM_AARCH64
        bytes[20] = 1;
        bytes[24] = 1; // Nonzero executable entry point.
        bytes[52] = 64;
        return bytes;
    }

    private static byte[] daemon() {
        byte[] bytes = elf(512);
        for (int i = 0; i < MARKERS.length; i++) withMarker(bytes, 128 + i * 64, MARKERS[i]);
        return bytes;
    }

    private static byte[] mutate(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        return bytes;
    }

    private static byte[] withMarker(byte[] bytes, int offset, String marker) {
        byte[] text = marker.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, bytes, offset, text.length);
        return bytes;
    }

    private static File write(Path directory, String name, byte[] bytes) throws IOException {
        return Files.write(directory.resolve(name), bytes).toFile();
    }

    private static void expect(File expected, File actual, String description) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
        checks++;
    }
}
