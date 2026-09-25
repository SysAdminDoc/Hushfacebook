/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.requireStoryFlagReaders
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

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
        requireStoryFlagReaders()
        fillStoryModelStub(GEN_AI_LABEL, DETECTED_INFO_STUB, accessor)

        enableStatus("aiDetectedPosts")
    }
}
