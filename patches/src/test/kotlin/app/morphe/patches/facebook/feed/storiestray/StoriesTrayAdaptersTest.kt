/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.RepoFiles
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Stories tray adapter methods of the feed's adapter configuration, as the patch picks
 * them. Each rule has a control that must fail it: the tray controller's constructor holds the
 * start and stop names too, and must never be picked.
 */
class StoriesTrayAdaptersTest {
    private val configuration = "Lcom/example/NewsFeedAdapterConfiguration;"
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    private fun method(
        name: String,
        strings: List<String>,
        flags: Int = static,
        parameters: List<String> = listOf(configuration, IMMUTABLE_LIST_BUILDER),
        returnType: String = "Lcom/example/Adapter;",
        definingClass: String = configuration,
    ): Method = ImmutableMethod(
        definingClass, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
        ImmutableMethodImplementation(
            6,
            strings.mapIndexed { i, s -> ImmutableInstruction21c(Opcode.CONST_STRING, i % 4, ImmutableStringReference(s)) } +
                listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            null, null,
        ),
    )

    private fun classOf(vararg methods: Method) =
        ImmutableClassDef(configuration, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods.toList())

    @Test
    fun `the classic adapter holds its trace name and has the adapter shape`() {
        val found = legacyTrayAdapters(classOf(
            method("A00", listOf(ADD_STORIES_ADAPTER)),
            method("A0x", listOf(ADD_STORIES_ADAPTER), flags = AccessFlags.PUBLIC.value),
            method("A0y", listOf(ADD_STORIES_ADAPTER), parameters = listOf(IMMUTABLE_LIST_BUILDER)),
            method("A0z", listOf(ADD_STORIES_ADAPTER), returnType = "V"),
            method("A02", listOf("NewsFeedAdapterConfiguration.<get-inlineComposerSectionAdapter>")),
        ))
        assertEquals(listOf("A00"), found.map { it.name })
    }

    @Test
    fun `the unified adapter holds all three names and has the adapter shape`() {
        val all = listOf(TRAY_ADAPTER_START, TOFU, TRAY_ADAPTER_STOP)
        val found = unifiedTrayAdapters(classOf(
            method("A01", all),
            method("A0a", listOf(TRAY_ADAPTER_START, TRAY_ADAPTER_STOP)),
            method("A0b", listOf(TRAY_ADAPTER_START, TOFU)),
            method("A0c", listOf(TOFU, TRAY_ADAPTER_STOP)),
            method("A0d", all, parameters = listOf("Lcom/example/Other;", IMMUTABLE_LIST_BUILDER)),
            // The tray controller's constructor: the same start and stop names, not an adapter.
            method("<init>", all, flags = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                parameters = listOf("Landroid/content/Context;"), returnType = "V"),
        ))
        assertEquals(listOf("A01"), found.map { it.name })
    }

    /** The hook passes the adapters under the numbers the extension reads them by. */
    @Test
    fun `the patch and the extension agree on the hook and the adapter numbers`() {
        val text = File(RepoFiles.root,
            "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed/FeedFilter.java").readText()
        assertTrue("the extension has no public static boolean hideStoriesTray(int)",
            Regex("""public static boolean hideStoriesTray\(int \w+\)""").containsMatchIn(text))
        assertTrue(HIDE_STORIES_TRAY.endsWith("/feed/FeedFilter;->hideStoriesTray(I)Z"))
        assertTrue("LEGACY_TRAY differs", text.contains("public static final int LEGACY_TRAY = $LEGACY_TRAY;"))
        assertTrue("UNIFIED_TRAY differs", text.contains("public static final int UNIFIED_TRAY = $UNIFIED_TRAY;"))
    }
}
