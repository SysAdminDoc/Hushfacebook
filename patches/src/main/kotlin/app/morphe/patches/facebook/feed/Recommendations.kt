/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.patches.facebook.shared.FEED_UNIT_EDGE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The GraphQL names of the flag Facebook sets on a post it recommends rather than one from someone
 * you follow, keyed the way StoryModels.kt describes.
 *
 * Read from 573, 577 and 580 (2026-09-25): GraphQLStory has one zero-argument method that asks
 * `getCachedModel` for the field keyed `recommendation_context` (0xd4bc5329) as the type tagged
 * `StoryRecommendationContext` (0xcc3b6841): `A0d()` returning `LX/41R;` in 573, `A0e()` returning
 * `LX/3zX;` in 577 and `A0f()` returning `LX/3zi;` in 580. The model's boolean
 * `is_in_feed_recommendation_story` (0x6ca2c94b) is the flag.
 */
internal const val RECOMMENDATION_CONTEXT_FIELD = "recommendation_context"
internal const val RECOMMENDATION_CONTEXT_TYPE = "StoryRecommendationContext"
internal const val RECOMMENDED_FLAG = "is_in_feed_recommendation_story"

/**
 * Kept literal. Facebook's own "hide suggested posts" filter: for a day after someone asks it to,
 * it drops every feed edge whose story carries [RECOMMENDED_FLAG]. The method holding this trace
 * name (`LX/2Rm;->CZn` in 580, `LX/2PZ;->Ca0` in 577) calls a static predicate on the edge
 * (`LX/2Rp;->A00` in 580, `LX/2Pc;->A00` in 577) that is exactly "the node is a GraphQLStory and
 * its recommendation context's flag is true". The patch holds the accessor to that predicate, so it
 * stops if Facebook's own meaning of the flag moves.
 */
internal const val HIDE_RECOMMENDATIONS_VALIDATOR = "HideRecommendationsFeedUnitValidator.isEdgeValid"

/** Every accessor [story] declares of its recommendation context. A patch wants exactly one. */
internal fun recommendationContextAccessors(story: ClassDef): List<Method> =
    storyModelAccessors(story, RECOMMENDATION_CONTEXT_FIELD, RECOMMENDATION_CONTEXT_TYPE)

/** Whether [method] loads exactly [string]. */
internal fun holdsString(method: Method, string: String): Boolean =
    method.implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

/** The methods of [classDef] that load exactly [string]. */
internal fun methodsHolding(classDef: ClassDef, string: String): List<Method> =
    classDef.methods.filter { holdsString(it, string) }

/** The static `(GraphQLFeedUnitEdge)Z` methods [validator] calls: the edge predicates it asks. */
internal fun edgePredicateCalls(validator: Method): List<MethodReference> =
    validator.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        if (!instruction.opcode.name.startsWith("invoke-static")) return@mapNotNull null
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@mapNotNull null
        call.takeIf {
            it.returnType == "Z" && it.parameterTypes.map { type -> type.toString() } == listOf(FEED_UNIT_EDGE)
        }
    }

/** The method [call] names, among [owner]'s: static, same name and prototype. */
internal fun resolveStatic(owner: ClassDef, call: MethodReference): Method? = owner.methods.firstOrNull {
    it.name == call.name && it.returnType == call.returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
        it.parameterTypes.map { type -> type.toString() } == call.parameterTypes.map { type -> type.toString() }
}

/** Whether [predicate] reads [RECOMMENDED_FLAG] through [accessor], as Facebook's own does. */
internal fun readsRecommendedFlag(predicate: Method, accessor: Method): Boolean =
    readsStoryFlag(predicate, accessor, RECOMMENDED_FLAG)
