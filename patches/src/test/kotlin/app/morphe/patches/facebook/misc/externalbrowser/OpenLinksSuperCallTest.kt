/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Open links in external browser puts its redirect: after the browser activity's own super
 * call, picked by name and prototype rather than by coming first.
 */
class OpenLinksSuperCallTest {
    private val bundle = "Landroid/os/Bundle;"
    private val superOnCreate = "Lfixture/BaseActivity;->onCreate($bundle)V"
    private val getResources = "Landroid/view/ContextThemeWrapper;->getResources()Landroid/content/res/Resources;"
    private val redirect = "Lapp/morphe/extension/facebook/misc/ExternalBrowser;->" +
        "redirect(Landroid/app/Activity;Landroid/content/Intent;)Z"

    private fun method(name: String, parameter: String, smali: String, registers: Int = 4): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/BrowserActivity;",
            name,
            listOf(ImmutableMethodParameter(parameter, null, null)),
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun onCreate(smali: String) = method("onCreate", bundle, smali)

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    /** The two super calls both builds make, in the other order. */
    private val resourcesFirst = """
        invoke-super { p0 }, $getResources
        move-result-object v0
        invoke-super { p0, p1 }, $superOnCreate
        return-void
    """

    @Test
    fun `the host's super call is picked in either order`() {
        assertEquals(2, onCreate(resourcesFirst).ownSuperCallIndex())
        assertEquals(
            0,
            onCreate(
                """
                    invoke-super { p0, p1 }, $superOnCreate
                    invoke-super { p0 }, $getResources
                    move-result-object v0
                    return-void
                """,
            ).ownSuperCallIndex(),
        )
        assertEquals(
            0,
            onCreate("invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void").ownSuperCallIndex(),
        )
    }

    /**
     * The first super call was `getResources()` here, so the redirect used to land between that
     * call and its move-result, and before `super.onCreate`.
     */
    @Test
    fun `the redirect goes after super onCreate when another super call comes first`() {
        val method = onCreate(resourcesFirst)
        method.hookRedirect(
            loadIntent = """
                invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
                move-result-object v0
            """,
        )
        val body = method.body()
        assertEquals(getResources, body[0].call)
        assertEquals(Opcode.MOVE_RESULT_OBJECT, body[1].opcode)
        assertEquals(superOnCreate, body[2].call)
        assertEquals("Landroid/app/Activity;->getIntent()Landroid/content/Intent;", body[3].call)
        assertEquals(redirect, body[5].call)
    }

    /**
     * The redirect names p0, and onNewIntent's p1, in 4-bit operands. The patcher's smali compiler
     * leaves out a call whose register doesn't fit, without a word, so a method keeping them above
     * v15 used to lose the redirect call and keep the move-result after it.
     */
    @Test
    fun `a redirect whose parameters sit above v15 stops the patch`() {
        val getIntent = """
            invoke-virtual { p0 }, Landroid/app/Activity;->getIntent()Landroid/content/Intent;
            move-result-object v0
        """
        val highest = method("onCreate", bundle, "invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void", registers = 17)
        highest.hookRedirect(loadIntent = getIntent)
        assertEquals("p0 in v15 still fits", redirect, highest.body()[3].call)

        val above = method("onCreate", bundle, "invoke-super/range { p0 .. p1 }, $superOnCreate\nreturn-void", registers = 20)
        val refused = assertThrows(PatchException::class.java) { above.hookRedirect(loadIntent = getIntent) }
        assertTrue(refused.message, refused.message.orEmpty().contains("v18"))

        // onNewIntent names its intent, p1, as well: v16 here.
        val newIntent = method(
            "onNewIntent",
            "Landroid/content/Intent;",
            "invoke-super/range { p0 .. p1 }, Lfixture/BaseActivity;->onNewIntent(Landroid/content/Intent;)V\nreturn-void",
            registers = 17,
        )
        assertThrows(PatchException::class.java) { newIntent.hookRedirect(loadIntent = null) }
    }

    @Test
    fun `no super call of the host's own shape stops the patch`() {
        val otherPrototype = onCreate(
            """
                const/4 v0, 0x0
                invoke-super { p0, p1, v0 }, Lfixture/BaseActivity;->onCreate(${bundle}Landroid/os/PersistableBundle;)V
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { otherPrototype.ownSuperCallIndex() }
        assertTrue(refused.message, refused.message.orEmpty().contains("super.onCreate"))
        assertTrue(refused.message, refused.message.orEmpty().contains("found 0"))

        assertThrows(PatchException::class.java) {
            onCreate("invoke-super { p0 }, $getResources\nmove-result-object v0\nreturn-void").ownSuperCallIndex()
        }
    }

    @Test
    fun `two super calls of the host's shape stop the patch`() {
        val twice = onCreate(
            """
                invoke-super { p0, p1 }, $superOnCreate
                invoke-super { p0, p1 }, $superOnCreate
                return-void
            """,
        )
        val refused = assertThrows(PatchException::class.java) { twice.ownSuperCallIndex() }
        assertTrue(refused.message, refused.message.orEmpty().contains("found 2"))
    }

    @Test
    fun `the lifecycle method is picked by name and parameter, and a missing one is named`() {
        val create = onCreate("return-void")
        val overload = method("onCreate", "Landroid/os/PersistableBundle;", "return-void")
        val newIntent = method("onNewIntent", "Landroid/content/Intent;", "return-void")
        assertSame(create, lifecycleMethod(listOf(overload, create, newIntent), "Lfixture/BrowserActivity;", "onCreate", bundle))

        val refused = assertThrows(PatchException::class.java) {
            lifecycleMethod(listOf(overload, newIntent), "Lfixture/BrowserActivity;", "onCreate", bundle)
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("onCreate($bundle)"))
        assertTrue(refused.message, refused.message.orEmpty().contains("Lfixture/BrowserActivity;"))
    }
}
