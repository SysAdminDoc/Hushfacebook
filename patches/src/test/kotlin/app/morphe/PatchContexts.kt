/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe

import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File

/**
 * A patcher context over a handful of classes and no APK, so a test can run a patch's execute block
 * as the patcher does and read what it left in each class.
 *
 * The patcher fills its class pool from an APK's dex files, and what takes a pool directly is
 * internal to it: the context's constructor, the pool's class and its setter. They are reached by
 * their JVM names, pinned to morphe-patcher 1.14.1. A bump that renames one fails here, naming it,
 * rather than leaving a test that never ran the patch. Nothing is written to disk: the context only
 * names its work folders until something compiles the dex, which no test here asks for.
 */
internal object PatchContexts {
    fun of(classes: Collection<ClassDef>): BytecodePatchContext {
        val work = File(System.getProperty("java.io.tmpdir"), "hushfacebook-patch-context")
        val config = PatcherConfig(apkFile = File(work, "none.apk"), temporaryFilesPath = work)
        val metadataType = Class.forName("app.morphe.patcher.PackageMetadata")
        val metadata = metadataType.getConstructor(
            String::class.java, String::class.java, String::class.java,
            Class.forName("com.reandroid.archive.block.ApkSignatureBlock"),
        ).newInstance("com.facebook.katana", "0", "0", null)
        val context = BytecodePatchContext::class.java.getConstructor(PatcherConfig::class.java, metadataType)
            .newInstance(config, metadata)
        val poolType = Class.forName("app.morphe.patcher.util.PatchClasses")
        val pool = poolType.getConstructor(Set::class.java).newInstance(classes.toSet())
        BytecodePatchContext::class.java.getMethod("setPatchClasses\$morphe_patcher", poolType).invoke(context, pool)
        BytecodePatchContext::class.java.getMethod("setOpcodes\$morphe_patcher", Opcodes::class.java)
            .invoke(context, Opcodes.getDefault())
        return context
    }
}
