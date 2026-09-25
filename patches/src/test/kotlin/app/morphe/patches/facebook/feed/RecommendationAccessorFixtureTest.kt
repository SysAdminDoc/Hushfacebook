/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.Fixtures
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recommendation accessor, found in the Facebook builds the bundle declares, the way the
 * "Hide suggested and promoted posts" patch finds it.
 *
 * Every declared build has to carry exactly one GraphQLStory accessor of the recommendation
 * context, and Facebook's own "hide suggested posts" filter has to read the flag through it and
 * through no other accessor of the same model class. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class RecommendationAccessorFixtureTest {
    @Test
    fun `every declared build has one recommendation accessor, the one Facebook's filter reads`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val story = FixtureDex.classes(bundle, setOf(GRAPHQL_STORY))[GRAPHQL_STORY]
                    ?: throw AssertionError("${bundle.name} has no $GRAPHQL_STORY")
                val accessors = recommendationContextAccessors(story)
                assertEquals("${bundle.name}: ${accessors.map { it.name }}", 1, accessors.size)
                val accessor = accessors.single()

                val validators = FixtureDex.classesHolding(bundle, HIDE_RECOMMENDATIONS_VALIDATOR)
                    .flatMap { methodsHolding(it, HIDE_RECOMMENDATIONS_VALIDATOR) }
                assertEquals("${bundle.name}: the methods holding \"$HIDE_RECOMMENDATIONS_VALIDATOR\"", 1, validators.size)
                val calls = edgePredicateCalls(validators.single())
                assertEquals("${bundle.name}: the edge predicates the validator asks", 1, calls.size)
                val owners = FixtureDex.classes(bundle, calls.map { it.definingClass }.toSet())
                val predicate = calls.single().let { call ->
                    owners[call.definingClass]?.let { resolveStatic(it, call) }
                        ?: throw AssertionError("${bundle.name}: ${calls.single()} is not a static method in the APK")
                }
                assertTrue("${bundle.name}: Facebook's filter doesn't read $RECOMMENDED_FLAG through ${accessor.name}()",
                    readsRecommendedFlag(predicate, accessor))

                // The control: every other accessor of the same model class is one the filter
                // doesn't read the flag through, the GenAI ones among them.
                val siblings = story.methods.filter {
                    it.name != accessor.name && it.parameterTypes.isEmpty() && it.returnType == accessor.returnType
                }
                assertTrue("${bundle.name}: no other accessor of ${accessor.returnType} to hold this against",
                    siblings.size > 2)
                for (sibling in siblings) {
                    assertFalse("${bundle.name}: the filter reads the flag through ${sibling.name}() too",
                        readsRecommendedFlag(predicate, sibling))
                }
                checked[version] = "${accessor.name}()${accessor.returnType} via $predicate"
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions.toSet(), checked.keys)
    }
}
