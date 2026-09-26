/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue
import java.nio.ByteBuffer

/**
 * The Facebook extension as the bundle carries it: the dex R8 wrote, which the Morphe plugin puts
 * among this module's resources as `extensions/facebook.mpe`, so every test run reads the one built
 * from the current sources. A test that reads it sees the descriptors and constants the patched app
 * gets, not the Java that compiles to them.
 */
internal object ExtensionDex {
    private const val PAYLOAD = "extensions/facebook.mpe"

    private val dex: DexBackedDexFile by lazy {
        val bytes = ExtensionDex::class.java.classLoader.getResourceAsStream(PAYLOAD)?.use { it.readBytes() }
            ?: throw AssertionError("$PAYLOAD is not on the test classpath. :patches:processResources copies it there.")
        DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(bytes))
    }

    /** The class of [type] in the payload. */
    fun classDef(type: String): ClassDef =
        dex.classes.firstOrNull { it.type == type } ?: throw AssertionError("$PAYLOAD holds no $type")

    /** The value a static final String field of [type] starts with, as javac wrote it into the class. */
    fun stringConstant(type: String, field: String): String {
        val declared = classDef(type).staticFields.firstOrNull { it.name == field }
            ?: throw AssertionError("$type in $PAYLOAD has no static field $field")
        return (declared.initialValue as? StringEncodedValue)?.value
            ?: throw AssertionError("$type.$field in $PAYLOAD starts with no string: ${declared.initialValue}")
    }
}
