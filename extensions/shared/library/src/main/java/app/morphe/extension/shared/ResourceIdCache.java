/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.shared;

import android.content.res.Resources;

import java.util.HashMap;
import java.util.Map;

/** Resolves resource ids once while allowing dynamic feature packages to appear later. */
public final class ResourceIdCache {
    private final Map<String, Integer> ids = new HashMap<>();

    /**
     * Resolves an id by name and package. A zero result is cached unless the package may load
     * after the first lookup.
     */
    public synchronized int resolve(
            Resources resources,
            String packageName,
            String name,
            boolean retryMissing
    ) {
        String key = packageName + ":" + name;
        Integer cached = ids.get(key);
        if (cached != null) {
            return cached;
        }

        int id;
        try {
            id = resources == null ? 0 : resources.getIdentifier(name, "id", packageName);
        } catch (Throwable ignored) {
            id = 0;
        }
        if (id != 0 || !retryMissing) {
            ids.put(key, id);
        }
        return id;
    }

    /** Supplies a deterministic id for an isolated test fixture. */
    public synchronized void putForTests(String packageName, String name, int id) {
        ids.put(packageName + ":" + name, id);
    }

    /** Clears values when a host activity or dynamic resource package is replaced. */
    public synchronized void clear() {
        ids.clear();
    }
}
