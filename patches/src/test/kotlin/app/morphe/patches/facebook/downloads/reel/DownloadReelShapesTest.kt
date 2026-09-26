/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code Download any reel puts in front of the sidebar assembly, and its field lookups. */
class DownloadReelShapesTest {
    private val session = "Lcom/facebook/auth/usersession/FbUserSession;"

    /**
     * A sidebar builder whose button list comes from a helper declared to return a plain List, as
     * a build could hand it, and whose markers are a new ArrayList. The block goes in front of the
     * nop standing in for the assembly's argument moves.
     */
    private fun sidebar(): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Sidebar;",
            "build",
            listOf(ImmutableMethodParameter("Lfixture/Scope;", null, null)),
            "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(40, emptyList(), null, null),
        ),
    ).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static { }, Lfixture/Lists;->buttons()Ljava/util/List;
                move-result-object v20
                new-instance v21, Ljava/util/ArrayList;
                invoke-direct { v21 }, Ljava/util/ArrayList;-><init>()V
                nop
                const/4 v0, 0x0
                return-object v0
            """,
        )
        addInstructionsWithLabels(
            4,
            sidebarButtonBlock(
                session = 30,
                scoped = 31,
                player = 32,
                helper = "Lfixture/Sidebar;->hushfacebookDownloadButton(${session}Lfixture/Scope;Lfixture/Player;)Lfixture/Button;",
                buttons = 20,
                icon = "Lfixture/Icon;->DOWNLOAD:Lfixture/Icon;",
                marker = "Lfixture/Marker;->of(Lfixture/Icon;)Lfixture/Marker;",
                markers = 21,
            ),
            ExternalLabel("facebooks_own", getInstruction(4)),
        )
    }

    private val Instruction.call get() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    /**
     * `AbstractCollection.add` through invoke-virtual only verifies on a register the verifier
     * knows holds an AbstractCollection. A list declared as a plain List doesn't, and ART rejects
     * the whole class for it.
     */
    @Test
    fun `both adds go through the List interface`() {
        val body = sidebar().implementation!!.instructions.toList()
        val adds = body.withIndex().filter { it.value.call?.contains("->add(") == true }
        assertEquals("two adds: the button and its marker", 2, adds.size)
        for ((index, add) in adds) {
            assertEquals(Opcode.INVOKE_INTERFACE, add.opcode)
            assertEquals("Ljava/util/List;->add(Ljava/lang/Object;)Z", add.call)
            // The list is copied into the call's first register just before it.
            val copy = body[index - 1] as TwoRegisterInstruction
            assertEquals(Opcode.MOVE_OBJECT_FROM16, body[index - 1].opcode)
            assertEquals(copy.registerA, (add as FiveRegisterInstruction).registerC)
        }
        assertEquals("the button goes in the button list", 20, (body[adds[0].index - 1] as TwoRegisterInstruction).registerB)
        assertEquals("the marker goes in the marker list", 21, (body[adds[1].index - 1] as TwoRegisterInstruction).registerB)
        assertTrue(body.none { it.call?.startsWith("Ljava/util/AbstractCollection;") == true })
    }

    private fun field(name: String, type: String) = ImmutableField("Lfixture/Sidebar;", name, type, AccessFlags.PUBLIC.value, null, null, null)

    @Test
    fun `a field is picked by its type, and a missing or doubled one is named`() {
        val sessionField = field("session", session)
        val others = listOf(field("context", "Landroid/content/Context;"), field("count", "I"))
        assertSame(sessionField, fieldOfType(others + sessionField, session, "the session field"))

        val none = assertThrows(PatchException::class.java) { fieldOfType(others, session, "the session field") }
        assertTrue(none.message, none.message.orEmpty().contains("the session field"))
        assertTrue(none.message, none.message.orEmpty().contains("found 0"))

        val two = assertThrows(PatchException::class.java) {
            fieldOfType(others + sessionField + field("session2", session), session, "the session field")
        }
        assertTrue(two.message, two.message.orEmpty().contains("found 2"))
    }
}
