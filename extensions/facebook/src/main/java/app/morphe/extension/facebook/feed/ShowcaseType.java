/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * What kind of row a ShowcaseFeedUnit is: {@code showcase_story_type}, an enum Facebook keeps on the
 * unit and renders the row by. A "Reels" carousel between posts isn't always filed under a reels
 * story category (another fork's filter for 573 hid them as SHOWCASE edges), and this is what
 * still tells it apart from the showcase rows that aren't reels, such as events or Marketplace.
 *
 * <p>The unit's accessor for the field is a Redex name that changes every build, and its class is
 * shared with other feed units, so a patch fills in a stub that calls it. The extension calls the
 * stub only for a unit whose {@code getTypeName()} answers {@link #UNIT_TYPE}, which the patch
 * holds to exactly one class. The enum's own constant names survive the obfuscator.
 */
public final class ShowcaseType {
    /** The GraphQL type the showcase rows answer {@code getTypeName()} with. */
    static final String UNIT_TYPE = "ShowcaseFeedUnit";
    /** The field the story type sits under, for the report. */
    static final String STORY_TYPE_FIELD = "showcase_story_type";

    /**
     * The story types that are rows of reels. The same names are in the enum on 577 and 580, and
     * the patch holds each build to all of them. SHOWCASE_FB_HASHTAG_DEEP_DIVE stays out until a
     * signed-in feed shows what it looks like.
     */
    static final String[] REELS_TYPES = {
            "SHOWCASE_SHORT_VIDEO",
            "SHOWCASE_FB_SHORTS_CFU",
            "SHOWCASE_FB_SHORTS_DYNAMIC_MIDCARD",
            "SHOWCASE_FB_SHORTS_MIDCARD",
            "SHOWCASE_FB_SHORTS_PROMO",
            "SHOWCASE_FB_SEARCH_REELS",
    };

    /** What {@link #read} answers when it can't say, each its own shape for the report. */
    static final String NOT_PATCHED = "accessor not patched";
    static final String NO_TYPE = "no story type";
    static final String NOT_AN_ENUM = "story type not an enum";
    static final String READ_FAILED = "read failed";

    static final StoryFlag.Accessor PATCHED = ShowcaseType::storyType;

    private ShowcaseType() {
    }

    /**
     * Injection point, filled in by the patch: the unit's {@code showcase_story_type} enum, or
     * Facebook's default constant when it has none. The patch replaces this body with a call to the
     * unit's accessor, whose name changes every build. Only a unit answering {@link #UNIT_TYPE} may
     * be passed.
     */
    public static Object storyType(Object unit) {
        return StoryFlag.NOT_PATCHED;
    }

    /**
     * The story type's constant name, or why there is none. Never throws: an accessor that throws
     * is recorded in Hook status and the unit reads as {@link #READ_FAILED}, which keeps it.
     */
    static String read(Object unit, StoryFlag.Accessor accessor) {
        Object type;
        try {
            type = accessor.model(unit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.FEED_REELS, "showcase story type accessor", failure);
            return READ_FAILED;
        }
        if (type == StoryFlag.NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.FEED_REELS, "method", UNIT_TYPE, "the " + STORY_TYPE_FIELD + " accessor");
            return NOT_PATCHED;
        }
        if (type == null) return NO_TYPE;
        if (!(type instanceof Enum)) return NOT_AN_ENUM;
        HookStatus.bound(FamilyNames.FEED_REELS, UNIT_TYPE + "#" + STORY_TYPE_FIELD);
        return ((Enum<?>) type).name();
    }

    /** Whether a story type read off a showcase unit is one of the rows of reels. */
    static boolean isReels(String type) {
        if (type == null) return false;
        for (String reels : REELS_TYPES) {
            if (reels.equals(type)) return true;
        }
        return false;
    }
}
