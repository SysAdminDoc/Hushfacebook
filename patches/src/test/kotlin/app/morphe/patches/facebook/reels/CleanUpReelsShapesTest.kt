/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.reels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Clean up Reels that need no Facebook build: how the Follow getter is traced out of a
 * dump shaped like each build's, what the reader rule refuses, and the code each hook puts in.
 */
class CleanUpReelsShapesTest {
    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        static: Boolean,
        smali: String,
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            "Lfixture/Host;",
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    /** A static dump method: v0 to v4 are locals, p0 the writer, p1 the config, p2 a prefix. */
    private fun dump(smali: String) = method(
        "dump", listOf("Lfixture/Writer;", "Lfixture/Config;", "Ljava/lang/String;"), "V", 8, static = true, smali,
    )

    private fun put(name: String, getter: String) = """
        invoke-virtual { p1 }, Lfixture/Config;->$getter()Z
        move-result v0
        invoke-static { v0 }, Ljava/lang/String;->valueOf(Z)Ljava/lang/String;
        move-result-object v1
        const-string v0, "$name"
        invoke-virtual { p0, v0, v1 }, Lfixture/Writer;->put(Ljava/lang/String;Ljava/lang/String;)V
    """

    /**
     * 580: the getter, its answer as text, then the name, then the write. The next name's getter
     * sits two instructions after this name, closer than its own four before, and isn't picked.
     */
    @Test
    fun `the value written beside the name is traced to its own getter`() {
        val dump = dump(
            put("getUDDConfig.resumePlayerOnBottomSheetDismiss", "before") +
                put("getUDDConfig.removeFollowingButton", "follow") +
                put("getUDDConfig.smallUiFootprintHideMore", "after") +
                put("getPlayerTabGrowthConfig.removeFollowingButton", "follow") +
                "return-void",
        )
        val found = followButtonGetter(dump)
        assertNull(found.problem)
        assertEquals("follow", found.getter!!.name)
        assertEquals(2, found.literals)
    }

    /** 577: a prefix joined to ".removeFollowingButton" first, then the getter, then the write. */
    @Test
    fun `a name built from the literal is followed to the write`() {
        val dump = dump(
            """
                move-object v4, p2
                const-string v2, ".removeFollowingButton"
                move-object v3, v2
                invoke-static { v4, v3 }, Lfixture/Text;->join(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v3
                invoke-virtual { p1 }, Lfixture/Config;->follow()Z
                move-result v2
                invoke-static { p0, v3, v2 }, Lfixture/Writer;->putBoolean(Lfixture/Writer;Ljava/lang/String;Z)V
                invoke-virtual { p1 }, Lfixture/Config;->after()Z
                move-result v2
                return-void
            """,
        )
        val found = followButtonGetter(dump)
        assertNull(found.problem)
        assertEquals("follow", found.getter!!.name)
        assertEquals(1, found.literals)
    }

    @Test
    fun `a dump the trace can't follow stops the patch with why`() {
        // No name at all.
        assertNotNull(followButtonGetter(dump(put("getUDDConfig.somethingElse", "other") + "return-void")).problem)
        // The value written is a constant, not a getter's answer.
        assertNotNull(followButtonGetter(dump(
            """
                const-string v1, "true"
                const-string v0, "getUDDConfig.removeFollowingButton"
                invoke-virtual { p0, v0, v1 }, Lfixture/Writer;->put(Ljava/lang/String;Ljava/lang/String;)V
                return-void
            """,
        )).problem)
        // Two names, two different getters: which one is the button's is a guess.
        val two = followButtonGetter(dump(
            put("getUDDConfig.removeFollowingButton", "follow") +
                put("getPlayerTabGrowthConfig.removeFollowingButton", "other") + "return-void",
        ))
        assertNull(two.getter)
        assertTrue(two.problem!!, two.problem!!.contains("2 getters"))
    }

    private fun reader(definingClass: String, name: String, vararg strings: String): Method = ImmutableMethod(
        definingClass, name, emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(
            1,
            strings.map {
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(it),
                )
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    @Test
    fun `the Follow getter's readers have to be the three known ones`() {
        val dumper = reader("Lfixture/Dump;", "getExtraFileFromWorkerThread", WATCH_FEED_DUMP)
        val author = reader("Lfixture/Author;", "A1F", AUTHOR_COMPONENT)
        val render = reader("Lfixture/Row;", RENDER)
        assertNull(followReaderProblem(listOf(render, dumper, author), dumper))

        val fourth = reader("Lfixture/Other;", "somewhere")
        assertNotNull("a fourth reader", followReaderProblem(listOf(render, dumper, author, fourth), dumper))
        assertNotNull("no dump", followReaderProblem(listOf(render, author, fourth), dumper))
        assertNotNull("no author literal", followReaderProblem(listOf(render, dumper, fourth), dumper))
        assertNotNull("not a render", followReaderProblem(listOf(fourth, dumper, author), dumper))
        assertNotNull("two author rows", followReaderProblem(
            listOf(reader("Lfixture/Row;", RENDER, AUTHOR_COMPONENT), dumper, author), dumper))
    }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    /** The index a branch at [index] lands on. */
    private fun MutableMethod.target(index: Int): Int {
        val body = body()
        var address = 0
        val addresses = body.map { instruction -> address.also { address += instruction.codeUnits } }
        return addresses.indexOf(addresses[index] + (body[index] as OffsetInstruction).codeOffset)
    }

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    /**
     * Every return gets the filter, one reached by a goto included, and the filter's answer goes in
     * a register of its own: null keeps the list, an array becomes Facebook's own ImmutableList.
     */
    @Test
    fun `every return of the chip list goes through the filter`() {
        val builder = method(
            "build", listOf("Ljava/util/List;", "Z"), IMMUTABLE_LIST, 4, static = true,
            """
                if-eqz p1, :empty
                invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
                move-result-object v0
                goto :done
                :empty
                invoke-static { }, $IMMUTABLE_LIST->of()$IMMUTABLE_LIST
                move-result-object v0
                return-object v0
                :done
                return-object v0
            """,
        )
        builder.filterChipsBeforeEveryReturn()
        val body = builder.body()
        val returns = body.indices.filter { body[it].opcode == Opcode.RETURN_OBJECT }
        assertEquals(2, returns.size)
        for (at in returns) {
            val hook = at - 6
            assertEquals(FILTER_CHIPS, body[hook].call)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, body[hook + 1].opcode)
            val answer = (body[hook + 1] as OneRegisterInstruction).registerA
            assertTrue("the answer overwrote the list", answer != 0)
            assertEquals(Opcode.IF_EQZ, body[hook + 2].opcode)
            assertEquals("null keeps the list", hook + 5, builder.target(hook + 2))
            assertEquals(Opcode.NOP, body[hook + 5].opcode)
            assertEquals(COPY_OF, body[hook + 3].call)
            assertEquals(0, (body[hook + 4] as OneRegisterInstruction).registerA)
        }
        val goto = body.indexOfFirst { it.opcode == Opcode.GOTO }
        assertEquals("the goto skipped the filter", returns.last() - 6, builder.target(goto))
    }

    @Test
    fun `a chip list with no spare local stops the patch`() {
        val builder = method("build", listOf("Ljava/util/List;"), IMMUTABLE_LIST, 2, static = true,
            """
                invoke-static { p0 }, $IMMUTABLE_LIST->copyOf(Ljava/util/Collection;)$IMMUTABLE_LIST
                move-result-object v0
                return-object v0
            """)
        assertThrows(PatchException::class.java) { builder.filterChipsBeforeEveryReturn() }
        val none = method("build", listOf("Ljava/util/List;"), IMMUTABLE_LIST, 2, static = true, "throw p0")
        assertThrows(PatchException::class.java) { none.filterChipsBeforeEveryReturn() }
    }

    @Test
    fun `the Follow getter answers true first when the extension says so`() {
        val getter = method("A1c", emptyList(), "Z", 2, static = false,
            """
                const/4 v0, 0x0
                return v0
            """)
        getter.returnTrueWhen(HIDE_FOLLOW_BUTTON)
        val body = getter.body()
        assertEquals(HIDE_FOLLOW_BUTTON, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals("off runs Facebook's own getter", 5, getter.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers true", 1, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[4].opcode)
        assertEquals(7, body.size)

        val noLocal = method("A1c", emptyList(), "Z", 1, static = false, "const/4 p0, 0x0\nreturn p0")
        assertThrows(PatchException::class.java) { noLocal.returnTrueWhen(HIDE_FOLLOW_BUTTON) }
    }

    @Test
    fun `a footer runnable returns first when the extension says so`() {
        val run = method("run", emptyList(), "V", 2, static = false,
            """
                sget-object v0, Ljava/lang/System;->out:Ljava/io/PrintStream;
                return-void
            """)
        run.returnVoidWhen(SKIP_HOT_COMMENT)
        val body = run.body()
        assertEquals(SKIP_HOT_COMMENT, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals("off runs Facebook's query", 4, run.target(2))
        assertEquals(Opcode.RETURN_VOID, body[3].opcode)
        assertEquals(Opcode.SGET_OBJECT, body[4].opcode)
    }
}
