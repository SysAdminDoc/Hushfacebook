/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * The chokepoint and the two getters come from
 * https://github.com/andrewliang25/morphe-patches (GPL-3.0, Andrew Liang).
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.shared.AddNewEdgeToCollectionFingerprint
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import app.morphe.patches.facebook.shared.feedUnitGetter
import app.morphe.patches.facebook.misc.extension.parameterRegister
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.shared.storyCategoryGetter

private const val HIDE_EDGE =
    "$EXTENSION_PACKAGE/feed/FeedFilter;->hideEdge(Ljava/lang/Object;Ljava/lang/Object;)Z"

/**
 * The one guard every feed filter shares, at the start of `addNewEdgeToCollection`.
 *
 * <p>It reads the edge's story category and its feed unit and asks the extension whether the edge
 * stays out. The feed patches only switch their rule on there. One guard rather than one per patch
 * is deliberate: FroggoMorphePatches prepended several label-bearing guards to this method and
 * users got "target dex pc is not at instruction start" crashes, because only the guard inserted
 * last kept its branch targets.
 *
 * <p>Nothing here is named by the obfuscator. The method keeps its name, the edge and the enum are
 * kept model classes, and the two getters are picked by return type and by the `inflateFeedUnit`
 * literal. A rule that needs more of the unit than this, such as the GenAI flag, reads it in the
 * extension from the feed unit this guard already passes, so no rule adds code here.
 */
internal val feedFilterHookPatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        val categoryGetter = storyCategoryGetter()
        val feedUnit = feedUnitGetter()

        val method = AddNewEdgeToCollectionFingerprint.method
        val edgeIndex = method.parameterTypes.indexOfFirst { it.toString() == FEED_UNIT_EDGE }
        if (edgeIndex < 0) {
            throw PatchException("addNewEdgeToCollection no longer takes a GraphQLFeedUnitEdge")
        }
        // Index 0, where no local holds anything yet, so two locals are free to borrow.
        method.requireLocals("Feed filter", 2)

        // The edge is a high parameter register, and invoke-virtual takes 4-bit operands, so it is
        // copied down first.
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, ${method.parameterRegister(edgeIndex)}
                invoke-virtual { v0 }, $FEED_UNIT_EDGE->$categoryGetter()$FEED_STORY_CATEGORY
                move-result-object v1
                invoke-virtual { v0 }, $FEED_UNIT_EDGE->${feedUnit.name}()${feedUnit.returnType}
                move-result-object v0
                invoke-static { v1, v0 }, $HIDE_EDGE
                move-result v0
                if-eqz v0, :keep
                const/4 v0, 0x0
                return v0
            """,
            ExternalLabel("keep", method.getInstruction(0)),
        )
    }
}
