/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * The feed unit model that answers `getTypeName()` with exactly [typeName].
 *
 * Facebook's generated GraphQL models keep that method's name while Redex renames their classes
 * every release, and each returns its GraphQL type as a literal. The extension's feed filter
 * compares the name a unit answers, so the literal being there is the evidence the rule needs.
 * `PaginatedPeopleYouMayKnowFeedUnit` is `LX/3zk;` in 580, and is in 577 under another name.
 */
internal fun typeNameFingerprint(typeName: String) = Fingerprint(
    name = "getTypeName",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == typeName
        } == true
    },
)

/** Stops the patch when no model answers [typeName], so a rename fails here, not silently. */
internal fun BytecodePatchContext.requireFeedTypeName(typeName: String) {
    if (typeNameFingerprint(typeName).methodOrNull == null) {
        throw PatchException("No feed unit answers getTypeName() with \"$typeName\" in this APK")
    }
}
