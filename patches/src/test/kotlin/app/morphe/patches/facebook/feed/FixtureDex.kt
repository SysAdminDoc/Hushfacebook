/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads the classes a fixture test needs out of a Facebook bundle's base APK, one dex at a time, so
 * no APK lands on disk and no more than one dex is held at once. What it hands back are immutable
 * copies, which keep none of the dex they came from.
 */
internal object FixtureDex {
    private val DEX = Regex("""classes\d*\.dex""")

    private fun forEachDex(bundle: File, visit: (DexBackedDexFile) -> Unit) {
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (DEX.matches(entry.name)) visit(DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(apk.readBytes())))
                }
            }
        }
    }

    /** The classes of [types] the bundle carries, by type. */
    fun classes(bundle: File, types: Set<String>): Map<String, ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        forEachDex(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in types && classDef.type !in found) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /**
     * Every method that [wanted] picks, in the dex files [dexFilter] lets through. The filter is how
     * a whole-APK search stays quick: a call needs its target in the dex's method section, and a
     * name needs to be in its string section, so a dex without them can be skipped unread.
     */
    fun methodsWhere(bundle: File, dexFilter: (DexBackedDexFile) -> Boolean, wanted: (Method) -> Boolean): List<Method> {
        val found = mutableListOf<Method>()
        forEachDex(bundle) { dex ->
            if (!dexFilter(dex)) return@forEachDex
            for (classDef in dex.classes) {
                for (method in classDef.methods) {
                    if (wanted(method)) found += ImmutableMethod.of(method)
                }
            }
        }
        return found
    }

    /** Every class with a method that loads exactly [string]. Only the dex files holding it are walked. */
    fun classesHolding(bundle: File, string: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        forEachDex(bundle) { dex ->
            if (dex.stringSection.none { it == string }) return@forEachDex
            for (classDef in dex.classes) {
                if (classDef.methods.any { holdsString(it, string) }) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }
}
