/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.reel

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved reel is named after its id, which the extension reads from Facebook's player params:
 * their toString has to be the extension's ID_PREFIX and then the id. Only a stub of the class in
 * the extension's tests said so. This holds every declared build to it, with the class name and the
 * prefix read from the compiled extension, so it's the class the extension treats as the params and
 * the prefix it looks for. toString's answer is followed through the helper Redex puts the
 * StringBuilder chain behind. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class VideoIdFixtureTest {
    @Test
    fun `each declared build's player params say the prefix and then their own id`() {
        val prefix = ExtensionDex.stringConstant(REEL_DOWNLOAD, "ID_PREFIX")
        val params = "L" + ExtensionDex.stringConstant(REEL_DOWNLOAD, "VIDEO_PLAYER_PARAMS").replace('.', '/') + ";"
        assertTrue("the extension's prefix is empty", prefix.isNotEmpty())
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableMapOf<String, String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val paramsClass = FixtureDex.classes(bundle, setOf(params))[params]
                    ?: throw AssertionError("${bundle.name} has no $params")
                assertTrue(
                    "${bundle.name}: $params isn't final, so a subclass could say something else",
                    AccessFlags.FINAL.isSet(paramsClass.accessFlags),
                )
                val toString = paramsClass.methods.singleOrNull {
                    it.name == "toString" && it.parameterTypes.isEmpty() && it.returnType == STRING
                } ?: throw AssertionError("${bundle.name}: $params declares no toString()")
                val helperTypes = toString.calls().filter { it.first == Opcode.INVOKE_STATIC }.map { it.second.definingClass }.toSet()
                val helpers = if (helperTypes.isEmpty()) emptyList() else FixtureDex.classes(bundle, helperTypes).values.flatMap { it.methods }

                val said = Concatenation(params, helpers).answer(toString)

                assertEquals("${bundle.name}: what toString joins, $said", 2, said.size)
                assertEquals("${bundle.name}: toString's first part", Part.Text(prefix), said[0])
                val id = said[1] as? Part.OwnField
                    ?: throw AssertionError("${bundle.name}: toString's second part is ${said[1]}, not a field of the params")
                assertTrue(
                    "${bundle.name}: ${id.name} isn't a String field of $params",
                    paramsClass.instanceFields.any { it.name == id.name && it.type == STRING },
                )
                checked[version] = id.name
            }
        }
        assertEquals("a declared build went unchecked: $checked", versions, checked.keys)
    }

    /** A part of what toString answers: text, or a String field of the params themselves. */
    private sealed interface Part {
        data class Text(val value: String) : Part
        data class OwnField(val name: String) : Part
    }

    /** Every call the method makes, with its opcode. */
    private fun Method.calls(): List<Pair<Opcode, MethodReference>> = implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { instruction.opcode to it }
    }

    /**
     * What a method answers, as the parts it joins, read along straight-line code: text, String
     * fields of [self] read from `this`, a StringBuilder's appends, and calls into [helpers].
     * Anything else stops the read, naming the instruction.
     */
    private class Concatenation(private val self: String, private val helpers: List<Method>) {
        private sealed interface Value
        private object This : Value
        private class Joined(val parts: List<Part>) : Value
        private class Builder : Value {
            val parts = mutableListOf<Part>()
        }

        fun answer(method: Method): List<Part> {
            val answer = evaluate(method, listOf(This)) as? Joined
                ?: throw AssertionError("${method.definingClass}->${method.name} doesn't answer joined text")
            // Neighbouring text reads as one, however it was split.
            val parts = mutableListOf<Part>()
            for (part in answer.parts) {
                val last = parts.lastOrNull()
                if (part is Part.Text && last is Part.Text) {
                    parts[parts.size - 1] = Part.Text(last.value + part.value)
                } else {
                    parts += part
                }
            }
            return parts
        }

        private fun evaluate(method: Method, arguments: List<Value>): Value {
            val where = "${method.definingClass}->${method.name}"
            val implementation = method.implementation ?: throw AssertionError("$where has no body")
            val registers = arrayOfNulls<Value>(implementation.registerCount)
            arguments.forEachIndexed { index, value -> registers[implementation.registerCount - arguments.size + index] = value }
            var result: Value? = null
            for (instruction in implementation.instructions) {
                val reference = (instruction as? ReferenceInstruction)?.reference
                when (instruction.opcode) {
                    Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                        registers[(instruction as OneRegisterInstruction).registerA] =
                            Joined(listOf(Part.Text((reference as StringReference).string)))
                    Opcode.IGET_OBJECT -> {
                        val read = instruction as TwoRegisterInstruction
                        val field = reference as FieldReference
                        if (registers[read.registerB] !== This || field.definingClass != self || field.type != STRING) {
                            throw AssertionError("$where reads $field, not a String field of its own params")
                        }
                        registers[read.registerA] = Joined(listOf(Part.OwnField(field.name)))
                    }
                    Opcode.NEW_INSTANCE -> {
                        if ((reference as TypeReference).type != BUILDER) throw AssertionError("$where makes a ${reference.type}")
                        registers[(instruction as OneRegisterInstruction).registerA] = Builder()
                    }
                    Opcode.INVOKE_DIRECT, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_STATIC -> {
                        val call = instruction as FiveRegisterInstruction
                        val passed = listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)
                            .take(call.registerCount).map { registers[it] }
                        result = invoke(where, reference as MethodReference, passed)
                    }
                    Opcode.MOVE_RESULT_OBJECT -> registers[(instruction as OneRegisterInstruction).registerA] = result
                    Opcode.RETURN_OBJECT -> return registers[(instruction as OneRegisterInstruction).registerA]
                        ?: throw AssertionError("$where returns something this read didn't follow")
                    else -> throw AssertionError("$where does ${instruction.opcode.name}, which this read doesn't follow")
                }
            }
            throw AssertionError("$where never returns")
        }

        private fun invoke(where: String, call: MethodReference, arguments: List<Value?>): Value? {
            val receiver = arguments.firstOrNull()
            return when {
                receiver is Builder && call.name == "<init>" -> {
                    arguments.drop(1).forEach { receiver.parts += text(where, it) }
                    null
                }
                receiver is Builder && call.name == "append" &&
                    call.parameterTypes.map { it.toString() } == listOf(STRING) -> {
                    receiver.parts += text(where, arguments[1])
                    receiver
                }
                receiver is Builder && call.name == "toString" && call.parameterTypes.isEmpty() -> Joined(receiver.parts.toList())
                else -> {
                    val helper = helpers.singleOrNull {
                        it.definingClass == call.definingClass && it.name == call.name && it.returnType == call.returnType &&
                            it.parameterTypes.map { type -> type.toString() } == call.parameterTypes.map { type -> type.toString() }
                    } ?: throw AssertionError("$where calls $call, which this read doesn't follow")
                    if (!AccessFlags.STATIC.isSet(helper.accessFlags)) throw AssertionError("$where calls $call, which isn't static")
                    evaluate(helper, arguments.map { it ?: throw AssertionError("$where passes $call something unread") })
                }
            }
        }

        private fun text(where: String, value: Value?): List<Part> =
            (value as? Joined)?.parts ?: throw AssertionError("$where appends something that isn't text")
    }

    private companion object {
        const val REEL_DOWNLOAD = "Lapp/morphe/extension/facebook/download/ReelDownload;"
        const val STRING = "Ljava/lang/String;"
        const val BUILDER = "Ljava/lang/StringBuilder;"
    }
}
