/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

/** The extension class that reads the flag, and its accessor this patch fills in. */
internal const val GEN_AI_LABEL = "$EXTENSION_PACKAGE/feed/GenAiLabel;"
internal const val DETECTED_INFO_STUB = "detectedInfo"

/**
 * Hides feed posts that Facebook's own detection marked as made with AI.
 *
 * The rule runs in the shared feed guard like every other feed rule, so `addNewEdgeToCollection`
 * still carries one Hushfacebook guard. What this patch adds is the one read the guard can't make
 * by name: GraphQLStory's accessor of the detected-AI info model, which Redex renames every build.
 * It is found by the two schema keys it loads (see Fingerprints.kt), held to the model
 * GenAiTransparencyPlugin reads the flag on, and written into `GenAiLabel.detectedInfo`, which
 * answers a marker until then. Everything else, the flag included, the extension reads through
 * members Facebook keeps.
 *
 * The switch starts off. Nobody has yet recorded a signed-in feed with one AI-labeled post and one
 * ordinary post beside it, and until someone does, the rule waits to be turned on.
 */
@Suppress("unused")
val hideAiDetectedPostsPatch = bytecodePatch(
    name = "Hide AI-detected posts",
    description = "Removes feed posts that Facebook's own detection marked as made with AI. Its switch " +
        "starts off, so turn it on in Hushfacebook's settings.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val story = classDefBy(GRAPHQL_STORY)
        val accessors = detectedInfoAccessors(story)
        val accessor = accessors.singleOrNull() ?: throw PatchException(
            "GraphQLStory has ${accessors.size} accessors of $DETECTED_INFO_FIELD as $DETECTED_INFO_TYPE, " +
                "expected one: ${accessors.joinToString { it.name }}",
        )

        // Facebook's own label reads the flag on this model. If it stops doing that, the flag may
        // have moved or changed meaning, and a rule that guesses could hide the wrong posts.
        val plugin = classDefByOrNull(GEN_AI_TRANSPARENCY_PLUGIN)
            ?: throw PatchException("GenAiTransparencyPlugin is gone, so nothing shows which flag Facebook's AI label reads")
        if (plugin.methods.none { readsDetectedFlag(it, accessor) }) {
            throw PatchException(
                "GenAiTransparencyPlugin no longer reads $DETECTED_FLAG through GraphQLStory.${accessor.name}()",
            )
        }

        // The extension reads the flag and the model's type tag through these, by reflection.
        if (!hasPublicBooleanReader(classDefBy(BASE_MODEL_WITH_TREE))) {
            throw PatchException("BaseModelWithTree has no public getCachedBoolean(int)")
        }
        if (!hasPublicTypeTag(classDefBy(TREE_JNI))) {
            throw PatchException("TreeJNI has no public int mTypeTag")
        }

        val stub = mutableClassDefBy(GEN_AI_LABEL).methods.singleOrNull {
            it.name == DETECTED_INFO_STUB && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.returnType == "Ljava/lang/Object;" &&
                it.parameterTypes.map { type -> type.toString() } == listOf("Ljava/lang/Object;")
        } ?: throw PatchException("GenAiLabel has no static Object $DETECTED_INFO_STUB(Object)")

        // Only the parameter register is used, so the stub's own register count doesn't matter.
        // The extension checks the unit is a GraphQLStory before it calls this.
        stub.addInstructions(
            0,
            """
                check-cast p0, $GRAPHQL_STORY
                invoke-virtual { p0 }, $GRAPHQL_STORY->${accessor.name}()${accessor.returnType}
                move-result-object p0
                return-object p0
            """,
        )

        enableStatus("aiDetectedPosts")
    }
}
