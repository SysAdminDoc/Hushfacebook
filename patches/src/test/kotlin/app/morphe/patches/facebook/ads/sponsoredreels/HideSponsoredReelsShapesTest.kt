/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.ads.sponsoredreels

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of Hide sponsored reels that need no Facebook build: which register each page filter
 * reads, whether the method is static or not, and the refusals when an anchor inside a method is
 * missing, longer than the literal, or doubled.
 */
class HideSponsoredReelsShapesTest {
    private val collection = "Ljava/util/Collection;"
    private val list = "Ljava/util/List;"
    private val withoutAds = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
        "withoutAds(Ljava/util/Collection;Ljava/lang/String;)Ljava/util/Collection;"
    private val withoutAdSections = "Lapp/morphe/extension/facebook/ads/ReelsAdFilter;->" +
        "withoutAdSections(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"
    private val settableFuture = "Lcom/google/common/util/concurrent/SettableFuture;"
    private val listenableFuture = "Lcom/google/common/util/concurrent/ListenableFuture;"

    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        static: Boolean,
        smali: String,
        definingClass: String = "Lfixture/Items;",
    ): MutableMethod = MutableMethod(
        ImmutableMethod(
            definingClass,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    ).apply { addInstructionsWithLabels(0, smali) }

    private fun MutableMethod.body(): List<Instruction> = implementation!!.instructions.toList()

    private val Instruction.call get() = ((this as ReferenceInstruction).reference as MethodReference).toString()

    /** The registers the filter call at [index] passes, and the one the answer is moved into. */
    private fun MutableMethod.filterAt(index: Int, filter: String): Pair<List<Int>, Int> {
        val body = body()
        assertEquals(filter, body[index].call)
        val call = body[index] as FiveRegisterInstruction
        assertEquals(Opcode.MOVE_RESULT_OBJECT, body[index + 1].opcode)
        return listOf(call.registerC, call.registerD) to (body[index + 1] as OneRegisterInstruction).registerA
    }

    /**
     * On both builds the page insert is an instance method and the announcement a static one.
     * Redex decides that per build, so each is checked both ways: the page is the last register
     * either way, and a p register written down for one form is another parameter in the other.
     */
    @Test
    fun `the page filter reads the Collection parameter, static or not`() {
        val cases = listOf(
            // name, parameters, return type, registers, static
            method("insert", listOf("I", collection), "Z", 6, static = false, smali = "const/4 v0, 0x0\nreturn v0"),
            method("insert", listOf("I", collection), "Z", 5, static = true, smali = "const/4 v0, 0x0\nreturn v0"),
            method("announce", listOf("Lfixture/Items;", collection), "V", 7, static = true, smali = "return-void"),
            method("announce", listOf("Lfixture/Items;", collection), "V", 8, static = false, smali = "return-void"),
        )
        for (page in cases) {
            val registers = page.implementation!!.registerCount
            page.filterPageFirst("fixture.AdItem")
            val body = page.body()
            assertEquals(Opcode.CONST_STRING, body[0].opcode)
            assertEquals("fixture.AdItem", ((body[0] as ReferenceInstruction).reference as StringReference).string)
            val (passed, answer) = page.filterAt(1, withoutAds)
            val form = if (AccessFlags.STATIC.isSet(page.accessFlags)) "static" else "instance"
            assertEquals("$form ${page.name}: the page handed to the filter", listOf(registers - 1, 0), passed)
            assertEquals("$form ${page.name}: the page the method goes on with", registers - 1, answer)
        }
    }

    /**
     * The filter call names the page in a 4-bit operand. The patcher's smali compiler leaves out a
     * call whose register doesn't fit, without a word, so a page in v16 or above used to lose its
     * filter call and keep the move-result after it.
     */
    @Test
    fun `a page kept above v15 stops the patch instead of losing its filter call`() {
        val highest = method("insert", listOf("I", collection), "Z", 16, static = true, smali = "const/4 v0, 0x0\nreturn v0")
        highest.filterPageFirst("fixture.AdItem")
        assertEquals("v15 still fits", listOf(15, 0), highest.filterAt(1, withoutAds).first)

        val above = method("insert", listOf("I", collection), "Z", 20, static = true, smali = "const/4 v0, 0x0\nreturn v0")
        val refused = assertThrows(PatchException::class.java) { above.filterPageFirst("fixture.AdItem") }
        assertTrue(refused.message, refused.message.orEmpty().contains("v19"))

        val sections = method(
            "addPage", listOf(list), "Z", 20, static = false,
            smali = "const/4 v3, 0x0\nreturn v3",
            definingClass = "Lfixture/Controller;",
        )
        assertThrows(PatchException::class.java) { sections.filterSectionsFirst("fixture.AdItem") }
    }

    @Test
    fun `a page method with no local to borrow stops the patch`() {
        val page = method("insert", listOf("I", collection), "Z", 3, static = false, smali = "return p1")
        assertThrows(PatchException::class.java) { page.filterPageFirst("fixture.AdItem") }
    }

    @Test
    fun `the sections filter reads the List parameter, static or not`() {
        for (static in listOf(false, true)) {
            val registers = if (static) 5 else 6
            val add = method(
                "addPage", listOf(list), "Z", registers, static,
                smali = "const/4 v3, 0x0\nreturn v3",
                definingClass = "Lfixture/Controller;",
            )
            add.filterSectionsFirst("fixture.AdItem")
            val (passed, answer) = add.filterAt(1, withoutAdSections)
            assertEquals("static=$static: the page handed to the filter", listOf(registers - 1, 3), passed)
            assertEquals("static=$static: the page the method goes on with", registers - 1, answer)
            assertEquals("the name goes in the local the method writes first", 3, (add.body()[0] as OneRegisterInstruction).registerA)
        }
    }

    /**
     * The borrowed register has to be a local the first instruction writes. `if-eqz` only reads its
     * register, and a `check-cast` of the page reads it and writes it back, so borrowing either
     * would hand the filter the ad type's name in place of the page.
     */
    @Test
    fun `the sections method has to start by writing a local`() {
        val reads = method(
            "addPage", listOf(list), "Z", 6, static = false,
            smali = """
                if-eqz p1, :empty
                const/4 v0, 0x1
                return v0
                :empty
                const/4 v0, 0x0
                return v0
            """,
            definingClass = "Lfixture/Controller;",
        )
        assertThrows(IllegalStateException::class.java) { reads.filterSectionsFirst("fixture.AdItem") }

        val casts = method(
            "addPage", listOf(list), "Z", 6, static = false,
            smali = """
                check-cast p1, Ljava/util/ArrayList;
                const/4 v0, 0x0
                return v0
            """,
            definingClass = "Lfixture/Controller;",
        )
        assertThrows(IllegalStateException::class.java) { casts.filterSectionsFirst("fixture.AdItem") }
    }

    private fun idle(smali: String) = method(
        "idle", listOf("Lfixture/Session;", "I"), "V", 6, static = false, smali = smali,
        definingClass = "Lfixture/IdleState;",
    ).body()

    private fun execute(from: String = "invoke-static { v0 }", literal: String = REELS_VIDEO_AD_QUERY) = """
        const-string v0, "$literal"
        $from, Lfixture/Executor;->run(Ljava/lang/String;)$settableFuture
        move-result-object v1
        return-void
    """

    @Test
    fun `the idle query's executor call is the first SettableFuture call after its literal`() {
        assertEquals(1, idleExecutorCallIndex(idle(execute()), "idle"))
    }

    /**
     * The patcher matched the fingerprint's literal by containment, so a method holding only a
     * longer name was found. Without the whole literal there is nowhere to start, and the search
     * used to start at index -1 and fail with an index error that named nothing.
     */
    @Test
    fun `a longer query name the fingerprint matched stops the patch naming the literal`() {
        val refused = assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(idle(execute(literal = "${REELS_VIDEO_AD_QUERY}V2")), "idle")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("\"$REELS_VIDEO_AD_QUERY\""))
    }

    @Test
    fun `an executor call the replacement can't stand in for stops the patch`() {
        // No SettableFuture call after the literal.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(idle("const-string v0, \"$REELS_VIDEO_AD_QUERY\"\nreturn-void"), "idle")
        }
        // Not a static call.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(
                idle(
                    """
                        const-string v0, "$REELS_VIDEO_AD_QUERY"
                        invoke-virtual { p0, v0 }, Lfixture/IdleState;->run(Ljava/lang/String;)$settableFuture
                        move-result-object v1
                        return-void
                    """,
                ),
                "idle",
            )
        }
        // Its answer isn't moved straight after.
        assertThrows(PatchException::class.java) {
            idleExecutorCallIndex(
                idle(
                    """
                        const-string v0, "$REELS_VIDEO_AD_QUERY"
                        invoke-static { v0 }, Lfixture/Executor;->run(Ljava/lang/String;)$settableFuture
                        return-void
                    """,
                ),
                "idle",
            )
        }
    }

    @Test
    fun `the fetch a log reports is the last future call before it`() {
        val fetch = method(
            "fetch", emptyList(), "V", 3, static = true,
            smali = """
                invoke-static { }, Lfixture/Api;->first()$listenableFuture
                move-result-object v0
                invoke-static { }, Lfixture/Api;->banner()$listenableFuture
                move-result-object v0
                const-string v1, "$BANNER_FETCH_LOG"
                invoke-static { }, Lfixture/Api;->after()$listenableFuture
                move-result-object v0
                return-void
            """,
        ).body()
        assertEquals("banner", fetch.futureCallBefore(BANNER_FETCH_LOG).name)

        val none = method(
            "fetch", emptyList(), "V", 3, static = true,
            smali = "const-string v1, \"$BANNER_FETCH_LOG\"\nreturn-void",
        ).body()
        val refused = assertThrows(PatchException::class.java) { none.futureCallBefore(BANNER_FETCH_LOG) }
        assertTrue(refused.message, refused.message.orEmpty().contains(BANNER_FETCH_LOG))
    }

    private fun tick(name: String, owner: String = "Lfixture/State;") = method(
        name, listOf("Lfixture/Media;", "I"), "J", 5, static = false,
        smali = "const-wide/16 v0, 0x0\nreturn-wide v0", definingClass = owner,
    )

    /**
     * One tick is the state's, none sends the walk to the parent, and two used to send it to the
     * parent as well, whose tick the state never runs.
     */
    @Test
    fun `a class declaring two ad-break ticks stops the patch`() {
        val other = method("other", listOf("Lfixture/Media;"), "J", 4, static = false, smali = "const-wide/16 v0, 0x0\nreturn-wide v0")
        val only = tick("tick")
        assertSame(only, adBreakTickAmong(listOf(other, only), "Lfixture/State;"))
        assertNull(adBreakTickAmong(listOf(other), "Lfixture/State;"))

        val refused = assertThrows(PatchException::class.java) {
            adBreakTickAmong(listOf(only, tick("tick2")), "Lfixture/State;")
        }
        assertTrue(refused.message, refused.message.orEmpty().contains("Lfixture/State;"))
        assertTrue(refused.message, refused.message.orEmpty().contains("found 2"))
    }
}
