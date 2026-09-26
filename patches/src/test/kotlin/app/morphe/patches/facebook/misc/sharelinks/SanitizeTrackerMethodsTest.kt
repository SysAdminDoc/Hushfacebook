/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two ExternalShareTracker methods Sanitize sharing links finds by shape beside its
 * fingerprint, and the refusal naming each when a build has none of it or two.
 */
class SanitizeTrackerMethodsTest {
    private val string = "Ljava/lang/String;"
    private val integer = "Ljava/lang/Integer;"

    private fun method(name: String, vararg parameters: String, public: Boolean = true): Method = ImmutableMethod(
        "Lfixture/ShareTracker;",
        name,
        parameters.map { ImmutableMethodParameter(it, null, null) },
        string,
        if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value,
        null,
        null,
        ImmutableMethodImplementation(8, listOf(ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), null, null),
    )

    /** The main method the fingerprint finds, which neither lookup may take. */
    private val main = method("main", FB_USER_SESSION, integer, integer, string, string, "Z")

    @Test
    fun `the share link method is the public session, Integer, String one`() {
        val share = method("share", FB_USER_SESSION, integer, string)
        val hidden = method("hidden", FB_USER_SESSION, integer, string, public = false)
        assertSame(share, shareLinkTracker(listOf(main, hidden, share)))

        val none = assertThrows(PatchException::class.java) { shareLinkTracker(listOf(main, hidden)) }
        assertTrue(none.message, none.message.orEmpty().contains("/share/ link"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            shareLinkTracker(listOf(main, share, method("share2", FB_USER_SESSION, integer, string)))
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }

    @Test
    fun `the extid method takes the session second and three strings`() {
        val extid = method("extid", "Lfixture/Source;", FB_USER_SESSION, string, string, string)
        val almost = method("almost", "Lfixture/Source;", FB_USER_SESSION, string, string, integer)
        assertSame(extid, extidTracker(listOf(main, almost, extid)))

        val none = assertThrows(PatchException::class.java) { extidTracker(listOf(main, almost)) }
        assertTrue(none.message, none.message.orEmpty().contains("extid"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            extidTracker(listOf(extid, method("extid2", "Lfixture/Other;", FB_USER_SESSION, string, string, string)))
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }
}
