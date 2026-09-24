/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/shared/FeedEdge.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026: the category is handed to the extension, which reads
 * the constant's name, so the SPONSORED field lookup in <clinit> is gone.
 */
package app.morphe.patches.facebook.shared

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** Kept GraphQL model class — Redex never renames it. */
internal const val FEED_UNIT_EDGE = "Lcom/facebook/graphql/model/GraphQLFeedUnitEdge;"

/** Kept enum class. Its fields are renamed; its constant names survive in `<clinit>`. */
internal const val FEED_STORY_CATEGORY =
    "Lcom/crossapp/graphql/facebook/enums/GraphQLFeedStoryCategory;"

/**
 * `FeedUnitCollectionManager.addNewEdgeToCollection` — the single funnel every news-feed edge
 * passes through into the `FeedUnitCollection`. Redex keeps the method name and it is unique
 * across all 20 dex files.
 *
 * Returning false is an outcome the app already handles: it logs "Edge not added to FUC" and the
 * caller carries on.
 */
internal object AddNewEdgeToCollectionFingerprint : Fingerprint(
    name = "addNewEdgeToCollection",
    returnType = "Z",
)

/**
 * The only zero-argument accessor on [FEED_UNIT_EDGE] returning the story-category enum (`B8f()`
 * here), selected by return type. It resolves through `getCachedEnum` with a default, so it never
 * returns null.
 */
internal fun BytecodePatchContext.storyCategoryGetter(): String =
    mutableClassDefBy(FEED_UNIT_EDGE).methods.single {
        it.returnType == FEED_STORY_CATEGORY && it.parameterTypes.isEmpty()
    }.name

/**
 * The [FEED_UNIT_EDGE] accessor returning the edge's feed unit, inflating it if the tree has not
 * materialised it yet (`BQd()` here).
 *
 * Two zero-argument methods return the feed-unit interface — the plain cached getter and this
 * wrapper, which calls it. Pick the wrapper by its `"inflateFeedUnit"` literal; it is what the
 * surrounding code calls anyway, so its cost is already paid.
 */
internal fun BytecodePatchContext.feedUnitGetter(): Method =
    mutableClassDefBy(FEED_UNIT_EDGE).methods.single { method ->
        method.parameterTypes.isEmpty() &&
            method.implementation?.instructions.orEmpty().any {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string ==
                    "inflateFeedUnit"
            }
    }
