/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.shared;

import java.util.Map;

/** The generated tables, for a test outside this package. */
public final class L10nTablesForTests {
    private L10nTablesForTests() {
    }

    /** The table for a tag as L10nTranslations keys it ("de", "pt-rbr", "in"), or null. */
    public static Map<String, String> of(String language) {
        return L10nTranslations.of(language);
    }
}
