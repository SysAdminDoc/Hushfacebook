/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/shared/Neuter.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.shared

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.util.returnEarly

/**
 * Make every void method on [classDescriptor] return immediately, and report how many changed.
 * Constructors are left alone so instances still initialise their fields.
 *
 * Facebook's ad schedulers and trackers keep their real class names through Redex while their
 * method names do not, so methods are selected by shape rather than by a name that is reassigned
 * every release. Returns 0 when the class is absent.
 */
internal fun BytecodePatchContext.neuterVoidMethods(classDescriptor: String): Int {
    val classDef = mutableClassDefByOrNull(classDescriptor) ?: return 0

    return classDef.methods.count { method ->
        val neuterable = method.returnType == "V" &&
            method.name != "<init>" &&
            method.name != "<clinit>" &&
            method.implementation != null

        if (neuterable) method.returnEarly()
        neuterable
    }
}
