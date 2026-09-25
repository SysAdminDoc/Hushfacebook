/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.RepoFiles
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ResTableMapEntry
import com.reandroid.arsc.value.ValueItem
import com.reandroid.arsc.value.ValueType
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours the Material You theme recolours, held to the Facebook builds it declares.
 *
 * <p>MaterialYouTheme.FDS_DARK lists FDS colour tokens with the colour Facebook's dark theme gives
 * each, and the theme recolours a token only when it arrives with exactly that colour. This reads
 * both fixtures: the token enum `FDSColors` resolves (its names and theme attributes, from its
 * static initializer) and the FDS styles in the resource table that set those attributes. Every
 * listed colour has to be one the dark or darker style gives that token, and none may be one any
 * light style gives it, or light mode would change. SURFACES, which route three and route four
 * recolour with no token to go on, may be no light style's colour for any token at all.
 */
class MaterialYouTokenFixtureTest {
    private val theme = File(RepoFiles.root,
        "extensions/facebook/src/main/java/app/morphe/extension/facebook/theme/MaterialYouTheme.java").readText()

    /** Token name to the dark colours the theme recolours, from the Java table. */
    private fun listedTokens(): Map<String, Set<Int>> {
        val start = theme.indexOf("static final String FDS_DARK =")
        val end = theme.indexOf("\";", start)
        val table = Regex(""""([^"]*)"""").findAll(theme.substring(start, end + 1)).joinToString("") { it.groupValues[1] }
        return table.split(";").associate { entry ->
            val (name, values) = entry.split("=")
            name to values.split(",").map { it.toInt(16) or -0x1000000 }.toSet()
        }
    }

    private fun listedSurfaces(): Set<Int> =
        Regex("""static final String SURFACES = "([0-9A-F ]+)";""").find(theme)!!.groupValues[1]
            .split(" ").map { it.toInt(16) or -0x1000000 }.toSet()

    @Test
    fun `every recoloured token and surface is dark-only in each declared build`() {
        val listed = listedTokens()
        assertTrue("the table lists almost nothing", listed.size > 50)
        val surfaces = listedSurfaces()
        var builds = 0
        for (target in AppCompatibilities.facebook().single().targets) {
            val version = checkNotNull(target.version)
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                withBaseApk(fixture) { apk -> checkBuild(fixture.name, apk, listed, surfaces) }
                builds++
            }
        }
        assertEquals("one fixture for each declared build", AppCompatibilities.facebook().single().targets.size, builds)
    }

    private fun checkBuild(build: String, apk: File, listed: Map<String, Set<Int>>, surfaces: Set<Int>) {
        val attributes = tokenAttributes(apk)
        assertTrue("$build: the token enum has too few constants", attributes.size > 300)
        val styles = fdsStyles(apk, attributes.values.toSet())

        // The light theme sets every token and has no parent; dark is its child that sets them all
        // again, and darker is dark's child.
        val light = styles.values.single { it.parent == 0 && it.sets > 300 }
        val dark = styles.values.single { it.parent == light.id && it.sets > 300 }
        val darker = styles.values.single { it.parent == dark.id }
        val lightSide = styles.values.filter { it.id != dark.id && it.id != darker.id }

        for ((name, colours) in listed) {
            val attribute = attributes[name] ?: error("$build: no FDS token $name")
            val darkColours = setOfNotNull(dark.values[attribute], darker.values[attribute])
            assertTrue("$build: $name is listed with ${hex(colours)}, dark gives ${hex(darkColours)}",
                darkColours.containsAll(colours))
            for (style in lightSide) {
                val lightColour = style.values[attribute] ?: continue
                assertTrue("$build: style ${Integer.toHexString(style.id)} gives $name the listed ${hex(setOf(lightColour))}",
                    lightColour !in colours)
            }
        }

        val lightColours = lightSide.flatMap { it.values.values }.toSet()
        for (surface in surfaces) {
            assertTrue("$build: ${hex(setOf(surface))} is a light style's colour", surface !in lightColours)
        }
    }

    private class Style(val id: Int, val parent: Int, val sets: Int, val values: Map<Int, Int>)

    /** Every style that sets 20 or more FDS attributes, with each one's colour where it resolves to one. */
    private fun fdsStyles(apk: File, fdsAttributes: Set<Int>): Map<Int, Style> {
        val table = ZipFile(apk).use { zip -> zip.getInputStream(zip.getEntry(TableBlock.FILE_NAME)).use { TableBlock.load(it) } }
        val byId = mutableMapOf<Int, ResourceEntry>()
        for (block in table.listPackages()) {
            for (pair in block.listSpecTypePairs()) {
                for (resource in pair.resources) if (resource != null && !resource.isEmpty) byId[resource.resourceId] = resource
            }
        }
        val styles = mutableMapOf<Int, Style>()
        for (resource in byId.values) {
            if (!resource.type.startsWith("style")) continue
            for (entry in resource.iterator()) {
                if (entry == null || entry.isNull || !entry.isComplex) continue
                val bag = entry.tableEntry as ResTableMapEntry
                val values = mutableMapOf<Int, Int>()
                var set = 0
                for (item in bag) {
                    if (item.nameId !in fdsAttributes) continue
                    set++
                    colour(item, byId, 0)?.let { values[item.nameId] = it }
                }
                if (set >= 20) styles[resource.resourceId] = Style(resource.resourceId, bag.parentId, set, values)
            }
        }
        return styles
    }

    /** A colour value, following references to colour resources in their default configuration. */
    private fun colour(item: ValueItem, byId: Map<Int, ResourceEntry>, depth: Int): Int? = when (item.valueType) {
        ValueType.COLOR_ARGB8, ValueType.COLOR_RGB8, ValueType.COLOR_ARGB4, ValueType.COLOR_RGB4 -> item.data
        ValueType.REFERENCE, ValueType.DYNAMIC_REFERENCE -> {
            val target = byId[item.data]
            val entry: Entry? = target?.let { resource ->
                resource.iterator().asSequence().firstOrNull { it != null && !it.isNull && it.resConfig.isDefault }
            }
            if (entry == null || entry.isComplex || depth > 4) null else colour(entry.resValue, byId, depth + 1)
        }
        else -> null
    }

    /** Each constant of the token enum FDSColors resolves, by name, with the theme attribute it passes. */
    private fun tokenAttributes(apk: File): Map<String, Int> {
        val container = DexFileFactory.loadDexContainer(apk, Opcodes.getDefault())
        val classes = mutableMapOf<String, ClassDef>()
        for (name in container.dexEntryNames) {
            for (classDef in container.getEntry(name)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
        }
        val source = classes.getValue(FDS_COLORS).methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Ljava/lang/Integer;" &&
                method.parameterTypes.size == 3 && method.parameterTypes[0].toString() == "Landroid/content/Context;"
        }
        val tokenType = source.parameterTypes[1].toString()
        val initializer = classes.getValue(tokenType).methods.single { it.name == "<clinit>" }

        // Follows constants and moves through registers to each constructor call, which takes the
        // name, the ordinal, the theme attribute, a fallback colour and a colour resource.
        val registers = mutableMapOf<Int, Any?>()
        val tokens = mutableMapOf<String, Int>()
        for (instruction in initializer.implementation!!.instructions) {
            val opcode = instruction.opcode
            when {
                instruction is NarrowLiteralInstruction && instruction is OneRegisterInstruction &&
                    opcode in setOf(Opcode.CONST, Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST_HIGH16) ->
                    registers[instruction.registerA] = instruction.narrowLiteral
                opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO ->
                    registers[(instruction as OneRegisterInstruction).registerA] =
                        ((instruction as ReferenceInstruction).reference as StringReference).string
                opcode in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16, Opcode.MOVE_OBJECT,
                    Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) ->
                    registers[(instruction as TwoRegisterInstruction).registerA] = registers[instruction.registerB]
                (opcode == Opcode.INVOKE_DIRECT || opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    ((instruction as ReferenceInstruction).reference as? MethodReference)
                        ?.let { it.name == "<init>" && it.definingClass == tokenType } == true -> {
                    val arguments = when (instruction) {
                        is RegisterRangeInstruction -> (0 until instruction.registerCount).map { instruction.startRegister + it }
                        is FiveRegisterInstruction -> listOf(instruction.registerC, instruction.registerD,
                            instruction.registerE, instruction.registerF, instruction.registerG).take(instruction.registerCount)
                        else -> error("unexpected call form $opcode")
                    }
                    val name = registers[arguments[1]] as? String ?: error("a token constructed without a name")
                    tokens[name] = registers[arguments[3]] as? Int ?: error("$name has no theme attribute")
                }
                instruction is OneRegisterInstruction && opcode != Opcode.SPUT_OBJECT -> registers.remove(instruction.registerA)
            }
        }
        return tokens
    }

    private fun withBaseApk(fixture: File, check: (File) -> Unit) = ZipFile(fixture).use { zip ->
        val base = checkNotNull(zip.getEntry("base.apk")) { "${fixture.name} holds no base.apk" }
        val copy = File.createTempFile("fixture-base", ".apk")
        try {
            zip.getInputStream(base).use { input -> copy.outputStream().use { input.copyTo(it) } }
            check(copy)
        } finally {
            copy.delete()
        }
    }

    private fun hex(colours: Set<Int>) = colours.joinToString(",") { "#%08X".format(it) }
}
