package app.pitstop.video

import app.pitstop.video.core.SourceId
import app.pitstop.video.core.shownSources
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatformChoiceTest {
    private val video = listOf(SourceId.YOUTUBE, SourceId.BILIBILI, SourceId.LOCAL)

    @Test fun hiddenPlatformsAreLeftOut() {
        assertEquals(listOf(SourceId.YOUTUBE, SourceId.LOCAL), shownSources(video, setOf(SourceId.BILIBILI)) { it })
    }

    @Test fun aBarIsNeverEmpty() {
        assertEquals(video, shownSources(video, video.toSet()) { it })
    }

    @Test fun otherBarsHiddenPlatformsDontMatter() {
        assertEquals(video, shownSources(video, setOf(SourceId.NETEASE)) { it })
    }
}
