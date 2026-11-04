package df.root;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Caches discovery results for immutable installed-package versions, including misses. */
final class SuManagerCache {
    interface Probe {
        boolean hasKsud(String nativeLibraryDir);
    }

    private final Map<String, String> entries;
    private final Probe probe;
    private boolean dirty;

    SuManagerCache(Map<String, String> saved) {
        this(saved, directory -> KsudResolver.find(directory == null ? null : new File(directory)) != null);
    }

    SuManagerCache(Map<String, String> saved, Probe probe) {
        entries = new HashMap<>(saved);
        this.probe = probe;
    }

    boolean hasKsud(String packageName, long lastUpdateTime, String sourceDir, String nativeLibraryDir) {
        // Bump this schema when the daemon recognition rules change.
        String identity = "v2\n" + lastUpdateTime + "\n" + field(sourceDir) + field(nativeLibraryDir);
        String saved = entries.get(packageName);
        if ((identity + "true").equals(saved)) return true;
        if ((identity + "false").equals(saved)) return false;

        boolean found = probe.hasKsud(nativeLibraryDir);
        entries.put(packageName, identity + found);
        dirty = true;
        return found;
    }

    void retainPackages(Set<String> installed) {
        dirty |= entries.keySet().retainAll(installed);
    }

    boolean isDirty() {
        return dirty;
    }

    Map<String, String> snapshot() {
        return new HashMap<>(entries);
    }

    private static String field(String value) {
        return value == null ? "-1:" : value.length() + ":" + value;
    }
}
