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
}
