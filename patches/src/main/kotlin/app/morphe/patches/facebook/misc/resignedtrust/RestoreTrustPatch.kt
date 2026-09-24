/*
 * Forked from:
 * https://github.com/andrewliang25/morphe-patches/blob/5db2e57e133aede5297c48b419168cf30fd89953/patches/src/main/kotlin/app/andrewliang/patches/facebook/resignedtrust/RestoreTrustPatch.kt
 * Copyright 2026 Andrew Liang (GPL-3.0).
 *
 * Modified for Hushfacebook (Facebook), 2026.
 */
package app.morphe.patches.facebook.misc.resignedtrust

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.settings.settingsPatch

private const val PACKAGE_INFO = "Landroid/content/pm/PackageInfo;"

private const val ORIGINAL_SIGNERS = "Lapp/morphe/extension/facebook/misc/FacebookSignature;->" +
    "originalSigners(Landroid/content/pm/PackageInfo;)Ljava/util/List;"

@Suppress("unused")
val restoreTrustPatch = bytecodePatch(
    name = "Restore screens on re-signed builds",
    description = "Makes profiles and some Settings pages open again on a re-signed build. A " +
        "Root Mount install doesn't need this patch.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())

    dependsOn(facebookExtensionPatch)

    execute {
        val method = PackageSignersFingerprint.method
        val owner = mutableClassDefBy(method.definingClass)

        // The package whose signers the method reads. The class holds exactly one.
        val packageInfoFields = owner.fields.filter { it.type.toString() == PACKAGE_INFO }
        check(packageInfoFields.size == 1) {
            "Expected 1 PackageInfo field on ${method.definingClass}, found ${packageInfoFields.size}"
        }
        val packageInfo = packageInfoFields.single().name

        // The result holds the signer list and two flags. Its constructor states that shape.
        val signers = method.returnType.toString()
        val constructor = mutableClassDefBy(signers).methods.singleOrNull {
            it.name == "<init>" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "Z", "Z")
        }
        check(constructor != null) { "$signers has no (List, boolean, boolean) constructor" }

        // For Facebook itself, answer with the original certificate and skip the body. For any
        // other package, the extension answers null and the body runs as before. The two flags
        // are false, as the body sets them for a single signer.
        //
        // The injection is at index 0, where no local is live yet, so v0 to v2 are free.
        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${method.definingClass}->$packageInfo:$PACKAGE_INFO
                invoke-static { v0 }, $ORIGINAL_SIGNERS
                move-result-object v1
                if-eqz v1, :original
                new-instance v0, $signers
                const/4 v2, 0x0
                invoke-direct { v0, v1, v2, v2 }, $signers-><init>(Ljava/util/List;ZZ)V
                return-object v0
            """,
            ExternalLabel("original", method.getInstruction(0)),
        )

        enableStatus("restoreTrust")
    }
}
