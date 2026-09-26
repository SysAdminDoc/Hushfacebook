/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.reels

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.feed.fillStoryModelStub
import app.morphe.patches.facebook.feed.hook.feedFilterHookPatch
import app.morphe.patches.facebook.feed.holdsString
import app.morphe.patches.facebook.feed.methodsHolding
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.facebook.shared.FEED_STORY_CATEGORY
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The story categories Facebook files the feed's rows of reels under, as the extension's
 * `FeedFilter.REELS_CATEGORIES` names them. FeedReelsCategoryTest holds the two lists together.
 */
internal val REELS_CATEGORIES = listOf("FB_SHORTS", "FB_SHORTS_FALLBACK", "END_OF_FEED_REELS")

/**
 * The names an enum's static initializer builds its constants with: GraphQLFeedStoryCategory's,
 * and the showcase story type's.
 */
internal fun categoryNames(category: ClassDef): Set<String> =
    category.methods.filter { it.name == "<clinit>" }.flatMap { method ->
        method.implementation?.instructions?.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
        } ?: emptyList()
    }.toSet()

/** The extension class that reads a showcase unit's story type, and its accessor this patch fills in. */
internal const val SHOWCASE_TYPE = "$EXTENSION_PACKAGE/feed/ShowcaseType;"
internal const val STORY_TYPE_STUB = "storyType"

/** The extension's switch for the pre-EOF injector's own Reels row. */
internal const val HIDE_PRE_EOF_REELS = "$EXTENSION_PACKAGE/feed/FeedFilter;->hidePreEofReels()Z"

/**
 * Removes the feed's rows of reels: the "Reels" carousels between posts, and the reels Facebook
 * adds where the feed you follow ends.
 *
 * Each row is a feed edge of its own, filed under a reels story category (a debug log of every
 * edge on a signed-in 580 showed `FB_SHORTS` and `END_OF_FEED_REELS`, both ShowcaseFeedUnit), so
 * the rule lives in the shared feed guard with the others, and `addNewEdgeToCollection` still
 * carries one Hushfacebook guard. The rule matches the categories by name, so the patch holds the
 * build to all of them: a Facebook that renamed one would otherwise leave the switch hiding nothing
 * without a word. A reel a friend posts is an ordinary post and stays. Off by default, like the
 * Stories tray.
 *
 * Two more ways a row of reels reaches the feed are covered here. A showcase row filed under
 * another category is told apart by its story type, which the unit's renamed accessor reads, so
 * the patch fills the extension's stub with it (see Fingerprints.kt for the evidence it's held to).
 * And the pre-EOF injector builds a Reels row of its own and adds it at the tail of the feed
 * without passing `addNewEdgeToCollection`, so it gets a hook of its own at its first instruction:
 * ask the extension, and return before building anything when told to. The patch checks that
 * those two are the only callers of the tail adder, so no third way in goes unseen.
 */
@Suppress("unused")
val hideFeedReelsPatch = bytecodePatch(
    name = "Hide Reels in the feed",
    description = "Removes the rows of reels between posts in the news feed, and the reels Facebook adds " +
        "where your feed ends. A reel a friend posts stays.",
    default = false,
) {
    category("Feed")
    dependsOn(settingsPatch)
    dependsOn(feedFilterHookPatch)
    compatibleWith(*AppCompatibilities.facebook())

    execute {
        val names = categoryNames(classDefBy(FEED_STORY_CATEGORY))
        val missing = REELS_CATEGORIES.filterNot(names::contains)
        if (missing.isNotEmpty()) {
            throw PatchException(
                "GraphQLFeedStoryCategory has no ${missing.joinToString()}, so the feed's reels rows can't be told apart",
            )
        }
        val validators = classDefByStrings(FB_SHORTS_IFU_VALIDATOR, StringComparisonType.EQUALS)
            .flatMap { methodsHolding(it, FB_SHORTS_IFU_VALIDATOR) }
            .filter { it.name == "getName" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() }
        if (validators.isEmpty()) {
            throw PatchException("No validator names itself \"$FB_SHORTS_IFU_VALIDATOR\" any more")
        }

        val accessor = showcaseStoryTypeAccessor()
        val injector = preEofInjector()

        fillStoryModelStub(SHOWCASE_TYPE, STORY_TYPE_STUB, accessor)
        mutableClassDefBy(injector.definingClass).methods.single {
            it.name == injector.name && it.returnType == "V" && it.parameterTypes.isEmpty()
        }.skipWhenPreEofReelsHidden()
        enableStatus("feedReels")
    }
}

/**
 * The showcase unit's story type accessor, held to the evidence the rule rests on: exactly one class
 * answers the showcase type name, it declares exactly one story type accessor, the enum it answers
 * builds every reels story type, and the renderer reads the row's type through it.
 */
private fun BytecodePatchContext.showcaseStoryTypeAccessor(): Method {
    val units = unitClassesNamed(classDefByStrings(SHOWCASE_FEED_UNIT, StringComparisonType.EQUALS), SHOWCASE_FEED_UNIT)
    val unit = units.singleOrNull() ?: throw PatchException(
        "Expected one feed unit class answering getTypeName() with \"$SHOWCASE_FEED_UNIT\", found " +
            units.joinToString { it.type }.ifEmpty { "none" },
    )
    if (!AccessFlags.PUBLIC.isSet(unit.accessFlags)) {
        throw PatchException("${unit.type}, the $SHOWCASE_FEED_UNIT class, isn't public, so the extension can't call it")
    }
    val accessors = showcaseStoryTypeAccessors(unit)
    val accessor = accessors.singleOrNull() ?: throw PatchException(
        "${unit.type} has ${accessors.size} accessors of $SHOWCASE_STORY_TYPE_FIELD, expected one: " +
            accessors.joinToString { it.name },
    )
    val storyType = classDefByOrNull(accessor.returnType)
        ?: throw PatchException("The $SHOWCASE_STORY_TYPE_FIELD accessor answers ${accessor.returnType}, which isn't in this APK")
    val typesMissing = REELS_SHOWCASE_TYPES.filterNot(categoryNames(storyType)::contains)
    if (typesMissing.isNotEmpty()) {
        throw PatchException("The showcase story type has no ${typesMissing.joinToString()}, so its rows of reels can't be told apart")
    }
    val renderers = classDefByStrings(SHOWCASE_NOT_HANDLED, StringComparisonType.EQUALS)
        .flatMap { methodsHolding(it, SHOWCASE_NOT_HANDLED) }
    if (renderers.none { callsMethod(it, accessor) }) {
        throw PatchException(
            "The showcase renderer (${renderers.size} method(s) holding its complaint) no longer reads the story type " +
                "through ${unit.type}->${accessor.name}()",
        )
    }
    return accessor
}

/**
 * The pre-EOF injector, held to the evidence its hook rests on: exactly one method has its kept
 * name, it holds the adapter's name and calls the feed's tail adder, and the only other caller of
 * the tail adder is `addNewEdgeToCollection`, which the feed guard covers.
 */
private fun BytecodePatchContext.preEofInjector(): Method {
    val adders = classDefByStrings(ADD_ELEMENT_AT_TAIL, StringComparisonType.EQUALS).flatMap(::tailAdders)
    val adder = adders.singleOrNull() ?: throw PatchException(
        "Expected one (GraphQLFeedUnitEdge)Z method holding \"$ADD_ELEMENT_AT_TAIL\", found ${adders.size}",
    )

    // One walk over the whole APK finds both: the injectors by name, and every caller of the adder.
    val injectors = mutableListOf<Method>()
    val callers = mutableListOf<Method>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            if (isPreEofInjector(method)) injectors += method
            if (callsMethod(method, adder)) callers += method
        }
    }
    val injector = injectors.singleOrNull() ?: throw PatchException(
        "Expected one $PRE_EOF_INJECTOR_PREFIX*()V method, found " +
            injectors.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" },
    )
    if (!holdsString(injector, PRE_EOF_ADAPTER)) {
        throw PatchException("${injector.definingClass}->${injector.name} no longer holds \"$PRE_EOF_ADAPTER\"")
    }
    if (!callsMethod(injector, adder)) {
        throw PatchException("${injector.definingClass}->${injector.name} no longer adds its row with the feed's tail adder")
    }
    tailCallerProblem(callers, injector)?.let { throw PatchException(it) }
    return injector
}

/**
 * Returns from the pre-EOF injector before it builds anything when the extension says the row is
 * hidden. At the first instruction no local holds anything, so v0 is free, and the method is void.
 */
private fun MutableMethod.skipWhenPreEofReelsHidden() {
    requireLocals("Hide Reels in the feed", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HIDE_PRE_EOF_REELS
            move-result v0
            if-eqz v0, :keep
            return-void
        """,
        ExternalLabel("keep", getInstruction(0)),
    )
}
