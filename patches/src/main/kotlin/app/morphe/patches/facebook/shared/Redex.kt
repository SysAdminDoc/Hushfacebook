/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/shared/Redex.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.shared

import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/** The static field Redex leaves behind holding a class's name from before obfuscation. */
private const val REDEX_ORIGINAL_NAME = "__redex_internal_original_name"

/**
 * The name [classDef] had before Redex renamed it, or null when the class carries none.
 *
 * About 6.7% of Facebook's classes keep this field, and most of them are inner Kotlin lambdas. It
 * does **not** reliably identify the class that *encloses* a lambda, so it is no substitute for a
 * string or resource anchor. It is reliable for one thing only: recognising the very lambda the name
 * belongs to, which is what it is used for here.
 */
internal fun redexOriginalName(classDef: ClassDef): String? =
    classDef.staticFields
        .firstOrNull { it.name == REDEX_ORIGINAL_NAME }
        ?.let { (it.initialValue as? StringEncodedValue)?.value }
