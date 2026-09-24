/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/hidesponsoredreels/HideSponsoredReelsPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.returnEarly
import app.morphe.util.superclassChain
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.util.MethodUtil
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val GRAPHQL_STORY = "Lcom/facebook/graphql/model/GraphQLStory;"
private const val COLLECTION = "Ljava/util/Collection;"
private const val LIST = "Ljava/util/List;"
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val SETTABLE_FUTURE = "Lcom/google/common/util/concurrent/SettableFuture;"

private const val FILTER = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
    "withoutAds(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"

private const val SECTION_FILTER = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
    "withoutAdSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

@Suppress("unused")
val hideSponsoredReelsPatch = bytecodePatch(
    name = "Hide sponsored reels",
    description = "Removes ads from Reels and Watch, including product banners over a reel and " +
        "ads inside a video.",
    default = true,
) {
    category("Ads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    execute {
        // Facebook does not splice ads into Reels on the device. A logging build showed items
        // reaching the collection only as whole fetched pages, never one at a time, with the ad
        // already in the page beside the organic reels -- the server inlines it. So the page
        // arriving at the collection is the only place left to drop it, the same way the news feed
        // patch rejects an edge rather than trying to prevent its insertion.
        //
        // Nothing obfuscated is named. The task that inserts an SFD ad carries a trace literal, and
        // its constructor is handed both the collection and the ad item -- so one string anchor
        // yields both classes, each picked out by a shape no sibling parameter shares.
        val adTask = SfdAdInsertFingerprint.method.definingClass
        val taken = mutableClassDefBy(adTask).methods
            .single { it.name == "<init>" }
            .parameterTypes
            .map { it.toString() }

        fun pick(what: String, matches: (List<String>) -> Boolean) = taken
            .filter { parameter ->
                mutableClassDefByOrNull(parameter)
                    ?.methods
                    ?.any { matches(listOf(it.returnType) + it.parameterTypes.map(CharSequence::toString)) }
                    ?: false
            }
            .also { check(it.size == 1) { "Expected 1 $what among ${taken.joinToString()}, found $it" } }
            .single()

        // The ad item base: the only one of them that can hand back a story.
        val adBase = pick("ad item type") { shape -> shape == listOf(GRAPHQL_STORY) }

        // The item collection: the only one of them that takes a whole collection and answers.
        val collection = pick("item collection") { shape -> shape == listOf("Z", COLLECTION) }

        val collectionClass = mutableClassDefBy(collection)

        // The method that actually puts the page into the backing list: it takes the position to
        // insert at as well as the page. Its sibling only walks the page afterwards telling
        // listeners about each item, which is why filtering that one alone removed nothing.
        val insertPage = collectionClass.methods.single {
            it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("I", COLLECTION)
        }

        // The listener walk. Filtered too, so nothing is announced that was just dropped.
        val announcePage = collectionClass.methods.single {
            it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(collection, COLLECTION)
        }

        // Replace the incoming page with one that has no ads in it. The helper hands back the very
        // same collection when it holds none, so the usual page is untouched and keeps its type.
        listOf(insertPage to "p2", announcePage to "p1").forEach { (method, page) ->
            method.requireLocals("Hide sponsored reels", 1)
            method.addInstructions(
                0,
                """
                    const-string v0, "${adBase.toBinaryName()}"
                    invoke-static { $page, v0 }, $FILTER
                    move-result-object $page
                """,
            )
        }

        // The filter on the collection is not enough. A device round on 2026-09-19 showed why. It
        // caught an ad in a page and logged the drop. The app then showed that ad as the third
        // reel. One step earlier, the controller adds a section wrapper. The wrapper holds its own
        // list of items, and the screen reads that list. Thus the ad stayed in the wrapper, and
        // the drop count in the log stayed correct.
        //
        // So the same filter also runs one level up, over the sections that the controller gets.
        // Both levels stay. This level reaches the screen. The collection level still covers
        // anything that enters the list by another route.
        val controller = mutableClassDefBy(VideoHomeInsertAdsFingerprint.method.definingClass)

        // A page of fetched sections: one List in, boolean out.
        val addPage = controller.methods.single {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf(LIST)
        }

        addPage.addInstructions(
            0,
            """
                const-string ${addPage.scratch()}, "${adBase.toBinaryName()}"
                invoke-static { p1, ${addPage.scratch()} }, $SECTION_FILTER
                move-result-object p1
            """,
        )

        // The client-side insert paths stay blocked. None of them fired in the logging run, but
        // they are what the app would use if a future release went back to inserting on the device,
        // and blocking them costs nothing.
        //
        // Only the inserts are blocked, not the requests that feed them. Stopping those would save
        // data, but belongs with the prefetch patch, and they have not been checked for organic
        // side effects.
        listOf(
            VideoHomeInsertAdsFingerprint,
            RealtimeIntentAdInsertFingerprint,
            SfdAdInsertFingerprint,
            PoeAdRenderFingerprint,
        ).forEach { it.method.returnEarly() }

        // Banners over a reel and mid-rolls are fetched while the reel plays, not in the page, so
        // the filters cannot see them. Their fetches return a failed future instead. Each caller
        // handles it as a network error: it stores no ad, and no query goes out.
        val adBreakFetch = AdBreakFetchFingerprint.method.instructions()

        // The banner helper. All banner requests go through it.
        val bannerFetch = adBreakFetch.futureCallBefore(BANNER_FETCH_LOG)
        mutableClassDefBy(bannerFetch.definingClass).methods
            .single { MethodUtil.methodSignaturesMatch(it, bannerFetch) }
            .let(::returnFailedFuture)

        // The ad-break server API. Each of its methods that returns a future is an ad query.
        val videoFetcher = adBreakFetch.futureCallBefore(VIDEO_FETCH_LOG).definingClass
        mutableClassDefBy(videoFetcher).methods
            .filter { it.returnType == LISTENABLE_FUTURE }
            .also { check(it.isNotEmpty()) { "$videoFetcher has no method that returns a future" } }
            .forEach(::returnFailedFuture)

        // The idle state runs its own video ad query on the GraphQL executor, which the whole app
        // shares. Thus only this one executor call changes. The helper call has the same size and
        // no arguments, so the next move-result gets the failed future.
        val idleVideoFetch = ReelsVideoAdQueryFingerprint.method
        val idleInstructions = idleVideoFetch.instructions()
        val queryIndex = idleInstructions.indexOfFirst { it.string == REELS_VIDEO_AD_QUERY }
        val executeIndex = (queryIndex until idleInstructions.size).first { index ->
            idleInstructions[index].methodReference?.returnType == SETTABLE_FUTURE
        }
        check(idleInstructions[executeIndex].opcode == Opcode.INVOKE_STATIC) {
            "The executor call at $executeIndex is not invoke-static"
        }
        check(idleInstructions[executeIndex + 1].opcode == Opcode.MOVE_RESULT_OBJECT) {
            "The executor call at $executeIndex has no move-result-object"
        }
        idleVideoFetch.replaceInstruction(
            executeIndex,
            "invoke-static { }, ${failedFutureOn(idleVideoFetch.definingClass)}",
        )

        // The ad-break lookup retries a failed fetch each second while the reel plays. Its tick
        // returns -1, the value for a reel with no media, which stops the poller. The lookup then
        // never starts.
        stopDeferredCardPoller()

        enableStatus("sponsoredReels")
    }
}

/**
 * Makes the tick of the deferred-card ad-break state answer -1 ("no media"), so its poller stops.
 *
 * <p>577 names each state in the state's own class, so the class holding the literal is the state
 * and declares the tick. 580 names every state from one method on the abstract base, by
 * `instance-of`, and the tick moved up into a parent the deferred-card state shares with two
 * other states. There the state is the type tested just before the literal, and the tick answers
 * -1 only for an instance of it, so the other states keep their own behaviour.
 */
private fun BytecodePatchContext.stopDeferredCardPoller() {
    val nameMethod = UnresolvedAdStateFingerprint.method
    val instructions = nameMethod.instructions()
    val literal = instructions.indexOfFirst { it.string == DEFERRED_CARD_STATE }
    check(literal >= 0) { "\"$DEFERRED_CARD_STATE\" is not in ${nameMethod.definingClass}->${nameMethod.name}" }

    // The type tested just before the literal, when the name comes from an instance-of chain.
    val tested = (maxOf(0, literal - 3) until literal).reversed()
        .firstNotNullOfOrNull { index ->
            instructions[index].takeIf { it.opcode == Opcode.INSTANCE_OF }
                ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        }
    val state = tested ?: nameMethod.definingClass

    fun isTick(method: MutableMethod) = method.returnType == "J" && method.parameterTypes.size == 2 &&
        method.parameterTypes[1].toString() == "I" && method.implementation != null

    // The tick the state runs: its own, or the nearest one it inherits.
    val tick = superclassChain(state)
        .mapNotNull { mutableClassDefByOrNull(it) }
        .firstNotNullOfOrNull { classDef -> classDef.methods.singleOrNull(::isTick) }
        ?: error("No class of $state's hierarchy declares an ad-break tick")

    if (tick.definingClass == state) {
        tick.returnEarly(-1L)
        return
    }

    // Shared with sibling states: answer -1 only for the deferred-card one. Index 0, where no
    // local holds anything yet; this is copied down because instance-of takes 4-bit registers.
    tick.requireLocals("Hide sponsored reels", 2)
    tick.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p0
            instance-of v0, v0, $state
            if-eqz v0, :other_state
            const-wide/16 v0, -0x1
            return-wide v0
        """,
        ExternalLabel("other_state", tick.getInstruction(0)),
    )
}

/** The state whose poller the lookup keeps retrying. */
private const val DEFERRED_CARD_STATE = "UnresolvedWithDeferredCardState"

private const val FAILED_FUTURE = "failedAdFetch"

/** Adds, once per class, a static method that returns a failed future. */
private fun BytecodePatchContext.failedFutureOn(classType: String): String {
    val reference = "$classType->$FAILED_FUTURE()$SETTABLE_FUTURE"
    val classDef = mutableClassDefBy(classType)
    if (classDef.methods.any { it.name == FAILED_FUTURE }) return reference

    ImmutableMethod(
        classType,
        FAILED_FUTURE,
        emptyList(),
        SETTABLE_FUTURE,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(2),
    ).toMutable().apply {
        addInstructions(
            0,
            """
                new-instance v0, Ljava/io/IOException;
                const-string v1, "Reels ad fetch blocked"
                invoke-direct { v0, v1 }, Ljava/io/IOException;-><init>(Ljava/lang/String;)V
                invoke-static { }, $SETTABLE_FUTURE->create()$SETTABLE_FUTURE
                move-result-object v1
                invoke-virtual { v1, v0 }, $SETTABLE_FUTURE->setException(Ljava/lang/Throwable;)Z
                return-object v1
            """,
        )
        classDef.methods.add(this)
    }

    return reference
}

/** Makes [method] return the failed future. It can write v0, because it returns at once. */
private fun BytecodePatchContext.returnFailedFuture(method: MutableMethod) =
    method.addInstructions(
        0,
        """
            invoke-static { }, ${failedFutureOn(method.definingClass)}
            move-result-object v0
            return-object v0
        """,
    )

/** The last call before [log] that returns a future. This is the fetch that the log reports. */
private fun List<Instruction>.futureCallBefore(log: String): MethodReference {
    val logIndex = indexOfFirst { it.string == log }
    check(logIndex >= 0) { "\"$log\" is not in the ad-break fetch" }

    return take(logIndex)
        .mapNotNull { it.methodReference }
        .last { it.returnType == LISTENABLE_FUTURE }
}

private val Instruction.methodReference
    get() = (this as? ReferenceInstruction)?.reference as? MethodReference

private val Instruction.string
    get() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** `LX/B89;` as the runtime reports it: `X.B89`. */
private fun String.toBinaryName() = removePrefix("L").removeSuffix(";").replace('/', '.')

/**
 * A register safe to borrow at the top of the method: the one its own first instruction overwrites.
 *
 * Nothing can read that register before the original code writes it. Thus an injected call can use
 * it and change nothing downstream. The two collection-level injections borrow v0, which is sound
 * because those methods are known to hold locals. The controller page method is not, so this one
 * resolves the register instead of an assumption.
 */
private fun MutableMethod.scratch(): String {
    val first = instructions().first()
    check(first is OneRegisterInstruction) {
        "$definingClass->$name starts with ${first.opcode.name}, which writes no register to borrow"
    }

    val register = first.registerA
    check(register < 16) { "$definingClass->$name: scratch register v$register is out of range" }

    return "v$register"
}

private fun MutableMethod.instructions(): List<Instruction> =
    implementation?.instructions?.toList()
        ?: throw IllegalStateException("$definingClass->$name has no body")
