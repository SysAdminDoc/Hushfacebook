/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.downloads.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.facebook.downloads.story.rememberPlayerSources
import app.morphe.patches.facebook.misc.extension.enableStatus
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.patches.facebook.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

/** The extension call that adds the item to a post's menu once Facebook has filled it. */
private const val ADD_ITEM = "Lapp/morphe/extension/facebook/download/VideoMenuItem;->add(" +
    "Landroid/view/Menu;Landroid/view/View;Ljava/lang/Object;I" +
    "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The video patch's own recorder of player sources, behind its own switch. */
private const val REMEMBER_VIDEO_SOURCE = "Lapp/morphe/extension/facebook/download/PlayerSources;->" +
    "rememberVideo(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V"

/** The helper this patch adds to the class that builds every post menu. */
private const val MENU_HELPER = "hushfacebookVideoMenuItem"

/** The helper this patch adds to the player params, beside the story patch's. */
private const val REMEMBER_HELPER = "hushfacebookRememberVideoSource"

/**
 * Adds Download to phone to the menu of a post that holds a video, in the feed and in Watch.
 *
 * Facebook's own Download video row is in the video feed's menu too, but only for videos whose
 * owner allows it, and 573-era patches that saved any video took over the player's own Save
 * button to do it. This adds an item of its own instead, after Facebook has filled the menu, so
 * Facebook's rows, its Save and its Download included, stay exactly as they were.
 *
 * Every post menu, the news feed's and Watch's alike, is filled by one virtual call to the base
 * of the menu builders, from one method that then shows the sheet. The call goes in right after
 * it, when the menu holds all of Facebook's rows. It hands the extension the menu, the view that
 * opened it and what the menu is for, and the extension adds the item only when that holds a
 * video, and only while Download any video is on, not paused, and the settings are ready.
 *
 * The save reuses the story save's pipeline whole: the player of the same video records its
 * DASH manifest under the video's id, and the post's media carries that id.
 */
@Suppress("unused")
val downloadVideoPatch = bytecodePatch(
    name = "Download any video",
    description = "Adds Download to phone to the menu of videos in the feed and in Watch, below " +
        "Facebook's own items. Videos save at the best quality the player streams.",
    default = false,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.facebook())
    dependsOn(facebookExtensionPatch)

    execute {
        // ---- the video feed's menu, and the base every post menu is filled through -----------
        val videoMenu = VideoFeedStoryMenuFingerprint.method
        val optionsType = videoMenu.definingClass
        val baseType = classDefBy(optionsType).superclass
            ?: throw PatchException("$optionsType has no superclass to fill menus through")
        val signature = videoMenu.parameterTypes.map(CharSequence::toString)

        val base = classDefBy(baseType)
        check(base.methods.count { it.name == videoMenu.name && it.parameters() == signature && it.returnType == "V" } == 1) {
            "$baseType declares no ${videoMenu.name}${signature.joinToString("", "(", ")V")} for $optionsType to override"
        }

        val propsType = signature[2]
        val itemField = classDefBy(propsType).fields.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == "Ljava/lang/Object;"
        }
        check(itemField.size == 1) {
            "Expected 1 field of Object on $propsType, what a menu is for, found ${itemField.size}"
        }

        // ---- where Facebook fills a post menu -----------------------------------------------------
        //
        // Every override calls its base with invoke-super. The one invoke-virtual of the base is
        // the builder that fills the menu and shows it (580: a static method of the feed menu
        // helper that returns the sheet; 577: the same method under another Redex name).
        val sites = mutableListOf<Triple<String, Method, Int>>()
        classDefForEach { classDef ->
            classDef.methods.forEach { method ->
                method.instructionsOrEmpty().forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) {
                        return@forEachIndexed
                    }
                    val reference = instruction.methodReference() ?: return@forEachIndexed
                    if (reference.definingClass == baseType && reference.name == videoMenu.name &&
                        reference.parameterTypes.map(CharSequence::toString) == signature
                    ) {
                        sites += Triple(classDef.type, method, index)
                    }
                }
            }
        }

        check(sites.size == 1) {
            "Expected 1 place that fills a post menu through $baseType->${videoMenu.name}, found ${sites.size}: " +
                sites.joinToString { "${it.first}->${it.second.name}" }
        }

        val (creatorType, creatorMethod, callIndex) = sites.single()
        val creatorClass = mutableClassDefBy(creatorType)
        val creator = creatorClass.methods.single {
            it.name == creatorMethod.name && it.parameters() == creatorMethod.parameters() &&
                it.returnType == creatorMethod.returnType
        }

        // The call takes the builder and seven arguments, so it's always the range form, and the
        // menu, the view and the props sit in the three registers after the builder's. A range
        // call leaves its registers as they were, so they still hold those three right after it.
        val call = creator.instructionsOrEmpty()[callIndex] as? RegisterRangeInstruction
            ?: throw PatchException("$creatorType->${creator.name} fills the menu without a range call")
        val menuRegister = call.startRegister + 1

        // ---- what the extension needs, read from this build --------------------------------------
        val attachments = graphQlGetter(GRAPHQL_STORY, IMMUTABLE_LIST, ATTACHMENTS_FIELD, "attachments")
        val attachedStory = graphQlGetter(GRAPHQL_STORY, GRAPHQL_STORY, ATTACHED_STORY_FIELD, "attached_story")
        val media = graphQlGetter(GRAPHQL_STORY_ATTACHMENT, GRAPHQL_MEDIA, MEDIA_FIELD, "media")
        val icon = stockDownloadIcon(videoMenu.instructionsOrEmpty(), "$optionsType->${videoMenu.name}")

        // ---- the helper -----------------------------------------------------------------------------
        //
        // Its own method, so it has fresh registers: the builder runs with more than eighty.
        val helper = ImmutableMethod(
            creatorType,
            MENU_HELPER,
            listOf(
                ImmutableMethodParameter(MENU, null, null),
                ImmutableMethodParameter(VIEW, null, null),
                ImmutableMethodParameter(propsType, null, null),
            ),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            MutableMethodImplementation(10),
        ).toMutable().apply {
            addInstructionsWithLabels(
                0,
                """
                    if-eqz p2, :none
                    move-object v0, p0
                    move-object v1, p1
                    iget-object v2, p2, $propsType->${itemField.single().name}:Ljava/lang/Object;
                    const v3, $icon
                    const-string v4, "$attachments"
                    const-string v5, "$media"
                    const-string v6, "$attachedStory"
                    invoke-static/range { v0 .. v6 }, $ADD_ITEM
                    :none
                    return-void
                """,
            )
        }
        creatorClass.methods.add(helper)

        // Right after Facebook has filled the menu and before it counts the rows. The extension
        // asks the switch first, so off, paused or not ready, the menu is Facebook's own.
        creator.addInstructions(
            callIndex + 1,
            "invoke-static/range { v$menuRegister .. v${menuRegister + 2} }, " +
                "$creatorType->$MENU_HELPER($MENU$VIEW$propsType)V",
        )

        // ---- the players' sources, for the manifest a post's media doesn't carry -------------------
        rememberPlayerSources(helperName = REMEMBER_HELPER, recorder = REMEMBER_VIDEO_SOURCE)

        enableStatus("videoDownload")
    }
}

/**
 * The name of [owner]'s public getter that returns [returnType] and reads the GraphQL field whose
 * name hashes to [field]. [owner] is a kept model class; the getter's own name is Redex's.
 */
private fun BytecodePatchContext.graphQlGetter(owner: String, returnType: String, field: Int, what: String): String {
    val getters = classDefBy(owner).methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == returnType &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.instructionsOrEmpty().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == field }
    }
    check(getters.size == 1) {
        "Expected 1 getter of $owner that reads $what, found ${getters.size}: " + getters.joinToString { it.name }
    }
    return getters.single().name
}

/**
 * The drawable Facebook's own Download video row sets, read from where that row is built: the
 * `setIcon(I)` the row calls just before it reports itself as [STOCK_DOWNLOAD_ROW], and the
 * constant that call is given.
 */
private fun stockDownloadIcon(instructions: List<Instruction>, where: String): Int {
    val row = instructions.indexOfFirst { it.stringReference() == STOCK_DOWNLOAD_ROW }
    check(row > 0) { "$where does not name its $STOCK_DOWNLOAD_ROW row" }

    val setIcon = (row - 1 downTo maxOf(0, row - 16)).firstOrNull { index ->
        val reference = instructions[index].methodReference()
        reference != null && reference.definingClass == MENU_ITEM && reference.name == "setIcon" &&
            reference.parameterTypes.map(CharSequence::toString) == listOf("I")
    } ?: throw PatchException("$where sets no icon on its $STOCK_DOWNLOAD_ROW row")

    val iconRegister = (instructions[setIcon] as FiveRegisterInstruction).registerD
    val writer = (setIcon - 1 downTo maxOf(0, setIcon - 8)).firstOrNull { index ->
        (instructions[index] as? OneRegisterInstruction)?.registerA == iconRegister
    } ?: throw PatchException("$where: the $STOCK_DOWNLOAD_ROW icon comes from no nearby constant")

    val icon = (instructions[writer] as? NarrowLiteralInstruction)?.narrowLiteral
    check(icon != null && icon ushr 24 == 0x7f) {
        "$where: the $STOCK_DOWNLOAD_ROW icon isn't an app resource constant: ${instructions[writer].opcode}"
    }
    return icon
}

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.instructionsOrEmpty(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.methodReference() =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.stringReference() =
    ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
