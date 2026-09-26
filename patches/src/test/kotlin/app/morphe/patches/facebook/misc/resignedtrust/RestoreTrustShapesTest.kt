/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The code Restore screens on re-signed builds puts at the top of the signers method, on methods
 * shaped like the builds' one and on the shapes a later build could give it.
 */
class RestoreTrustShapesTest {
    private val originalSigners = "Lapp/morphe/extension/facebook/misc/FacebookSignature;->" +
        "originalSigners(Landroid/content/pm/PackageInfo;)Ljava/util/List;"

    /** The signers method: an instance method taking nothing, like `A00()` on both builds. */
    private fun signersMethod(registers: Int): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Trust;",
            "signers",
            emptyList(),
            "Lfixture/Signers;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, "const/4 v0, 0x0\nreturn-object v0") }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    /** The index a branch at [index] lands on. */
    private fun MutableMethod.target(index: Int): Int {
        val body = body()
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
    }

    private val Instruction.reference get() = (this as ReferenceInstruction).reference

    /** Both builds give the method six registers: five locals and `this` in v5. */
    @Test
    fun `the answer is read through a copy of this and Facebook's path is kept`() {
        val method = signersMethod(6)
        method.answerOriginalSigners("packageInfo", "Lfixture/Signers;")
        val body = method.body()

        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
        assertEquals(0, (body[0] as TwoRegisterInstruction).registerA)
        assertEquals("this", 5, (body[0] as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.IGET_OBJECT, body[1].opcode)
        assertEquals(0, (body[1] as TwoRegisterInstruction).registerB)
        assertEquals("packageInfo", (body[1].reference as FieldReference).name)
        assertEquals(originalSigners, (body[2].reference as MethodReference).toString())
        assertEquals(Opcode.IF_EQZ, body[4].opcode)
        assertEquals("null runs Facebook's own code", 9, method.target(4))
        assertEquals(Opcode.RETURN_OBJECT, body[8].opcode)
        assertEquals(Opcode.CONST_4, body[9].opcode)
    }

    /**
     * `iget-object` takes 4-bit registers. Read straight from `p0`, a method with twenty registers
     * has `this` in v19, which the instruction can't name. The patcher's smali compiler left the
     * read out without a word, so the injected call read a v0 nothing had written, which ART
     * rejects when it verifies the class.
     */
    @Test
    fun `a method whose this sits above v15 still gets the answer`() {
        val method = signersMethod(20)
        method.answerOriginalSigners("packageInfo", "Lfixture/Signers;")
        val body = method.body()
        assertEquals(Opcode.MOVE_OBJECT_FROM16, body[0].opcode)
        assertEquals(19, (body[0] as TwoRegisterInstruction).registerB)
        assertEquals(Opcode.IGET_OBJECT, body[1].opcode)
        assertEquals(0, (body[1] as TwoRegisterInstruction).registerB)
    }

    /**
     * v0 to v2 are borrowed. With fewer than three locals one of them is `this`, which the
     * injection overwrites before Facebook's own code reads it.
     */
    @Test
    fun `a method with fewer than three locals stops the patch`() {
        for (registers in 1..3) {
            assertThrows("$registers registers", PatchException::class.java) {
                signersMethod(registers).answerOriginalSigners("packageInfo", "Lfixture/Signers;")
            }
        }
    }
}
