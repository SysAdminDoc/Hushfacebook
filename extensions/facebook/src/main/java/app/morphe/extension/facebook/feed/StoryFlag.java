/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

import androidx.annotation.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import app.morphe.extension.shared.diagnostics.HookStatus;

/**
 * A boolean Facebook keeps on a model that hangs off a GraphQLStory, read the way Facebook's own
 * code reads it.
 *
 * <p>Facebook's tree models key a field by the {@code String.hashCode()} of its GraphQL name, and
 * tag a model with the first four bytes of the MD5 of its GraphQL type's name. The story's accessor
 * for the model is a Redex name that changes every build, so a patch fills in a stub that calls it.
 * Everything after that goes through members Facebook keeps: the model's {@code TreeJNI.mTypeTag},
 * to be sure it's the type the flag belongs to, and {@code BaseModelWithTree.getCachedBoolean},
 * the reader Facebook's own code calls. That reader checks the native tree is still there before it
 * reads, so a released model reads false rather than crashing.
 *
 * <p>Only a definite true counts. A unit that isn't a story, a story with no such model, a model of
 * another type, a false flag and anything this can't read all keep the post, and each has its own
 * reason for the diagnostic report.
 */
final class StoryFlag {
    /** Kept names this reads by. */
    static final String STORY_CLASS = "com.facebook.graphql.model.GraphQLStory";
    static final String TREE_MODEL_CLASS = "com.facebook.graphql.modelutil.BaseModelWithTree";
    static final String TREE_CLASS = "com.facebook.graphservice.tree.TreeJNI";

    /** What a stub answers until its patch fills it in. */
    static final Object NOT_PATCHED = new Object();

    /** The story's model, however it's read: the stub a patch filled in, or a stand-in in a test. */
    interface Accessor {
        @Nullable
        Object model(Object story);
    }

    /** What reading one feed unit found. Only {@link #FLAGGED} hides anything. */
    enum Outcome {
        FLAGGED(true),
        NOT_FLAGGED(false),
        NO_MODEL(false),
        NO_UNIT(false),
        NOT_A_STORY(false),
        NO_READER(false),
        NO_ACCESSOR(false),
        OTHER_TYPE(false),
        NOT_A_TREE(false),
        READ_FAILED(false);

        final boolean hides;

        Outcome(boolean hides) {
            this.hides = hides;
        }
    }

    /** The Hook status family the reads report under. */
    final String family;
    /** The GraphQL field the story holds the model under, and the model's GraphQL type. */
    final String modelField;
    final String modelType;
    /** The flag's GraphQL name, which is also what a hidden post's reason says. */
    final String flag;
    final int modelTypeTag;
    final int flagKey;
    /** The hook names Hook status uses when the accessor or the reader throws. */
    private final String accessorHook;
    private final String readerHook;
    /** What the report counts each outcome under: a shape, never content. */
    private final String[] reasons;

    /** Which Hook status row this flag last reported the members into. */
    private volatile long reportedGeneration = -1;

    /**
     * @param label      what the hook names start with, such as "GenAI".
     * @param modelLabel what the model is called in the report, such as "GenAI info".
     */
    StoryFlag(String family, String label, String modelLabel, String modelField, String modelType, String flag) {
        this.family = family;
        this.modelField = modelField;
        this.modelType = modelType;
        this.flag = flag;
        this.modelTypeTag = typeTag(modelType);
        this.flagKey = flag.hashCode();
        this.accessorHook = modelLabel + " accessor";
        this.readerHook = label + " flag reader";
        reasons = new String[Outcome.values().length];
        reasons[Outcome.FLAGGED.ordinal()] = "flag true";
        reasons[Outcome.NOT_FLAGGED.ordinal()] = "flag false";
        reasons[Outcome.NO_MODEL.ordinal()] = "no " + modelLabel;
        reasons[Outcome.NO_UNIT.ordinal()] = "no feed unit";
        reasons[Outcome.NOT_A_STORY.ordinal()] = "not a story";
        reasons[Outcome.NO_READER.ordinal()] = "reader missing";
        reasons[Outcome.NO_ACCESSOR.ordinal()] = "accessor not patched";
        reasons[Outcome.OTHER_TYPE.ordinal()] = "ambiguous: info of another type";
        reasons[Outcome.NOT_A_TREE.ordinal()] = "ambiguous: info not a tree model";
        reasons[Outcome.READ_FAILED.ordinal()] = "read failed";
    }

    /** What the report counts this outcome under. */
    String reason(Outcome outcome) {
        return reasons[outcome.ordinal()];
    }

    /** What this flag makes of the feed unit. Never throws. */
    Outcome read(@Nullable Object feedUnit, Accessor accessor) {
        if (feedUnit == null) return Outcome.NO_UNIT;
        Members found = members();
        report();
        if (!found.complete()) return Outcome.NO_READER;
        if (!found.story.isInstance(feedUnit)) return Outcome.NOT_A_STORY;

        Object model;
        try {
            model = accessor.model(feedUnit);
        } catch (Throwable failure) {
            HookStatus.threw(family, accessorHook, failure);
            return Outcome.READ_FAILED;
        }
        if (model == NOT_PATCHED) {
            HookStatus.missingMember(family, "method", STORY_CLASS, "the " + modelField + " accessor");
            return Outcome.NO_ACCESSOR;
        }
        if (model == null) return Outcome.NO_MODEL;
        if (!found.treeModel.isInstance(model)) return Outcome.NOT_A_TREE;

        try {
            if (found.typeTag.getInt(model) != modelTypeTag) return Outcome.OTHER_TYPE;
            Object value = found.cachedBoolean.invoke(model, flagKey);
            if (!(value instanceof Boolean)) return Outcome.READ_FAILED;
            return (Boolean) value ? Outcome.FLAGGED : Outcome.NOT_FLAGGED;
        } catch (InvocationTargetException failure) {
            HookStatus.threw(family, readerHook, failure.getCause());
            return Outcome.READ_FAILED;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            HookStatus.threw(family, readerHook, failure);
            return Outcome.READ_FAILED;
        }
    }

    /**
     * The members, reported into this flag's Hook status row once per row: again after a
     * diagnostic clear, which empties the row it was written into.
     */
    void report() {
        long generation = HookStatus.generation();
        if (reportedGeneration == generation) return;
        reportedGeneration = generation;
        members().reportInto(family);
    }

    /**
     * The kept members a flag is read through, looked up once and shared by every flag. A member
     * this build doesn't have is null, and every flag then keeps every post while the report names
     * what's missing.
     */
    static final class Members {
        @Nullable final Class<?> story;
        @Nullable final Class<?> treeModel;
        @Nullable final Method cachedBoolean;
        @Nullable final Field typeTag;

        Members(@Nullable Class<?> story, @Nullable Class<?> treeModel, @Nullable Method cachedBoolean,
                @Nullable Field typeTag) {
            this.story = story;
            this.treeModel = treeModel;
            this.cachedBoolean = cachedBoolean;
            this.typeTag = typeTag;
        }

        boolean complete() {
            return story != null && treeModel != null && cachedBoolean != null && typeTag != null;
        }

        static Members lookUp(ClassLoader loader) {
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
            return new Members(story, treeModel, cachedBoolean, typeTag);
        }

        /** What was found and what wasn't, into one family's Hook status row. */
        void reportInto(String family) {
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
    static volatile Members cachedMembers;

    static Members members() {
        Members found = cachedMembers;
        if (found == null) {
            found = Members.lookUp(StoryFlag.class.getClassLoader());
            cachedMembers = found;
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
            // Every Android build carries MD5. Without it no model's tag matches, and every flag
            // keeps every post as another type's.
            return 0;
        }
    }
}
