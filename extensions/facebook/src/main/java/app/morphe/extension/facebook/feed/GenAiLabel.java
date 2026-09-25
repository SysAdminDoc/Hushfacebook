/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The rule follows the Facebook 573 AI filter of FroggoMorphePatches
 * (https://github.com/SapitoSucio/FroggoMorphePatches, GPL-3.0) in what it hides. Its code
 * isn't here: that filter names 573's obfuscated members, and this reads kept ones.
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * Whether a feed unit carries the flag Facebook sets on a post its own detection found to be made
 * with AI: {@code ai_generated_detected_info.was_detected_as_ai_generated} on a GraphQLStory. It is
 * the flag Facebook's AI label in the post header reads.
 *
 * <p>Only a definite true hides anything. A unit that isn't a story, a story with no GenAI info, a
 * false flag, and anything this can't be sure of all keep the post, and each says why in the
 * diagnostic report. A post its creator labeled as AI, and Facebook didn't detect, carries its own
 * flag on another model and stays.
 */
public final class GenAiLabel {
    /** The GraphQL names. See the patch's Fingerprints.kt for how the keys were read. */
    static final String DETECTED_INFO_TYPE = "XFBAIGeneratedDetectedInfo";
    static final String DETECTED_FLAG = "was_detected_as_ai_generated";

    /** A tree model keys a field by its name's hash code. */
    static final int DETECTED_FLAG_KEY = DETECTED_FLAG.hashCode();

    /** A tree model is tagged with the first four bytes of its GraphQL type name's MD5. */
    static final int DETECTED_INFO_TYPE_TAG = typeTag(DETECTED_INFO_TYPE);

    /** Kept names this reads by. */
    static final String STORY_CLASS = "com.facebook.graphql.model.GraphQLStory";
    static final String TREE_MODEL_CLASS = "com.facebook.graphql.modelutil.BaseModelWithTree";
    static final String TREE_CLASS = "com.facebook.graphservice.tree.TreeJNI";

    /** What {@link #detectedInfo} answers until the patch fills it in. */
    static final Object NOT_PATCHED = new Object();

    /** The story's GenAI info, however it's read. The patched accessor in the app, a stand-in in a test. */
    interface Accessor {
        @Nullable
        Object detectedInfo(Object story);
    }

    static final Accessor PATCHED = GenAiLabel::detectedInfo;

    /**
     * What reading one feed unit found. The reason is the name the report counts it under, and
     * names a shape, never content.
     */
    enum Outcome {
        FLAGGED("flag true", true),
        NOT_FLAGGED("flag false", false),
        NO_INFO("no GenAI info", false),
        NO_UNIT("no feed unit", false),
        NOT_A_STORY("not a story", false),
        NO_READER("reader missing", false),
        NO_ACCESSOR("accessor not patched", false),
        OTHER_TYPE("ambiguous: info of another type", false),
        NOT_A_TREE("ambiguous: info not a tree model", false),
        READ_FAILED("read failed", false);

        final String reason;
        final boolean hides;

        Outcome(String reason, boolean hides) {
            this.reason = reason;
            this.hides = hides;
        }
    }

    private GenAiLabel() {
    }

    /**
     * Injection point, filled in by the patch: the story's {@code ai_generated_detected_info}
     * model, or null when it has none. The patch replaces this body with a call to GraphQLStory's
     * accessor, whose name changes every build. Only a GraphQLStory may be passed.
     */
    public static Object detectedInfo(Object story) {
        return NOT_PATCHED;
    }

    /** What the rule makes of this feed unit. Never throws. */
    static Outcome read(@Nullable Object feedUnit, Accessor accessor) {
        if (feedUnit == null) return Outcome.NO_UNIT;
        Reader found = reader();
        if (!found.complete()) return Outcome.NO_READER;
        if (!found.story.isInstance(feedUnit)) return Outcome.NOT_A_STORY;

        Object info;
        try {
            info = accessor.detectedInfo(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(FamilyNames.AI_DETECTED_POSTS, "GenAI info accessor", failure);
            return Outcome.READ_FAILED;
        }
        if (info == NOT_PATCHED) {
            HookStatus.missingMember(FamilyNames.AI_DETECTED_POSTS, "method", STORY_CLASS,
                    "the ai_generated_detected_info accessor");
            return Outcome.NO_ACCESSOR;
        }
        if (info == null) return Outcome.NO_INFO;
        if (!found.treeModel.isInstance(info)) return Outcome.NOT_A_TREE;

        try {
            if (found.typeTag.getInt(info) != DETECTED_INFO_TYPE_TAG) return Outcome.OTHER_TYPE;
            Object flag = found.cachedBoolean.invoke(info, DETECTED_FLAG_KEY);
            if (!(flag instanceof Boolean)) return Outcome.READ_FAILED;
            return (Boolean) flag ? Outcome.FLAGGED : Outcome.NOT_FLAGGED;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(FamilyNames.AI_DETECTED_POSTS, "GenAI flag reader", failure.getCause());
            return Outcome.READ_FAILED;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(FamilyNames.AI_DETECTED_POSTS, "GenAI flag reader", failure);
            return Outcome.READ_FAILED;
        }
    }

    /**
     * The kept members the rule reads by, looked up once. A member this build doesn't have is null,
     * and the rule then keeps every post and the report names what's missing.
     */
    static final class Reader {
        @Nullable final Class<?> story;
        @Nullable final Class<?> treeModel;
        @Nullable final Method cachedBoolean;
        @Nullable final Field typeTag;

        Reader(@Nullable Class<?> story, @Nullable Class<?> treeModel, @Nullable Method cachedBoolean,
               @Nullable Field typeTag) {
            this.story = story;
            this.treeModel = treeModel;
            this.cachedBoolean = cachedBoolean;
            this.typeTag = typeTag;
        }

        boolean complete() {
            return story != null && treeModel != null && cachedBoolean != null && typeTag != null;
        }

        static Reader lookUp(ClassLoader loader) {
            Class<?> story = classOrNull(STORY_CLASS, loader);
            Class<?> treeModel = classOrNull(TREE_MODEL_CLASS, loader);
            Class<?> tree = classOrNull(TREE_CLASS, loader);
            Method cachedBoolean = null;
            Field typeTag = null;
            try {
                if (treeModel != null) cachedBoolean = treeModel.getMethod("getCachedBoolean", int.class);
            } catch (NoSuchMethodException | RuntimeException missing) {
                // Named in the report.
            }
            try {
                if (tree != null) {
                    Field field = tree.getField("mTypeTag");
                    if (field.getType() == int.class) typeTag = field;
                }
            } catch (NoSuchFieldException | RuntimeException missing) {
                // Named in the report.
            }
            return new Reader(story, treeModel, cachedBoolean, typeTag);
        }

        /** What was found and what wasn't, into the family's Hook status row. */
        void report() {
            String family = FamilyNames.AI_DETECTED_POSTS;
            if (story != null) HookStatus.bound(family, "GraphQLStory");
            else HookStatus.missingMember(family, "class", "com.facebook.graphql.model", "GraphQLStory");
            if (cachedBoolean != null) HookStatus.bound(family, "BaseModelWithTree#getCachedBoolean");
            else HookStatus.missingMember(family, "method", TREE_MODEL_CLASS, "getCachedBoolean(int)");
            if (typeTag != null) HookStatus.bound(family, "TreeJNI#mTypeTag");
            else HookStatus.missingMember(family, "field", TREE_CLASS, "mTypeTag");
        }

        @Nullable
        private static Class<?> classOrNull(String name, ClassLoader loader) {
            try {
                return Class.forName(name, false, loader);
            } catch (Throwable missing) {
                return null;
            }
        }
    }

    /** Looked up on first use. A test sets it to stand in for a build missing a member. */
    static volatile Reader cachedReader;

    /** Which Hook status row the reader was last reported into. */
    private static volatile long reportedGeneration = -1;

    /**
     * The reader, reported into Hook status once per row: again after a diagnostic clear, which
     * empties the row it was written into.
     */
    static Reader reader() {
        Reader found = cachedReader;
        if (found == null) {
            found = Reader.lookUp(GenAiLabel.class.getClassLoader());
            cachedReader = found;
        }
        long generation = HookStatus.generation();
        if (reportedGeneration != generation) {
            reportedGeneration = generation;
            found.report();
        }
        return found;
    }

    /** The tag Facebook's tree models give a GraphQL type: its name's MD5, first four bytes. */
    static int typeTag(String typeName) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(typeName.getBytes(StandardCharsets.UTF_8));
            return ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8)
                    | (digest[3] & 0xff);
        } catch (NoSuchAlgorithmException missing) {
            // Every Android build carries MD5. Without it no model's tag matches, and the rule
            // keeps every post as another type's.
            return 0;
        }
    }
}
