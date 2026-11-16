package app.pitstop.video

import app.pitstop.video.core.PickedFiles
import app.pitstop.video.core.isPermissionLoss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickedFilesTest {
    @Test fun findsSongsWhoseGrantWasDropped() {
        val picked = listOf("content://d/1", "content://d/2", "content://d/3")
        assertEquals(listOf("content://d/1", "content://d/3"), PickedFiles.withoutAccess(picked, setOf("content://d/2")))
        assertEquals(emptyList<String>(), PickedFiles.withoutAccess(picked, picked.toSet()))
    }

    @Test fun permissionLossIsFoundInsideWrappers() {
        val denied = SecurityException("Permission Denial: reading ... requires ACTION_OPEN_DOCUMENT")
        assertTrue(isPermissionLoss(RuntimeException("Source error", IllegalStateException("load", denied))))
        assertFalse(isPermissionLoss(java.io.IOException("timeout")))
        assertFalse(isPermissionLoss(null))
    }

    @Test fun treeFolderIdsRoundTrip() {
        val tree = "content://com.google.android.apps.docs.storage/tree/acc%3D5%3Bdoc%3Dencoded%3Dabc"
        val doc = "acc=5;doc=encoded=xyz=="
        val id = PickedFiles.treeFolderId(tree, doc)
        assertTrue(id.startsWith(app.pitstop.video.core.COLLECTION_PREFIX))
        assertEquals(tree to doc, PickedFiles.parseTreeFolder(id))
        // Other folders (Drive API ids, Google picker folders) aren't tree folders.
        assertEquals(null, PickedFiles.parseTreeFolder(app.pitstop.video.core.COLLECTION_PREFIX + "1AbC"))
        assertEquals(null, PickedFiles.parseTreeFolder(app.pitstop.video.core.COLLECTION_PREFIX + "gp:1AbC"))
        assertEquals(null, PickedFiles.parseTreeFolder("content://x"))
    }

    @Test fun folderGrantsCoverTheirFiles() {
        val tree = "content://com.google.android.apps.docs.storage/tree/acc%3D5%3Bdoc%3Dencoded%3Dabc"
        val granted = setOf(tree, "content://d/2")
        assertTrue(PickedFiles.hasAccess("$tree/document/acc%3D5%3Bdoc%3Dencoded%3Dsong", granted))
        assertTrue(PickedFiles.hasAccess("content://d/2", granted))
        assertFalse(PickedFiles.hasAccess("content://d/1", granted))
        assertEquals(listOf("content://d/1"), PickedFiles.withoutAccess(listOf("content://d/1", "$tree/document/x"), granted))
    }

    @Test fun songsMatchByFileName() {
        assertEquals("adele - hello.mp3", PickedFiles.matchKey(PickedFiles.fileName("Adele - Hello", "MP3", null)))
        assertEquals("adele - hello.mp3", PickedFiles.matchKey(PickedFiles.fileName("x", "MP3", " Adele - Hello.MP3 ")))
        // A subtitle that isn't an extension (e.g. "Folder") isn't glued on.
        assertEquals("song", PickedFiles.fileName("song", "No access · add again", null))
    }
}
