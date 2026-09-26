/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/shared/FeedEdge.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the category is handed to the extension, which reads
 * the constant's name, so the SPONSORED field lookup in <clinit> is gone. The funnel's whole shape
 * is pinned and has to match exactly once, and each getter names what it could not find.
 */
package app.morphe.patches.facebook.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Kept GraphQL model class — Redex never renames it. */
internal const val FEED_UNIT_EDGE = "Lcom/facebook/graphql/model/GraphQLFeedUnitEdge;"

/** Kept enum class. Its fields are renamed; its constant names survive in `<clinit>`. */
internal const val FEED_STORY_CATEGORY =
    "Lcom/crossapp/graphql/facebook/enums/GraphQLFeedStoryCategory;"

/** Kept Guava class: the builder the feed collects its edges into. */
private const val IMMUTABLE_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

/** The funnel as a refusal names it. */
private const val FEED_FUNNEL = "addNewEdgeToCollection(ImmutableList\$Builder, GraphQLFeedUnitEdge, object)Z"

/**
 * `FeedUnitCollectionManager.addNewEdgeToCollection` — the single funnel every news-feed edge
 * passes through into the `FeedUnitCollection`. Redex keeps the method name, and on both builds one
 * method in all the dex files carries it.
 *
 * The whole shape is pinned, not the name alone: the builder the feed collects into, the edge, and
 * one more object whose class Redex renames every build (`"L"` takes any object type). A name and a
 * return type would also take an overload, or another collection's method of the same name, and
 * the patcher's lookup hands back the first method that fits without looking for a second. So the
 * guard asks [addNewEdgeToCollection] for it, which takes every match and wants exactly one.
 *
 * Returning false is an outcome the app already handles: it logs "Edge not added to FUC" and the
 * caller carries on.
 */
internal object AddNewEdgeToCollectionFingerprint : Fingerprint(
    name = "addNewEdgeToCollection",
    returnType = "Z",
    parameters = listOf(IMMUTABLE_LIST_BUILDER, FEED_UNIT_EDGE, "L"),
)

/**
 * The one method [AddNewEdgeToCollectionFingerprint] matches.
 *
 * With two, the guard would go on whichever the class walk met first, and every edge through the
 * other would pass unchecked with nothing to say so. A build with none or several stops the patch.
 */
internal fun BytecodePatchContext.addNewEdgeToCollection(): MutableMethod =
    AddNewEdgeToCollectionFingerprint.matchAllOrNull().orEmpty().oneFeedFunnel { it.originalMethod }.method

/** The only entry of this list, or a refusal naming the method [method] reads out of each entry. */
internal fun <T> List<T>.oneFeedFunnel(method: (T) -> Method): T =
    singleOrNull() ?: throw PatchException(
        "Feed filter: expected exactly one $FEED_FUNNEL, found $size" +
            if (isEmpty()) {
                "."
            } else {
                joinToString(prefix = ": ", postfix = ".") { entry ->
                    method(entry).run { "$definingClass->$name(${parameterTypes.joinToString("")})$returnType" }
                }
            },
    )

/**
 * The only zero-argument accessor on [FEED_UNIT_EDGE] returning the story-category enum (`B8f()`
 * here), selected by return type. It resolves through `getCachedEnum` with a default, so it never
 * returns null.
 */
internal fun BytecodePatchContext.storyCategoryGetter(): String =
    storyCategoryGetterOf(mutableClassDefBy(FEED_UNIT_EDGE).methods).name

/** The [storyCategoryGetter] among [methods], or a refusal saying which accessor is missing or doubled. */
internal fun storyCategoryGetterOf(methods: Iterable<Method>): Method =
    methods.filter { it.returnType == FEED_STORY_CATEGORY && it.parameterTypes.isEmpty() }
        .singleOrPatchException("Feed filter: GraphQLFeedUnitEdge's zero-argument getter returning GraphQLFeedStoryCategory")

/**
 * The [FEED_UNIT_EDGE] accessor returning the edge's feed unit, inflating it if the tree has not
 * materialised it yet (`BQd()` here).
 *
 * Two zero-argument methods return the feed-unit interface — the plain cached getter and this
 * wrapper, which calls it. Pick the wrapper by its `"inflateFeedUnit"` literal; it is what the
 * surrounding code calls anyway, so its cost is already paid.
 */
internal fun BytecodePatchContext.feedUnitGetter(): Method =
    feedUnitGetterOf(mutableClassDefBy(FEED_UNIT_EDGE).methods)

/** The [feedUnitGetter] among [methods], or a refusal saying which accessor is missing or doubled. */
internal fun feedUnitGetterOf(methods: Iterable<Method>): Method =
    methods.filter { method ->
        method.parameterTypes.isEmpty() &&
            method.implementation?.instructions?.any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "inflateFeedUnit"
            } == true
    }.singleOrPatchException("Feed filter: GraphQLFeedUnitEdge's zero-argument getter holding \"inflateFeedUnit\"")
