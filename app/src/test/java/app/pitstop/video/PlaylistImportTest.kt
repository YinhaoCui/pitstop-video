package app.pitstop.video

import app.pitstop.video.core.Playlist
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.importer.NativeLink
import app.pitstop.video.importer.PlaylistFiles
import app.pitstop.video.importer.TrackMatcher
import app.pitstop.video.importer.TrackMeta
import app.pitstop.video.importer.extractUrl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportTest {
    private fun song(title: String, author: String, dur: Long?, source: SourceId = SourceId.NETEASE) =
        VideoItem(source, title, title, author, durationSec = dur)

    @Test fun exactSongBeatsLiveAndCover() {
        val t = TrackMeta("晴天", listOf("周杰伦"), durationSec = 269)
        val good = TrackMatcher.score(t, song("晴天", "周杰伦", 270))
        val live = TrackMatcher.score(t, song("晴天 (Live)", "周杰伦", 300))
        val cover = TrackMatcher.score(t, song("晴天 翻唱", "某人", 268))
        assertTrue(good > live && good > cover)
        assertTrue(good >= TrackMatcher.GREAT_SCORE)
    }

    @Test fun youtubeTitlesWithNoiseStillMatch() {
        val t = TrackMeta("Bohemian Rhapsody", listOf("Queen"), durationSec = 354)
        val yt = song("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official", 359, SourceId.YT_MUSIC)
        assertTrue(TrackMatcher.score(t, yt) >= TrackMatcher.MIN_SCORE)
        assertTrue(TrackMatcher.score(t, song("Another One Bites the Dust", "Queen", 215)) < TrackMatcher.MIN_SCORE)
    }

    @Test fun matchTriesPlatformsInOrderAndStopsOnAGreatHit() = runBlocking {
        val asked = mutableListOf<SourceId>()
        val best = TrackMatcher.match(TrackMeta("Yellow", listOf("Coldplay"), durationSec = 266), listOf(SourceId.NETEASE, SourceId.YT_MUSIC)) { id, _ ->
            asked += id
            if (id == SourceId.NETEASE) listOf(song("Yellow", "Coldplay", 267)) else emptyList()
        }
        assertNotNull(best)
        assertEquals(listOf(SourceId.NETEASE), asked)
        assertNull(TrackMatcher.match(TrackMeta("Nothing like it", listOf("Nobody")), listOf(SourceId.NETEASE)) { _, _ -> listOf(song("Yellow", "Coldplay", 267)) })
    }

    @Test fun bracketedExtrasDontBlockAMatch() {
        // Bilibili's "久石譲" is "Joe Hisaishi" on YouTube Music: the title has to carry the match.
        val t = TrackMeta("Ask me why (疎開)", listOf("久石譲"))
        val yt = song("Ask me why", "Joe Hisaishi", 120, SourceId.YT_MUSIC)
        assertTrue(TrackMatcher.score(t, yt) >= TrackMatcher.MIN_SCORE)
        assertTrue(TrackMatcher.score(t, song("Ask Me Anything", "Joe Hisaishi", 120)) < TrackMatcher.MIN_SCORE)
        assertEquals("Ask me why", TrackMatcher.coreTitle("Ask me why (疎開)"))
    }

    @Test fun artistUnderAnotherNameBeatsACover() = runBlocking {
        // What YouTube Music returns for "久石譲 Ask me why (疎開)".
        val results = listOf(
            song("Ask Me Why (Evacuation) (Piano Version)", "Piano Echoes", 81, SourceId.YT_MUSIC),
            song("Ask Me Why", "David Pasqualini", 206, SourceId.YT_MUSIC),
            song("Ask me why (II)", "Joe Hisaishi", 208, SourceId.YT_MUSIC),
            song("Ask me why (I)", "Joe Hisaishi", 73, SourceId.YT_MUSIC),
        )
        val t = TrackMeta("Ask me why (疎開)", listOf("久石譲"))
        assertEquals(setOf("joehisaishi"), TrackMatcher.inferredArtists(t, results))
        val best = TrackMatcher.match(t, listOf(SourceId.YT_MUSIC)) { _, _ -> results }
        assertEquals("Joe Hisaishi", best?.item?.author)
    }

    @Test fun normalizeFoldsWidthCaseAndPunctuation() {
        assertEquals(TrackMatcher.normalize("ＡＢＣ，d-e"), TrackMatcher.normalize("abc d e"))
        assertEquals("Song", TrackMatcher.cleanTitle("Song (feat. Someone)").trim())
    }

    @Test fun textAndCsvParse() {
        val text = PlaylistFiles.parseText("1. 周杰伦 - 晴天\n\nTaylor Swift, Ed Sheeran – Everything Has Changed\nJust A Title")
        assertEquals(3, text.size)
        assertEquals(TrackMeta("晴天", listOf("周杰伦")), text[0])
        assertEquals(listOf("Taylor Swift", "Ed Sheeran"), text[1].artists)
        assertEquals("Just A Title", text[2].title)

        // Exportify-style header, quoted cells, duration in ms.
        val csv = "\"Track Name\",\"Artist Name(s)\",\"Album Name\",\"Duration (ms)\",\"ISRC\"\n" +
            "\"Yellow\",\"Coldplay\",\"Parachutes\",\"266773\",\"GBAYE0000351\"\n" +
            "\"Hello, World\",\"A;B\",\"\",\"180000\",\"\"\n"
        val rows = PlaylistFiles.parseCsv(csv)
        assertEquals(2, rows.size)
        assertEquals(TrackMeta("Yellow", listOf("Coldplay"), "Parachutes", 266, "GBAYE0000351"), rows[0])
        assertEquals("Hello, World", rows[1].title)
        assertEquals(listOf("A", "B"), rows[1].artists)
    }

    @Test fun pitstopFileRoundTripsAndCsvExportEscapes() {
        val p = Playlist("x", "Mixed", listOf(song("A, \"quoted\"", "Me", 10), song("BV1xx411c7mD", "Up", 99, SourceId.BILIBILI)))
        val back = PlaylistFiles.fromPitstop(PlaylistFiles.toPitstop(listOf(p)))!!
        assertEquals(p.items, back.single().items)
        assertNull(PlaylistFiles.fromPitstop("{\"something\":1}"))
        val csv = PlaylistFiles.toCsv(p) { null }
        assertTrue(csv.lines()[1].startsWith("\"A, \"\"quoted\"\"\""))
        assertEquals("Me - A, \"quoted\"", PlaylistFiles.toText(p).lines().first())
    }

    @Test fun nativeLinksAndShareText() {
        assertEquals(NativeLink.Netease(123), NativeLink.parse("https://music.163.com/#/playlist?id=123"))
        assertEquals(NativeLink.Netease(456), NativeLink.parse("https://y.music.163.com/m/playlist?id=456&userid=1"))
        assertEquals(NativeLink.YouTube("PLabc_-1", true), NativeLink.parse("https://music.youtube.com/playlist?list=PLabc_-1"))
        assertEquals(NativeLink.BiliSeason(546195, 1903592), NativeLink.parse("https://space.bilibili.com/546195/lists/1903592?type=season"))
        assertEquals(NativeLink.BiliFav(77), NativeLink.parse("https://space.bilibili.com/9/favlist?fid=77&ftype=create"))
        assertEquals(NativeLink.BiliVideo("BV1xx411c7mD"), NativeLink.parse("https://www.bilibili.com/video/BV1xx411c7mD?p=2"))
        assertNull(NativeLink.parse("https://open.spotify.com/playlist/37i9dQZF1DX0XUsuxWHRQd"))
        assertEquals("https://y.qq.com/n/ryqq/playlist/7729596131", extractUrl("分享歌单《好歌》: https://y.qq.com/n/ryqq/playlist/7729596131 （来自@QQ音乐）"))
    }
}
