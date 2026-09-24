/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed

import app.morphe.RepoFiles
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The suggested feed units the patch checks for at patch time are the ones the extension hides at
 * run time.
 *
 * <p>The patch refuses a Facebook build that carries none of them, so a rename fails loudly; the
 * extension's feed filter does the hiding. A unit added to one list and not the other would either
 * be hidden with no patch-time check behind it, or checked for and never hidden. Both files are
 * read as text, so nothing here loads a patch class.
 */
class SuggestedUnitsParityTest {
    private val patchList = File(
        RepoFiles.root,
        "patches/src/main/kotlin/app/morphe/patches/facebook/feed/suggested/HideSuggestedPostsPatch.kt",
    )
    private val filter = File(
        RepoFiles.root,
        "extensions/facebook/src/main/java/app/morphe/extension/facebook/feed/FeedFilter.java",
    )

    @Test
    fun `the patch and the extension name the same suggested units`() {
        val descriptor = Regex(""""L(com/facebook/graphql/model/[A-Za-z0-9]+);"""")
        val binary = Regex(""""(com\.facebook\.graphql\.model\.[A-Za-z0-9]+)"""")
        val checked = descriptor.findAll(patchList.readText()).map { it.groupValues[1].replace('/', '.') }.toSortedSet()
        val hidden = binary.findAll(filter.readText()).map { it.groupValues[1] }.toSortedSet()
        assertTrue("the patch names no suggested unit, so this compares nothing", checked.size > 5)
        assertEquals("suggested units checked at patch time and hidden at run time", checked, hidden)
    }
}
