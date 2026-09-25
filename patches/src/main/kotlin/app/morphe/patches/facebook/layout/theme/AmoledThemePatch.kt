/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/amoledtheme/AmoledThemePatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.AccessFlags
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.w3c.dom.Element
import app.morphe.patches.facebook.misc.settings.settingsPatch

/** The largest value a channel can have and still count as a background. */
private const val MAX_CHANNEL = 0x2A

/**
 * The largest difference between the channels of a background. A grey has almost none. A dark green
 * or dark brown banner has much more, and it keeps its colour.
 */
private const val MAX_SPREAD = 8

/** Opaque black, as the signed int that a colour holds. */
private const val BLACK = -0x1000000

/** Gets the resolved colour and its token. Gives the colour to draw. */
internal const val APPLY = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->apply(ILjava/lang/Object;)I"

/** The framework call that turns a colour string, such as `"#FF252728"`, into a colour. */
internal const val PARSE_COLOR = "Landroid/graphics/Color;->parseColor(Ljava/lang/String;)I"

/** The extension call that replaces it. It has the same signature. */
internal const val PARSE_COLOR_DARK = "Lapp/morphe/extension/facebook/theme/AmoledTheme;->parseColor(Ljava/lang/String;)I"

/** The classes of the extension. Route four skips them, because the replacement calls the original. */
internal const val EXTENSION_PACKAGE = "Lapp/morphe/extension/"

/**
 * True for a dark grey, which is what a background uses. False for a dark colour with a hue, which
 * is a banner and keeps its colour. The extension holds the same rule for the colours it gets.
 */
private fun isDarkNeutral(red: Int, green: Int, blue: Int): Boolean {
    val high = maxOf(red, green, blue)
    return high <= MAX_CHANNEL && high - minOf(red, green, blue) <= MAX_SPREAD
}

/**
 * A colour reaches the screen by four routes, and this patch covers all four with one rule.
 *
 * The first route is a resolver: a component asks the design system, and the bytecode half hooks
 * the four methods that answer. The second is a resource: a view reads a colour by id, so no int
 * passes a hook, and the resource half below rewrites it. The third is a literal written in code,
 * which the bytecode half rewrites in place. The fourth is a string that the server sends, which
 * the app parses with `Color.parseColor`. The bytecode half sends each of those calls through the
 * extension.
 *
 * Routes two, three and four match on the **value** or on a framework call, with no class, method
 * or resource name.
 * Facebook strips resource names and renames its classes about every two weeks, so a name is not an
 * anchor here. A value of this exact shape is a colour and nothing else.
 */
private val amoledThemeResourcePatch = resourcePatch {
    execute {
        var changed = 0

        listOf("res/values/colors.xml", "res/values-night/colors.xml")
            .filter { get(it, false).exists() }
            .forEach { path ->
                document(path).use { document ->
                    val colors = document.getElementsByTagName("color")
                    for (index in 0 until colors.length) {
                        val color = colors.item(index) as? Element ?: continue
                        if (!isDarkBackground(color.textContent)) continue
                        color.textContent = "#ff000000"
                        changed++
                    }
                }
            }

        check(changed > 0) { "No dark colour resource found, so the theme would stay grey" }
    }
}

/**
 * True when this resource value is an opaque dark grey. A reference such as `@color/foo` gives
 * false, and so does a translucent value, which is a scrim and not a background.
 */
private fun isDarkBackground(value: String): Boolean {
    val hex = value.trim().removePrefix("#")
    if (hex.length !in setOf(3, 4, 6, 8)) return false
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false

    // Widen #rgb and #argb to two digits for each channel.
    val wide = if (hex.length <= 4) hex.map { "$it$it" }.joinToString("") else hex
    if (wide.length == 8 && wide.take(2).toInt(16) != 0xFF) return false

    val (red, green, blue) = wide.takeLast(6).chunked(2).map { it.toInt(16) }
    return isDarkNeutral(red, green, blue)
}

@Suppress("unused")
val amoledThemePatch = bytecodePatch(
    name = "AMOLED black theme",
    description = "Makes Facebook's dark mode black instead of dark grey. Turn on dark mode in " +
        "Facebook first.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(amoledThemeResourcePatch)

    dependsOn(facebookExtensionPatch)

    execute {
        // Route one. Four methods and six returns. Each hook keeps the body of the method and
        // sends the value through the extension before the method returns it.
        DarkSchemeResolveFingerprint.method.hookColorReturns(tokenParameterIndex = 0, target = APPLY)
        hookFdsColorsResolvers(target = APPLY)
        fdsViewResolver().hookColorReturns(tokenParameterIndex = 1, target = APPLY)

        // Route three. The palette tables, the top bar of the feed, the system bars and each Litho
        // component that draws its own chrome all write a colour instead of asking for one, so no
        // resolver and no resource reaches them. The sweep reads every class and rewrites only the
        // classes that hold one, which takes about 30 seconds.
        val owners = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.methods.any { it.hasDarkColor() }) owners += classDef.type
        }

        val rewritten = owners.sumOf { type ->
            mutableClassDefByOrNull(type)?.methods?.sumOf { it.blackenDarkColors() } ?: 0
        }
        check(rewritten > 0) { "No dark colour written in code, so the chrome would stay grey" }

        // Route four. The server sends some colours as strings, and the app parses them with
        // Color.parseColor. Each of those calls goes to the extension instead.
        val parsers = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
            if (classDef.methods.any { it.callsParseColor() }) parsers += classDef.type
        }

        val rerouted = parsers.sumOf { type ->
            mutableClassDefByOrNull(type)?.methods?.sumOf { it.rerouteParseColor() } ?: 0
        }
        check(rerouted > 0) { "No call to Color.parseColor found, so server colours would stay grey" }

        enableStatus("amoledTheme")
    }
}

/**
 * The resolver of the view code, which takes a Context and the FDS token. It has a Redex name, thus
 * its descriptor comes from the wrapper in `FdsColorScheme` that calls it.
 */
internal fun BytecodePatchContext.fdsViewResolver(): MutableMethod {
    val resolverCall = FdsSchemeResolveFingerprint.instructionMatches[1].instruction
    val resolver = (resolverCall as ReferenceInstruction).reference as MethodReference

    // If Facebook moves the resolver into the wrapper, this reaches 1 caller and not 894.
    check(resolver.definingClass.toString() != FDS_COLOR_SCHEME) {
        "The FDS colour resolver is now inside FdsColorScheme. Find the seam again."
    }

    return mutableClassDefBy(resolver.definingClass.toString()).methods.single {
        it.name == resolver.name &&
            it.returnType == "I" &&
            it.parameterTypes.map(CharSequence::toString) ==
            resolver.parameterTypes.map(CharSequence::toString)
    }
}

/**
 * Hooks every FDS colour resolver on `FDSColors`: each instance method answering an int that takes
 * the colour token and asks the Integer-answering source itself.
 *
 * <p>577 had two of them, one for Litho and one from a Context. 580 split the Litho one into a
 * thin wrapper and a second Context resolver, so a fingerprint per resolver matched the wrong one
 * or none. The token type is read off the source's own signature, the one static method answering
 * `Integer` from a Context, the token and a palette.
 */
internal fun BytecodePatchContext.hookFdsColorsResolvers(target: String) {
    val colors = mutableClassDefBy(FDS_COLORS)
    val source = colors.methods.singleOrNull { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
            method.parameterTypes.size == 3 && method.parameterTypes[0].toString() == "Landroid/content/Context;"
    } ?: error("FDSColors has no single Integer colour source taking a Context, a token and a palette")
    val tokenType = source.parameterTypes[1].toString()

    val resolvers = colors.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
            method.parameterTypes.any { it.toString() == tokenType } &&
            method.implementation?.instructions?.any { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                reference != null && reference.definingClass == FDS_COLORS && reference.returnType == "Ljava/lang/Integer;"
            } == true
    }
    check(resolvers.isNotEmpty()) { "No FDSColors resolver asks the Integer colour source" }
    resolvers.forEach { method ->
        method.hookColorReturns(
            tokenParameterIndex = method.parameterTypes.indexOfFirst { it.toString() == tokenType },
            target = target,
        )
    }
}

/** True when this instruction writes an opaque dark grey. */
private fun Instruction.isDarkColor(): Boolean {
    if (this !is NarrowLiteralInstruction || this !is OneRegisterInstruction) return false
    if (narrowLiteral == BLACK || (narrowLiteral ushr 24) != 0xFF) return false
    return isDarkNeutral(
        (narrowLiteral shr 16) and 0xFF,
        (narrowLiteral shr 8) and 0xFF,
        narrowLiteral and 0xFF,
    )
}

/** True when this instruction calls `Color.parseColor`. */
internal fun Instruction.isParseColorCall(): Boolean =
    (this as? ReferenceInstruction)?.reference?.toString() == PARSE_COLOR

/** True when this method calls `Color.parseColor`. It only reads, thus it needs no proxy. */
private fun Method.callsParseColor(): Boolean =
    implementation?.instructions?.any { it.isParseColorCall() } == true

/**
 * Sends each call to `Color.parseColor` in this method to the extension. Answers how many it sent.
 *
 * The replacement has the same signature and reads the same register, so the `move-result` that
 * follows stays correct. A call in the range form stays in the range form, because its register
 * can be above v15.
 */
private fun MutableMethod.rerouteParseColor(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.isParseColorCall() }

    sites.asReversed().forEach { (index, instruction) ->
        val call = when (instruction) {
            is RegisterRangeInstruction ->
                "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister} }, " +
                    PARSE_COLOR_DARK
            is FiveRegisterInstruction -> "invoke-static { v${instruction.registerC} }, $PARSE_COLOR_DARK"
            else -> error("$definingClass->$name: unexpected call form ${instruction.opcode}")
        }
        replaceInstruction(index, call)
    }
    return sites.size
}

/** True when this method writes a dark grey. It only reads, thus it needs no proxy of the class. */
private fun Method.hasDarkColor(): Boolean =
    implementation?.instructions?.any { it.isDarkColor() } == true

/**
 * Replaces each dark grey that this method writes with black. Answers how many it replaced.
 *
 * A `const-wide/32` carries its value as a narrow literal too, so a colour kept in a long matches.
 * It stays a `const-wide/32`: a narrow `const` in its place leaves the long's upper register
 * unset, and ART rejects the whole class ("register v0 has type IntegerConstant but expected
 * Long (Low Half)", a static initializer on 580 with every patch on).
 */
internal fun MutableMethod.blackenDarkColors(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.isDarkColor() }
        .map { Triple(it.index, (it.value as OneRegisterInstruction).registerA, it.value.opcode) }

    sites.asReversed().forEach { (index, register, opcode) ->
        val black = when (opcode) {
            Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32 -> "const-wide/32 v$register, $BLACK"
            else -> "const v$register, $BLACK"
        }
        replaceInstruction(index, black)
    }
    return sites.size
}

/**
 * Sends each `int` that this method returns through the extension method [target], which takes
 * the colour and the colour token and returns a colour. The token is in parameter
 * [tokenParameterIndex]; the first declared parameter is index 0.
 *
 * The hooks go in from last to first, because an insert moves every later index.
 *
 * Both registers get a test. `invoke-static` has 4-bit operands, thus a register above v15
 * assembles into something else and the patch applies but does nothing.
 *
 * A `return` must not be a branch target. The new instructions go in before it, thus a jump onto
 * the `return` would miss them and lose a colour without an error.
 */
internal fun MutableMethod.hookColorReturns(tokenParameterIndex: Int, target: String) {
    val implementation = checkNotNull(implementation) { "$definingClass->$name has no body" }
    val instructions = implementation.instructions.toList()

    val addresses = instructions.runningFold(0) { address, it -> address + it.codeUnits }
    val indexOfAddress = addresses.withIndex().associate { (index, address) -> address to index }
    val branchTargets = instructions.withIndex().mapNotNull { (index, instruction) ->
        if (instruction is OffsetInstruction) {
            indexOfAddress[addresses[index] + instruction.codeOffset]
        } else {
            null
        }
    }.toSet()

    val returns = instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }

    check(returns.isNotEmpty()) { "$definingClass->$name returns no int to recolour" }

    val parameterRegisters = parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val tokenRegister = implementation.registerCount - parameterRegisters + tokenParameterIndex

    check(tokenRegister < 16) {
        "$definingClass->$name: token register v$tokenRegister is out of invoke-static range"
    }

    returns.asReversed().forEach { (index, register) ->
        check(register < 16) {
            "$definingClass->$name: return register v$register is out of invoke-static range"
        }
        check(index !in branchTargets) {
            "$definingClass->$name: the return at index $index is a branch target"
        }
        addInstructions(
            index,
            """
                invoke-static { v$register, v$tokenRegister }, $target
                move-result v$register
            """,
        )
    }
}
