/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.storiestray

import app.morphe.patches.facebook.feed.holdsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method

/**
 * Kept literals. The Stories tray is never a feed edge: NewsFeedAdapterConfiguration builds the
 * feed's adapter list and adds the tray as an adapter of its own, in one of two methods of the same
 * class (read from 573, 577 and 580, 2026-09-25):
 *
 * - The classic tray, in the method holding the trace name [ADD_STORIES_ADAPTER] (`LX/2Qi;->A00`
 *   in 580, `LX/2Tu;->A00` in 577, `LX/2T2;->A00` in 573). It adds the tray controller's adapter to
 *   the list and returns it, or returns null.
 * - The unified "tofu" tray, in the static sibling holding [TRAY_ADAPTER_START], [TRAY_ADAPTER_STOP]
 *   and [TOFU] (`A01` of the same classes). Its first instruction returns null unless a server gate
 *   is on. The start and stop names also sit in the tray controller's constructor, which is why the
 *   sibling has to take the configuration class and the list builder too.
 *
 * Both take (the configuration class, `ImmutableList$Builder`) and are static. Their callers only look
 * the answer up in the finished list with `ImmutableList.indexOf`, which gives -1 for null, so null
 * is the state Facebook itself is in when the tray is gated off.
 */
internal const val ADD_STORIES_ADAPTER = "NewsFeedAdapterConfiguration.addStoriesAdapter"
internal const val TRAY_ADAPTER_START = "stories_tray_create_adapter_start"
internal const val TRAY_ADAPTER_STOP = "stories_tray_create_adapter_stop"
internal const val TOFU = "tofu"
internal const val IMMUTABLE_LIST_BUILDER = "Lcom/google/common/collect/ImmutableList\$Builder;"

/** Whether [method] has a tray adapter's shape: static, (its own class, list builder), an object back. */
internal fun isTrayAdapterShape(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null &&
        method.returnType.startsWith("L") &&
        method.parameterTypes.map { it.toString() } == listOf(method.definingClass, IMMUTABLE_LIST_BUILDER)

/** The classic tray adapter methods of [configuration]: the shape, and the trace name. */
internal fun legacyTrayAdapters(configuration: ClassDef): List<Method> = configuration.methods.filter {
    isTrayAdapterShape(it) && holdsString(it, ADD_STORIES_ADAPTER)
}

/** The unified tray adapter methods of [configuration]: the shape, and all three of its names. */
internal fun unifiedTrayAdapters(configuration: ClassDef): List<Method> = configuration.methods.filter {
    isTrayAdapterShape(it) && holdsString(it, TRAY_ADAPTER_START) && holdsString(it, TRAY_ADAPTER_STOP) &&
        holdsString(it, TOFU)
}
