/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.aidetected

import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.BASE_MODEL_WITH_TREE
import app.morphe.patches.facebook.feed.GRAPHQL_STORY
import app.morphe.patches.facebook.feed.TREE_JNI
import app.morphe.patches.facebook.feed.hasPublicBooleanReader
import app.morphe.patches.facebook.feed.hasPublicTypeTag
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GenAI accessor, found in the Facebook builds the bundle declares, the way the patch finds it.
 *
 * Every declared build has to carry exactly one GraphQLStory accessor of the detected-AI info, and
 * Facebook's own AI label has to read the detected flag through it and through no other accessor
 * of the same model. The members the extension reads by reflection have to be there and public.
 * Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class GenAiAccessorFixtureTest {
    private val wanted = setOf(GRAPHQL_STORY, GEN_AI_TRANSPARENCY_PLUGIN, BASE_MODEL_WITH_TREE, TREE_JNI)

    /** The wanted classes of a bundle's base APK, read one dex at a time so no APK lands on disk. */
    private fun classesOf(bundle: File): Map<String, ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (!Regex("""classes\d*\.dex""").matches(entry.name)) continue
                    val dex = DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(apk.readBytes()))
                    for (classDef in dex.classes) {
                        if (classDef.type in wanted) found.putIfAbsent(classDef.type, classDef)
                    }
                }
            }
        }
        return found
    }

    @Test
    fun `every declared build has one detected-info accessor, the one its AI label reads`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val accessorNames = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val classes = classesOf(bundle)
                fun kept(type: String) = classes[type] ?: throw AssertionError("${bundle.name} has no $type")
                val story = kept(GRAPHQL_STORY)

                val accessors = detectedInfoAccessors(story)
                assertEquals("${bundle.name}: ${accessors.map { it.name }}", 1, accessors.size)
                val accessor = accessors.single()
                accessorNames[version] = "${accessor.name}()${accessor.returnType}"

                val plugin = kept(GEN_AI_TRANSPARENCY_PLUGIN)
                assertTrue("${bundle.name}: the AI label doesn't read $DETECTED_FLAG through ${accessor.name}()",
                    plugin.methods.any { readsDetectedFlag(it, accessor) })

                // The control: every other accessor of the same model class, the self-disclosure one
                // among them, is one the label doesn't read the detected flag through.
                val siblings = story.methods.filter {
                    it.name != accessor.name && it.parameterTypes.isEmpty() && it.returnType == accessor.returnType
                }
                assertTrue("${bundle.name}: GraphQLStory has no other accessor of ${accessor.returnType} to hold " +
                    "this against", siblings.isNotEmpty())
                for (sibling in siblings) {
                    assertFalse("${bundle.name}: the label reads the detected flag through ${sibling.name}() too",
                        plugin.methods.any { readsDetectedFlag(it, sibling) })
                }

                assertTrue("${bundle.name}: no public BaseModelWithTree.getCachedBoolean(int)",
                    hasPublicBooleanReader(kept(BASE_MODEL_WITH_TREE)))
                assertTrue("${bundle.name}: no public TreeJNI.mTypeTag", hasPublicTypeTag(kept(TREE_JNI)))
            }
        }
        assertEquals("a declared build went unchecked", versions.toSet(), accessorNames.keys)
    }
}
