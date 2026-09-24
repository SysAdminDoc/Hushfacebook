/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Built on SysAdminDoc/hushfeed (GPL-3.0).
 */
package app.morphe.patches.facebook.misc.extension

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

/** The package of the Facebook extension, as a smali descriptor prefix. */
internal const val EXTENSION_PACKAGE = "Lapp/morphe/extension/facebook"

/** Which patches this build carries. See the class's own comment. */
internal const val SETTINGS_STATUS = "$EXTENSION_PACKAGE/settings/SettingsStatus;"

/**
 * Rewrites `SettingsStatus.[name]()` to answer true, so the extension acts for this patch and the
 * settings screen shows its switch. Call it from the feature patch's execute block; the patch must
 * depend on the Facebook extension patch, which is what merges `SettingsStatus` into the APK.
 */
internal fun BytecodePatchContext.enableStatus(name: String) {
    val method = mutableClassDefBy(SETTINGS_STATUS).methods.singleOrNull {
        it.name == name && it.returnType == "Z" && it.parameterTypes.isEmpty()
    } ?: throw PatchException("SettingsStatus has no boolean method $name()")
    method.returnEarly(true)
}

/** How many registers a parameter of this type takes: two for a long or a double. */
private fun CharSequence.width(): Int = if (toString() == "J" || toString() == "D") 2 else 1

/**
 * The `p` register holding declared parameter [index], counting `this` on an instance method and
 * two registers for each wide parameter before it.
 */
internal fun MutableMethod.parameterRegister(index: Int): String {
    require(index in parameterTypes.indices) { "$definingClass->$name has no parameter $index" }
    val self = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return "p" + (self + parameterTypes.take(index).sumOf { it.width() })
}

/** How many of the method's registers are locals rather than parameters. */
internal fun MutableMethod.localRegisterCount(): Int {
    val implementation = implementation
        ?: throw PatchException("$definingClass->$name has no body")
    val self = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    return implementation.registerCount - self - parameterTypes.sumOf { it.width() }
}

/**
 * Throws unless the method has at least [count] locals, which is what an injection at index 0 may
 * borrow: no local holds anything before the method's own first instruction runs.
 */
internal fun MutableMethod.requireLocals(what: String, count: Int) {
    val locals = localRegisterCount()
    if (locals < count) {
        throw PatchException("$what: $definingClass->$name has $locals local register(s), needs $count")
    }
}
