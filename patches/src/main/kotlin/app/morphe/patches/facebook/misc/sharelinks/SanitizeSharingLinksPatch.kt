/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharelinks

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val SANITIZE =
    "Lapp/morphe/extension/facebook/misc/LinkCleaner;->sanitizeShared(Ljava/lang/String;)Ljava/lang/String;"

/**
 * Takes Facebook's tracking tags off the links the app hands out when someone shares.
 *
 * Each method that adds a tag to a shared link has its answer passed through the extension on
 * the way out, at every return. With the switch off, paused, or before the settings are ready, the
 * extension hands the link back as it came, so Facebook's own code runs as it always did. The
 * methods keep doing everything else they do, their logging included. What changes is only the
 * link that leaves.
 *
 * Found by reading 580 and 577 (2026-09-25): ExternalShareTracker adds `mibextid` to Copy link and
 * to every share destination, and on some servers `extid`, a random id new on each share. Around
 * it, one appender adds `sfnsn` to WhatsApp shares, one `ref=share` to some stories, one `mibextid`
 * to a group's share link, and the live video dialog `sfnsn`.
 */
@Suppress("unused")
val sanitizeSharingLinksPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Takes Facebook's tracking tags, such as mibextid, off the links you share or copy. " +
        "The post or reel a link opens stays the same. A facebook.com/share/ link is made for one " +
        "share, so Facebook can still trace it back to you.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    execute {
        val tracker = mutableClassDefBy(ExternalShareTrackerFingerprint.method.definingClass)
        val trackerMethods = listOf(
            // The link a share hands out, with mibextid added for its source and destination.
            ExternalShareTrackerFingerprint.method,
            // The same for a /share/ link, and Send in Messenger calls it directly.
            tracker.methods.single {
                it.returnType == "Ljava/lang/String;" && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                    it.parameterTypes == listOf(FB_USER_SESSION, "Ljava/lang/Integer;", "Ljava/lang/String;")
            },
            // extid: a random id, new on every share, logged beside the link under the sharer.
            tracker.methods.single {
                val parameters = it.parameterTypes.map(CharSequence::toString)
                it.returnType == "Ljava/lang/String;" && parameters.size == 5 &&
                    parameters[1] == FB_USER_SESSION && parameters.drop(2).all { type -> type == "Ljava/lang/String;" }
            },
        )

        (trackerMethods + listOf(
            SfnsnAppenderFingerprint.method,
            RefShareAppenderFingerprint.method,
            GroupShareLinkFingerprint.method,
            LiveShareLinkFingerprint.method,
        )).forEach { it.sanitizeEveryReturn() }

        enableStatus("sanitizeSharingLinks")
    }
}

/**
 * Sends each link this method returns through the extension. The hooks go in from last to first,
 * because an insert moves every later index. Each goes in at the return's own control-flow label,
 * so a branch that jumped to the return runs it too, and the range form of the call takes a
 * register above v15.
 */
private fun MutableMethod.sanitizeEveryReturn() {
    val implementation = implementation ?: throw PatchException("$definingClass->$name has no body")
    val returns = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) throw PatchException("$definingClass->$name returns no link to sanitize")

    returns.asReversed().forEach { (index, register) ->
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range { v$register .. v$register }, $SANITIZE
                move-result-object v$register
            """,
        )
    }
}
