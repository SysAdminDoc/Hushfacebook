/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import com.facebook.graphql.modelutil.BaseModelWithTree;

/**
 * The feed guard with both feed patches in, for a test outside this package. A test JVM has no
 * patched {@code SettingsStatus}, so the public guard would hide nothing whatever the switches say.
 */
public final class FeedGuardForTests {
    private FeedGuardForTests() {
    }

    public static boolean hides(Object category, Object feedUnit) {
        return FeedFilter.hideEdge(category, feedUnit, true, true);
    }

    /**
     * The guard with the GenAI patch in as well. A test JVM has no patched accessor either, so any
     * story's GenAI info is {@code detectedInfo}.
     */
    public static boolean hides(Object category, Object feedUnit, Object detectedInfo) {
        return FeedFilter.hideEdge(category, feedUnit, true, true, true, story -> detectedInfo);
    }

    /** GenAI info of the type Facebook's detection writes, with its flag set to [flagged]. */
    public static BaseModelWithTree detectedInfo(boolean flagged) {
        return new BaseModelWithTree(GenAiLabel.DETECTED_INFO_TYPE_TAG).with(GenAiLabel.DETECTED_FLAG, flagged);
    }
}
