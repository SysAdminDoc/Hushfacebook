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

    /** The author row the Follow check is read from: the reader holding its literal, never the dump. */
    @Test
    fun `the author row is the one reader besides the dump holding its literal`() {
        val dumper = reader("Lfixture/Dump;", "getExtraFileFromWorkerThread", WATCH_FEED_DUMP, AUTHOR_COMPONENT)
        val author = reader("Lfixture/Author;", "A1F", AUTHOR_COMPONENT)
        val render = reader("Lfixture/Row;", RENDER)
        assertEquals(author, authorRow(listOf(render, dumper, author), dumper))
        assertNull("no author row", authorRow(listOf(render, dumper), dumper))
        assertNull("two author rows", authorRow(listOf(reader("Lfixture/Row;", RENDER, AUTHOR_COMPONENT), dumper, author), dumper))
    }

    /** A method a static call can reach: its owner, name and shape, and the strings it loads. */
    private fun callee(owner: String, name: String, returnType: String, first: String, vararg strings: String,
                       static: Boolean = true): Method = ImmutableMethod(
        owner, name, listOf(ImmutableMethodParameter(first, null, null)), returnType,
        AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
        ImmutableMethodImplementation(
            2,
            strings.map {
                com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c(
                    Opcode.CONST_STRING, 0, com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference(it),
                )
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    private fun reference(method: Method) = com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference(
        method.definingClass, method.name, method.parameterTypes, method.returnType,
    )

    /** An author row whose body makes each of [calls] with invoke-static, the first as a range call. */
    private fun authorCalling(vararg calls: Method): Method = ImmutableMethod(
        "Lfixture/Author;", "A1F", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(
            2,
            calls.mapIndexed { index, call ->
                if (index == 0) {
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc(
                        Opcode.INVOKE_STATIC_RANGE, 1, 1, reference(call),
                    )
                } else {
                    com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c(
                        Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, reference(call),
                    )
                }
            } + com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(Opcode.RETURN_VOID),
            null,
            null,
        ),
    )

    private val surfaces = FOLLOW_CHECK_SURFACES.toTypedArray()
    private val builder = callee("Lfixture/Button;", "build", "Ljava/lang/Object;", FB_USER_SESSION, FOLLOW_BUTTON_KEY)
    private val check = callee("Lfixture/Check;", "offersFollow", "Z", FB_USER_SESSION, *surfaces)

    /** Every lookalike the rule has to pass over, each short of the check by one thing. */
    private val lookalikes = listOf(
        callee("Lfixture/Check;", "oneSurface", "Z", FB_USER_SESSION, surfaces.first()),
        callee("Lfixture/Check;", "sessionNotFirst", "Z", "Landroid/content/Context;", *surfaces),
        callee("Lfixture/Check;", "notABoolean", "Ljava/lang/String;", FB_USER_SESSION, *surfaces),
        callee("Lfixture/Check;", "notStatic", "Z", FB_USER_SESSION, *surfaces, static = false),
    )

    private fun resolver(vararg methods: Method): (MethodReference) -> Method? = { call ->
        methods.firstOrNull { it.definingClass == call.definingClass && it.name == call.name }
    }

    /**
     * The check the author row asks: static, a boolean, the session first and both surfaces named,
     * picked out of lookalikes that miss one of those each, wherever it sits among the row's calls.
     */
    @Test
    fun `the Follow check is the one the author row asks beside the button it builds`() {
        val resolve = resolver(builder, check, *lookalikes.toTypedArray())
        for (at in 0..lookalikes.size) {
            val calls = lookalikes.toMutableList<Method>().apply { add(at, check) } + builder
            val found = followCheck(authorCalling(*calls.toTypedArray()), resolve)
            assertNull(found.problem)
            assertEquals("the check at call $at", check, found.check)
        }
        // Asked twice, it's still one check.
        assertEquals(check, followCheck(authorCalling(check, builder, check), resolve).check)
    }

    @Test
    fun `an author row that doesn't show one Follow check beside its button stops the patch with why`() {
        val second = callee("Lfixture/Check;", "alsoOffersFollow", "Z", FB_USER_SESSION, *surfaces)
        val resolve = resolver(builder, check, second, *lookalikes.toTypedArray())

        // The button built somewhere else: the check this row asks may not decide it any more.
        val noButton = followCheck(authorCalling(check), resolve)
        assertNull(noButton.check)
        assertTrue(noButton.problem!!, noButton.problem!!.contains(FOLLOW_BUTTON_KEY))
        // No check asked.
        val none = followCheck(authorCalling(builder, *lookalikes.toTypedArray()), resolve)
        assertNull(none.check)
        assertTrue(none.problem!!, none.problem!!.contains("0 Follow checks"))
        // Two: which one decides the button is a guess.
        val two = followCheck(authorCalling(check, builder, second), resolve)
        assertNull(two.check)
        assertTrue(two.problem!!, two.problem!!.contains("2 Follow checks"))
        // Calls the patch can't resolve count for nothing.
        assertNotNull(followCheck(authorCalling(builder, check)) { null }.problem)
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
        getter.returnTrueWhen(HIDE_FOLLOWING_BUTTON)
        val body = getter.body()
        assertEquals(HIDE_FOLLOWING_BUTTON, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals("off runs Facebook's own getter", 5, getter.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers true", 1, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[4].opcode)
        assertEquals(7, body.size)

        val noLocal = method("A1c", emptyList(), "Z", 1, static = false, "const/4 p0, 0x0\nreturn p0")
        assertThrows(PatchException::class.java) { noLocal.returnTrueWhen(HIDE_FOLLOWING_BUTTON) }
    }

    /**
     * The Follow check asks first and answers no when told to, in a local of its own: the session,
     * the other arguments and the check's own answer stay where Facebook put them.
     */
    @Test
    fun `the Follow check answers false first when the extension says so`() {
        val parameters = listOf(FB_USER_SESSION, "Ljava/lang/String;", "Z")
        val check = method("A0B", parameters, "Z", 5, static = true,
            """
                const/4 v0, 0x1
                return v0
            """)
        check.returnFalseWhen(HIDE_FOLLOW_BUTTON)
        val body = check.body()
        assertEquals(HIDE_FOLLOW_BUTTON, body[0].call)
        assertEquals(Opcode.MOVE_RESULT, body[1].opcode)
        assertEquals(0, (body[1] as OneRegisterInstruction).registerA)
        assertEquals(Opcode.IF_EQZ, body[2].opcode)
        assertEquals("off runs Facebook's own check", 5, check.target(2))
        assertEquals(Opcode.CONST_4, body[3].opcode)
        assertEquals("the hook answers no", 0, (body[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN, body[4].opcode)
        assertEquals(0, (body[4] as OneRegisterInstruction).registerA)
        assertEquals(7, body.size)

        // Every register a parameter: nothing is free to hold the extension's answer.
        val noLocal = method("A0B", parameters, "Z", 3, static = true, "return p2")
        assertThrows(PatchException::class.java) { noLocal.returnFalseWhen(HIDE_FOLLOW_BUTTON) }
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
