package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The AMOLED sweep over colour literals written in code.
 *
 * <p>A `const-wide/32` carries its value as a narrow literal as well, so a dark grey kept in a
 * long matched, and the sweep put a narrow `const` in its place. ART then rejected the class it
 * sat in: on 580 with every patch on, a static initializer failed with "register v0 has type
 * IntegerConstant but expected Long (Low Half)". The width has to survive the rewrite.
 */
class AmoledColorLiteralTest {
    /** 0xFF1D1B20: opaque, every channel at most 0x2A, and five apart at most. */
    private val darkGrey = -14869728

    /** 0xFF0A2A0A: every channel dark enough, but green, so a banner that keeps its colour. */
    private val darkGreen = 0xFF0A2A0A.toInt()

    private val black = -0x1000000

    private fun palette(vararg instructions: Instruction): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lcom/example/Palette;",
            "<clinit>",
            emptyList(),
            "V",
            AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
            null,
            null,
            ImmutableMethodImplementation(2, instructions.toList(), null, null),
        ),
    )

    private fun store(opcode: Opcode, type: String) =
        ImmutableInstruction21c(opcode, 0, ImmutableFieldReference("Lcom/example/Palette;", "surface", type))

    @Test
    fun aLongColourStaysALong() {
        val method = palette(
            ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, darkGrey),
            store(Opcode.SPUT_WIDE, "J"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(1, method.blackenDarkColors())
        val written = method.implementation!!.instructions.first()
        assertEquals(Opcode.CONST_WIDE_32, written.opcode)
        assertEquals(0, (written as OneRegisterInstruction).registerA)
        assertEquals(black.toLong(), (written as WideLiteralInstruction).wideLiteral)
    }

    @Test
    fun anIntColourStaysAnInt() {
        val method = palette(
            ImmutableInstruction31i(Opcode.CONST, 0, darkGrey),
            store(Opcode.SPUT, "I"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(1, method.blackenDarkColors())
        val written = method.implementation!!.instructions.first()
        assertEquals(Opcode.CONST, written.opcode)
        assertEquals(black.toLong(), (written as WideLiteralInstruction).wideLiteral)
    }

    @Test
    fun aDarkColourWithAHueIsLeftAlone() {
        val method = palette(
            ImmutableInstruction31i(Opcode.CONST_WIDE_32, 0, darkGreen),
            store(Opcode.SPUT_WIDE, "J"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )

        assertEquals(0, method.blackenDarkColors())
        val kept = method.implementation!!.instructions.first()
        assertEquals(Opcode.CONST_WIDE_32, kept.opcode)
        assertEquals(darkGreen.toLong(), (kept as WideLiteralInstruction).wideLiteral)
    }
}
