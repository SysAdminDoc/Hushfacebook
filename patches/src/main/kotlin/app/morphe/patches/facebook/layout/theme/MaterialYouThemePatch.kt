/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.w3c.dom.Document
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.cbrt
import kotlin.math.pow

internal const val MATERIAL_YOU = "Lapp/morphe/extension/facebook/theme/MaterialYouTheme;"

/** Route one for FDS: the colour, the token, and back comes the colour to draw. */
private const val FDS = "$MATERIAL_YOU->fds(ILjava/lang/Object;)I"

/** Route one for the Mig dark scheme, which only answers for dark mode. */
private const val MIG = "$MATERIAL_YOU->mig(ILjava/lang/Object;)I"

/** Route four: replaces `Color.parseColor`, and AMOLED's replacement of it when AMOLED went first. */
private const val PARSE_COLOR_YOU = "$MATERIAL_YOU->parseColor(Ljava/lang/String;)I"

/**
 * Route three: the dark surfaces Facebook writes into its code, each read from the extension field
 * of the same name instead. None is a light-theme colour in 577 or 580. MaterialYouTheme.SURFACES
 * holds the same six, which MaterialYouParityTest checks.
 */
internal val SURFACE_FIELDS: Map<Int, String> =
    listOf(0x101011, 0x18191A, 0x1C1C1D, 0x242526, 0x252728, 0x3E4042)
        .associate { rgb -> (rgb or -0x1000000) to "DARK_%06X".format(rgb) }

/**
 * The palette Android 11 gets, darkest tone first: the same text as TonePalette.FALLBACK, which
 * MaterialYouParityTest checks. Route two writes it into the night colours as the fallback.
 */
internal const val FALLBACK_PALETTE =
    "000000 00184A 192E60 324578 4A5C92 6375AC 7C8FC8 97AAE4 B3C5FF DBE1FF EEF0FF FEFBFF FFFFFF;" +
        "000000 1A1B21 2F3036 46464C 5D5E64 76767D 909097 ABAAB1 C6C6CD E3E2E9 F1F0F7 FEFBFF FFFFFF;" +
        "000000 191B23 2E3038 45464F 5C5E67 757680 8F909A AAAAB4 C5C6D0 E2E2EC F0F0FA FEFBFF FFFFFF"

/** The tone of each step of a palette family, darkest first. */
private val TONES = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 99, 100)

/** Android's resource names for those steps: system_*_1000 is tone 0, system_*_0 is tone 100. */
private val SYSTEM_STEPS = listOf(1000, 900, 800, 700, 600, 500, 400, 300, 200, 100, 50, 10, 0)

/**
 * A night colour moves to a system tone only when one is this close to its lightness, so no
 * contrast on the screens that read it moves by more than a little.
 */
private const val MAX_TONE_DISTANCE = 3.0

/** The palette family and tone a night colour becomes, and its lightness before. */
internal data class NightTone(val accent: Boolean, val tone: Int) {
    /** The framework colour for API 31 and newer. */
    val systemColor: String
        get() = "@android:color/system_" + (if (accent) "accent1_" else "neutral1_") + SYSTEM_STEPS[TONES.indexOf(tone)]

    /** The same step of the fixed palette, for Android 11. */
    val fallback: String
        get() = "#ff" + FALLBACK_PALETTE.split(";")[if (accent) 0 else 1].trim().split(" ")[TONES.indexOf(tone)].lowercase()
}

/**
 * The tone a night colour resource takes, or null to leave it. A grey takes the neutral family and
 * one of Facebook's blues the accent, at the tone nearest its lightness, and only when that tone is
 * within [MAX_TONE_DISTANCE]. Black, white, a translucent colour or any other hue stays as it is.
 */
internal fun nightTone(value: String): NightTone? {
    val color = parseOpaque(value) ?: return null
    if (color == -0x1000000 || color == -1) return null
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    val accent = when {
        maxOf(r, g, b) - minOf(r, g, b) <= 10 -> false
        isFacebookBlue(r, g, b) -> true
        else -> return null
    }
    val lightness = lstar(r, g, b)
    val tone = TONES.minBy { abs(it - lightness) }
    return if (abs(tone - lightness) <= MAX_TONE_DISTANCE) NightTone(accent, tone) else null
}

/** An opaque `#rrggbb` or `#ffrrggbb`, as an int, or null for anything else. */
private fun parseOpaque(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (!hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return when (hex.length) {
        6 -> hex.toLong(16).toInt() or -0x1000000
        8 -> if (hex.take(2).lowercase() == "ff") hex.toLong(16).toInt() else null
        else -> null
    }
}

/** MaterialYouTheme.isFacebookBlue: an HSV hue from 200 to 225 degrees, clearly coloured. */
private fun isFacebookBlue(r: Int, g: Int, b: Int): Boolean {
    val delta = b - minOf(r, g)
    if (b < r || b < g || b < 77 || delta * 4 < b) return false
    val turn = 60 * (r - g)
    return turn >= -40 * delta && turn <= -15 * delta
}

/** CIE L* of an sRGB colour. */
internal fun lstar(r: Int, g: Int, b: Int): Double {
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    val y = 0.2126 * linear(r) + 0.7152 * linear(g) + 0.0722 * linear(b)
    return if (y <= 216.0 / 24389.0) y * 24389.0 / 27.0 else 116.0 * cbrt(y) - 16.0
}

/**
 * Route two for the night colours: each one [nightTone] takes gets the fixed palette's colour in
 * [night], which Android 11 reads, and the wallpaper palette's in [nightV31], which Android 12 and
 * newer prefer. A colour Facebook already gives a value of its own for Android 12 and newer is left
 * alone, and the default (light) colours are never opened. Answers how many it changed.
 */
internal fun recolourNightColours(night: Document, nightV31: Document): Int {
    val resources = nightV31.documentElement
    val present = mutableSetOf<String>()
    val existing = resources.getElementsByTagName("color")
    for (index in 0 until existing.length) {
        (existing.item(index) as? Element)?.let { present += it.getAttribute("name") }
    }

    var changed = 0
    val colors = night.getElementsByTagName("color")
    for (index in 0 until colors.length) {
        val color = colors.item(index) as? Element ?: continue
        val tone = nightTone(color.textContent) ?: continue
        val name = color.getAttribute("name")
        if (name in present) continue
        color.textContent = tone.fallback
        resources.appendChild(
            nightV31.createElement("color").also {
                it.setAttribute("name", name)
                it.textContent = tone.systemColor
            },
        )
        changed++
    }
    return changed
}

private const val NIGHT_COLORS = "res/values-night/colors.xml"
private const val NIGHT_V31_COLORS = "res/values-night-v31/colors.xml"

private val materialYouResourcePatch = resourcePatch {
    // After every patch's own work, so AMOLED's black has gone in first and stays: black is no
    // tone this recolours.
    finalize {
        check(get(NIGHT_COLORS, false).exists()) { "Facebook has no night colours, so text would keep its grey" }
        val dynamic = get(NIGHT_V31_COLORS, false)
        if (!dynamic.exists()) {
            dynamic.parentFile.mkdirs()
            dynamic.writeText("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n</resources>\n")
        }
        val changed = document(NIGHT_COLORS).use { night ->
            document(NIGHT_V31_COLORS).use { nightV31 -> recolourNightColours(night, nightV31) }
        }
        check(changed > 0) { "No night colour is near a palette tone, so text would keep its grey" }
    }
}

@Suppress("unused")
val materialYouThemePatch = bytecodePatch(
    name = "Material You theme",
    description = "Gives Facebook's dark mode the colours of your wallpaper on Android 12 and newer, and " +
        "a fixed blue palette on Android 11. Light mode stays as it is. Turn on dark mode in Facebook first.",
    default = false,
) {
    category("Interface")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(materialYouResourcePatch)

    dependsOn(facebookExtensionPatch)

    execute {
        enableStatus("materialYouTheme")
    }

    // After every patch's execute, so with AMOLED in the build its hooks and rewrites are already
    // there: each hook here goes after AMOLED's and gets AMOLED's colour, and AMOLED's black is no
    // dark-theme colour this recolours.
    finalize {
        // Route one: the Mig dark scheme, the FDSColors resolvers and the view code's resolver.
        DarkSchemeResolveFingerprint.method.hookColorReturns(tokenParameterIndex = 0, target = MIG)
        hookFdsColorsResolvers(target = FDS)
        fdsViewResolver().hookColorReturns(tokenParameterIndex = 1, target = FDS)

        // Route four. AMOLED, when it went first, has sent every call to its own parser, and the
        // extension's parser calls AMOLED's when AMOLED is in the build.
        var amoledParsers = 0
        val parsers = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
            if (classDef.methods.any { it.callsAnyParser() }) parsers += classDef.type
        }
        val rerouted = parsers.sumOf { type ->
            mutableClassDefByOrNull(type)?.methods?.sumOf { method ->
                method.implementation?.instructions?.count { it.referenceText() == PARSE_COLOR_DARK }
                    ?.let { amoledParsers += it }
                method.rerouteParsers()
            } ?: 0
        }
        check(rerouted > 0) { "No call to Color.parseColor found, so server colours would stay grey" }

        // Route three. AMOLED, when it went first, has blackened all but one of these, which is why
        // finding none is fine then.
        val fields = classDefBy(MATERIAL_YOU).fields
            .filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "I" }
            .map { it.name }
            .toSet()
        SURFACE_FIELDS.values.forEach { field ->
            check(field in fields) { "$MATERIAL_YOU has no static int $field for route three to read" }
        }
        val owners = mutableSetOf<String>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(EXTENSION_PACKAGE)) return@classDefForEach
            if (classDef.methods.any { it.writesSurface() }) owners += classDef.type
        }
        val read = owners.sumOf { type ->
            mutableClassDefByOrNull(type)?.methods?.sumOf { it.readSurfaceFields() } ?: 0
        }
        check(read > 0 || amoledParsers > 0) { "No dark surface written in code, so the chrome would stay grey" }
    }
}

private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

/** True when this method calls `Color.parseColor` or AMOLED's replacement of it. */
private fun Method.callsAnyParser(): Boolean =
    implementation?.instructions?.any { it.referenceText().let { text -> text == PARSE_COLOR || text == PARSE_COLOR_DARK } } == true

/**
 * Sends each call to `Color.parseColor`, or to AMOLED's replacement of it, to the extension. Same
 * signature, same register, same form, so the `move-result` after it stays right.
 */
private fun MutableMethod.rerouteParsers(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.referenceText().let { text -> text == PARSE_COLOR || text == PARSE_COLOR_DARK } }

    sites.asReversed().forEach { (index, instruction) ->
        val call = when (instruction) {
            is RegisterRangeInstruction ->
                "invoke-static/range { v${instruction.startRegister} .. v${instruction.startRegister} }, $PARSE_COLOR_YOU"
            is FiveRegisterInstruction -> "invoke-static { v${instruction.registerC} }, $PARSE_COLOR_YOU"
            else -> error("$definingClass->$name: unexpected call form ${instruction.opcode}")
        }
        replaceInstruction(index, call)
    }
    return sites.size
}

/** True for a `const` of one of the surfaces. A `const-wide` keeps its value: a long isn't read from an int field. */
private fun Instruction.isSurface(): Boolean =
    opcode == Opcode.CONST && this is NarrowLiteralInstruction && narrowLiteral in SURFACE_FIELDS

private fun Method.writesSurface(): Boolean = implementation?.instructions?.any { it.isSurface() } == true

/**
 * Replaces each `const vX, <surface>` in this method with a read of the extension field for that
 * surface, into the same register. A `const` has an 8-bit register and so does `sget`, and neither
 * branches, so nothing around it moves. Answers how many it replaced.
 */
internal fun MutableMethod.readSurfaceFields(): Int {
    val sites = (implementation ?: return 0).instructions.withIndex()
        .filter { it.value.isSurface() }
        .map { Triple(it.index, (it.value as OneRegisterInstruction).registerA, (it.value as NarrowLiteralInstruction).narrowLiteral) }

    sites.asReversed().forEach { (index, register, value) ->
        replaceInstruction(index, "sget v$register, $MATERIAL_YOU->${SURFACE_FIELDS.getValue(value)}:I")
    }
    return sites.size
}
