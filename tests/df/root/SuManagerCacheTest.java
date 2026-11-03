package df.root;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Measures expensive probe calls rather than filesystem timing. */
public final class SuManagerCacheTest {
    private static int checks;

    public static void main(String[] args) {
        CountingProbe probe = new CountingProbe();
        SuManagerCache cache = new SuManagerCache(Collections.emptyMap(), probe);
        check(!cache.isDirty(), "new cache starts clean");
        check(cache.hasKsud("manager", 1, "/app/base.apk", "/lib/manager"), "discover manager");
        check(!cache.hasKsud("ordinary", 1, "/other/base.apk", "/lib/ordinary"), "discover non-manager");
        check(probe.calls == 2 && cache.isDirty(), "first inventory probes both packages");

        for (int i = 0; i < 20; i++) {
            check(cache.hasKsud("manager", 1, "/app/base.apk", "/lib/manager"), "positive cache hit");
            check(!cache.hasKsud("ordinary", 1, "/other/base.apk", "/lib/ordinary"), "negative cache hit");
        }
        check(probe.calls == 2, "repeated scans add no expensive probes");

        Map<String, String> saved = cache.snapshot();
        CountingProbe restartedProbe = new CountingProbe();
        SuManagerCache restarted = new SuManagerCache(saved, restartedProbe);
        saved.clear();
        check(restarted.hasKsud("manager", 1, "/app/base.apk", "/lib/manager"), "persisted positive hit");
        check(!restarted.hasKsud("ordinary", 1, "/other/base.apk", "/lib/ordinary"), "persisted negative hit");
        check(restartedProbe.calls == 0 && !restarted.isDirty(), "cold restart reuses both results");
        restarted.snapshot().clear();
        check(restarted.snapshot().size() == 2, "snapshot cannot mutate cache");

        restarted.hasKsud("manager", 2, "/app/base.apk", "/lib/manager");
        check(restartedProbe.calls == 1, "update timestamp invalidates result");
        restarted.hasKsud("manager", 2, "/updated/base.apk", "/lib/manager");
        check(restartedProbe.calls == 2, "APK path invalidates result");
        restarted.hasKsud("manager", 2, "/updated/base.apk", "/lib/ordinary");
        check(restartedProbe.calls == 3, "native path invalidates result");
        check(!restarted.hasKsud("manager", 2, "/updated/base.apk", "/lib/ordinary"), "replacement negative hit");
        restarted.hasKsud("new-app", 1, "/new/base.apk", "/lib/ordinary");
        check(restartedProbe.calls == 4, "new package probes once");

        SuManagerCache pruned = new SuManagerCache(restarted.snapshot(), new CountingProbe());
        pruned.retainPackages(Set.of("manager", "ordinary", "new-app"));
        check(!pruned.isDirty(), "unchanged installed set stays clean");
        pruned.retainPackages(Set.of("manager"));
        check(pruned.isDirty() && pruned.snapshot().keySet().equals(Set.of("manager")), "uninstalled entries removed");

        Map<String, String> stale = new HashMap<>(cache.snapshot());
        stale.put("manager", stale.get("manager").replace("v1\n", "v0\n"));
        stale.put("ordinary", "invalid-cache-data");
        CountingProbe staleProbe = new CountingProbe();
        SuManagerCache invalidated = new SuManagerCache(stale, staleProbe);
        check(invalidated.hasKsud("manager", 1, "/app/base.apk", "/lib/manager"), "old schema rechecked");
        check(!invalidated.hasKsud("ordinary", 1, "/other/base.apk", "/lib/ordinary"), "malformed result rechecked");
        check(staleProbe.calls == 2, "invalid cache entries do not hide applications");

        CountingProbe nullProbe = new CountingProbe();
        SuManagerCache noLibraries = new SuManagerCache(Collections.emptyMap(), nullProbe);
        check(!noLibraries.hasKsud("no-libs", 1, null, null), "missing native directory is not a manager");
        check(!noLibraries.hasKsud("no-libs", 1, null, null), "null metadata can be cached");
        check(nullProbe.calls == 1 && nullProbe.lastDirectory == null, "null directory passed through once");
        noLibraries.hasKsud("no-libs", 1, "null", null);
        check(nullProbe.calls == 2, "null is distinct from a literal null path");
        check(noLibraries.hasKsud("no-libs", 1, "null", "/lib/manager"), "new native directory rechecked");

        // Delimiters in paths must not let one package identity impersonate another.
        CountingProbe pathProbe = new CountingProbe();
        SuManagerCache paths = new SuManagerCache(Collections.emptyMap(), pathProbe);
        paths.hasKsud("paths", 1, "a\nb", "c");
        paths.hasKsud("paths", 1, "a", "b\nc");
        check(pathProbe.calls == 2, "path boundary encoding is unambiguous");
        check(!new SuManagerCache(Collections.emptyMap()).hasKsud("no-native", 1, "/app.apk", null),
                "default probe handles null native directory");

        verifyIncrementalInventory();

        System.out.println("SuManagerCache: " + checks + " checks passed; unchanged packages require zero new probes");
    }

    private static void verifyIncrementalInventory() {
        CountingProbe initialProbe = new CountingProbe();
        SuManagerCache initial = new SuManagerCache(Collections.emptyMap(), initialProbe);
        for (int i = 0; i < 500; i++) {
            initial.hasKsud("app." + i, 1, "/apps/" + i + "/base.apk",
                    i == 0 ? "/lib/manager" : "/lib/app." + i);
        }
        check(initialProbe.calls == 500, "first inventory probes 500 new packages");

        CountingProbe restoredProbe = new CountingProbe();
        SuManagerCache restored = new SuManagerCache(initial.snapshot(), restoredProbe);
        for (int i = 0; i < 500; i++) {
            boolean found = restored.hasKsud("app." + i, 1, "/apps/" + i + "/base.apk",
                    i == 0 ? "/lib/manager" : "/lib/app." + i);
            check(found == (i == 0), "restored inventory preserves classification");
        }
        check(restoredProbe.calls == 0 && !restored.isDirty(), "unchanged inventory performs zero probes");

        // A formerly ordinary package becomes a manager after an update.
        check(restored.hasKsud("app.499", 2, "/apps/499/base.apk", "/lib/manager"),
                "updated package is newly recognized as a manager");
        check(restoredProbe.calls == 1, "one changed package requires just one probe");
        System.out.println("500-package inventory: first=500 probes, restored=0, one update=1");
    }

    private static final class CountingProbe implements SuManagerCache.Probe {
        int calls;
        String lastDirectory;

        @Override
        public boolean hasKsud(String nativeLibraryDir) {
            calls++;
            lastDirectory = nativeLibraryDir;
            return "/lib/manager".equals(nativeLibraryDir);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
