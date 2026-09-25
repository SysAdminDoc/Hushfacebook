/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The rule follows the Facebook 573 AI filter of FroggoMorphePatches
 * (https://github.com/SapitoSucio/FroggoMorphePatches, GPL-3.0) in what it hides. Its code
 * isn't here: that filter names 573's obfuscated members, and this reads kept ones.
 */
package app.morphe.extension.facebook.feed;

import app.morphe.extension.facebook.settings.FamilyNames;

/**
 * Whether a feed unit carries the flag Facebook sets on a post its own detection found to be made
 * with AI: {@code ai_generated_detected_info.was_detected_as_ai_generated} on a GraphQLStory. It is
 * the flag Facebook's AI label in the post header reads.
 *
 * <p>Only a definite true hides anything (see {@link StoryFlag}). A post its creator labeled as
 * AI, and Facebook didn't detect, carries its own flag on another model and stays.
 */
public final class GenAiLabel {
    /** The GraphQL names. See the patch's Fingerprints.kt for how the keys were read. */
    static final String DETECTED_INFO_FIELD = "ai_generated_detected_info";
    static final String DETECTED_INFO_TYPE = "XFBAIGeneratedDetectedInfo";
    static final String DETECTED_FLAG = "was_detected_as_ai_generated";

    static final StoryFlag FLAG = new StoryFlag(FamilyNames.AI_DETECTED_POSTS, "GenAI", "GenAI info",
            DETECTED_INFO_FIELD, DETECTED_INFO_TYPE, DETECTED_FLAG);

    /** A tree model keys a field by its name's hash code. */
    static final int DETECTED_FLAG_KEY = FLAG.flagKey;

    /** A tree model is tagged with the first four bytes of its GraphQL type name's MD5. */
    static final int DETECTED_INFO_TYPE_TAG = FLAG.modelTypeTag;

    static final StoryFlag.Accessor PATCHED = GenAiLabel::detectedInfo;

    private GenAiLabel() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code ai_generated_detected_info}
     * model, or null when it has none. The patch replaces this body with a call to GraphQLStory's
     * accessor, whose name changes every build. Only a GraphQLStory may be passed.
     */
    public static Object detectedInfo(Object story) {
        return StoryFlag.NOT_PATCHED;
    }
}
