package app.pitstop.video

import app.pitstop.video.core.SourceId
import app.pitstop.video.podcast.PodcastParser
import app.pitstop.video.radio.RadioParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastRadioTest {
    // Trimmed from a real ximalaya.com feed: CDATA titles, a multi-line enclosure, "mm:ss", GMT dates.
    private val xmlyFeed = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:atom="http://www.w3.org/2005/Atom">
    <channel>
        <atom:link href="https://www.ximalaya.com/album/51076156.xml" type="application/rss+xml" rel="self"></atom:link>
        <title><![CDATA[声动早咖啡]]></title>
        <itunes:title><![CDATA[声动早咖啡]]></itunes:title>
        <itunes:author><![CDATA[声动活泼]]></itunes:author>
        <description><![CDATA[<p>一个十五分钟的晨间仪式</p>]]></description>
        <itunes:owner><itunes:name><![CDATA[声动活泼]]></itunes:name></itunes:owner>
        <itunes:image href="https://fdfs.xmcdn.com/storages/c5f1/show.jpeg"/>
        <item>
            <itunes:episode>1</itunes:episode>
            <title><![CDATA[咖啡豆｜理想、小米加快自研电池]]></title>
            <itunes:title><![CDATA[咖啡豆｜理想、小米加快自研电池]]></itunes:title>
            <description><![CDATA[<p>感谢收听</p>]]></description>
            <enclosure
                    length="15588391"
                    type="audio/x-m4a"
                    url="https://jt.ximalaya.com//A.m4a?channel=rss&amp;track_id=1032177219"
            />
            <guid>xmly_track_1032177219</guid>
            <pubDate>Thu, 12 Nov 2026 23:00:00 GMT</pubDate>
            <itunes:duration>14:48</itunes:duration>
            <itunes:image href="https://fdfs.xmcdn.com/storages/0983/ep1.jpeg"></itunes:image>
        </item>
        <item>
            <title><![CDATA[京东内测短剧产品]]></title>
            <enclosure length="11384279" type="audio/x-m4a" url="https://jt.ximalaya.com//B.m4a?track_id=1031917116"/>
            <pubDate>Wed, 11 Nov 2026 23:00:00 GMT</pubDate>
            <itunes:duration>10:25</itunes:duration>
        </item>
    </channel>
</rss>"""

    // Trimmed from a real simplecast feed (oldest item first here to check newest-first sorting).
    private val simplecastFeed = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
  <channel>
    <title>The Daily</title>
    <image><url>https://image.simplecastcdn.com/images/show.jpg</url><title>The Daily</title></image>
    <itunes:author>The New York Times</itunes:author>
    <item>
      <title>Older episode</title>
      <pubDate>Sun, 24 Oct 2021 10:00:00 +0000</pubDate>
      <enclosure length="1" type="audio/mpeg" url="https://dts.podtrac.com/old.mp3"/>
      <itunes:duration>1843</itunes:duration>
    </item>
    <item>
      <title>Newest episode</title>
      <pubDate>Fri, 13 Nov 2026 10:45:00 +0000</pubDate>
      <enclosure length="29490529" type="audio/mpeg" url="https://dts.podtrac.com/new.mp3?aid=rss_feed&amp;feed=Sl5CSM3S"/>
      <itunes:duration>00:30:43</itunes:duration>
    </item>
    <item>
      <title>No audio, skipped</title>
      <pubDate>Fri, 13 Nov 2026 11:00:00 +0000</pubDate>
    </item>
  </channel>
</rss>"""

    private fun parse(xml: String) = PodcastParser.parseRss(xml.byteInputStream(Charsets.UTF_8))

    @Test
    fun parsesXimalayaFeed() {
        val feed = parse(xmlyFeed)
        assertEquals("声动早咖啡", feed.title)
        assertEquals("声动活泼", feed.author)
        assertEquals("https://fdfs.xmcdn.com/storages/c5f1/show.jpeg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)
        val first = feed.episodes[0]
        assertEquals("咖啡豆｜理想、小米加快自研电池", first.title)
        assertEquals("https://jt.ximalaya.com//A.m4a?channel=rss&track_id=1032177219", first.audioUrl)
        assertEquals(14 * 60 + 48L, first.durationSec)
        assertEquals("https://fdfs.xmcdn.com/storages/0983/ep1.jpeg", first.imageUrl)
        assertEquals(1794524400000L, first.publishedMs)

        val items = PodcastParser.episodeItems(feed, "https://www.ximalaya.com/album/51076156.xml")
        assertEquals(2, items.size)
        assertEquals(SourceId.PODCASTS, items[0].source)
        assertEquals("声动早咖啡", items[1].author)
        assertEquals("https://www.ximalaya.com/album/51076156.xml", items[1].authorId)
        // An episode without its own image shows the show's cover.
        assertEquals("https://fdfs.xmcdn.com/storages/c5f1/show.jpeg", items[1].thumbnailUrl)
    }

    @Test
    fun episodesNewestFirstAndCapped() {
        val feed = parse(simplecastFeed)
        assertEquals("The Daily", feed.title)
        assertEquals("https://image.simplecastcdn.com/images/show.jpg", feed.imageUrl)
        assertEquals(2, feed.episodes.size)
        val items = PodcastParser.episodeItems(feed, "https://feeds.simplecast.com/Sl5CSM3S")
        assertEquals(listOf("Newest episode", "Older episode"), items.map { it.title })
        assertEquals(30 * 60 + 43L, items[0].durationSec)
        assertEquals(1843L, items[1].durationSec)
        assertEquals(1, PodcastParser.episodeItems(feed, "x", cap = 1).size)
        assertEquals(1, PodcastParser.parseRss(simplecastFeed.byteInputStream(), maxItems = 1).episodes.size)
    }

    @Test
    fun brokenFeedKeepsWhatWasRead() {
        val broken = simplecastFeed.replace("<title>Newest episode</title>", "<title>Bad &nbsp; entity</title>")
        val feed = parse(broken)
        assertEquals(listOf("Older episode"), feed.episodes.map { it.title })
    }

    @Test
    fun durations() {
        assertEquals(1843L, PodcastParser.parseDuration("1843"))
        assertEquals(1843L, PodcastParser.parseDuration(" 1843.6 "))
        assertEquals(888L, PodcastParser.parseDuration("14:48"))
        assertEquals(4 * 3600 + 60 + 20L, PodcastParser.parseDuration("04:01:20"))
        assertNull(PodcastParser.parseDuration(""))
        assertNull(PodcastParser.parseDuration("0"))
        assertNull(PodcastParser.parseDuration("about an hour"))
    }

    @Test
    fun dates() {
        assertEquals(1794566700000L, PodcastParser.parseDate("Fri, 13 Nov 2026 10:45:00 +0000"))
        assertEquals(1794524400000L, PodcastParser.parseDate("Thu, 12 Nov 2026 23:00:00 GMT"))
        // Zone names as written by libsyn.
        assertTrue(PodcastParser.parseDate("Fri, 31 Jul 2026 10:02:23 PST") != null)
        assertNull(PodcastParser.parseDate("yesterday"))
    }

    @Test
    fun chartAndLookup() {
        val chart = PodcastParser.chartShows("""{"feed":{"title":"Top Shows","country":"us","results":[
            {"artistName":"The New York Times","id":"1200361736","name":"The Daily","kind":"podcasts",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts221/v4/ab/mza_1.jpg/100x100bb.png"},
            {"artistName":"NPR","id":"1222114325","name":"Up First from NPR","kind":"podcasts",
             "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts211/v4/0e/mza_2.png/100x100bb.png"}]}}""")
        assertEquals(2, chart.size)
        assertEquals(PodcastParser.SHOW_PREFIX + "1200361736", chart[0].id)
        assertEquals("The New York Times", chart[0].author)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Podcasts221/v4/ab/mza_1.jpg/600x600bb.png", chart[0].thumbnailUrl)

        val lookup = PodcastParser.itunesShows("""{"resultCount":1,"results":[
            {"wrapperType":"track","kind":"podcast","collectionId":1200361736,"trackId":1200361736,
             "artistName":"The New York Times","collectionName":"The Daily","trackName":"The Daily",
             "feedUrl":"http://feeds.simplecast.com/Sl5CSM3S",
             "artworkUrl600":"https://is1-ssl.mzstatic.com/image/thumb/x/600x600bb.jpg"}]}""")
        val daily = lookup.getValue("1200361736")
        assertEquals(PodcastParser.SHOW_PREFIX + "https://feeds.simplecast.com/Sl5CSM3S", daily.id)
        assertEquals("https://feeds.simplecast.com/Sl5CSM3S", daily.authorId)
        assertEquals("The Daily", daily.author)

        val merged = PodcastParser.withFeedUrls(chart, lookup)
        assertEquals(daily.id, merged[0].id)
        assertEquals(chart[0].thumbnailUrl, merged[0].thumbnailUrl)
        assertEquals(chart[1], merged[1]) // not found: stays an Apple-id row
    }

    @Test
    fun stations() {
        val json = """[
          {"stationuuid":"595687e1-9fa0-49d7-8cb7-d6ed63f6d8b3","name":"Christmas FM Non-stop!",
           "url":"https://xmas-node1.zip.wf/live.aac","url_resolved":"https://xmas-node1.zip.wf/live.aac",
           "favicon":"https://zip.wf/chfm.jpg","tags":"advent,canti natalizi,carols","country":"Finland",
           "countrycode":"FI","hls":0,"lastcheckok":1},
          {"stationuuid":"24711f7f-8ff5-4141-8e0f-ab17f3da1b89","name":"怀集音乐之声",
           "url":"http://example.cn/live","url_resolved":"https://lhttp.qingting.fm/live/4804/64k.m3u8?x=1",
           "favicon":"http://www.hj0758.cn/favicon.ico","tags":"music","country":"China","hls":0,"lastcheckok":1},
          {"stationuuid":"aa","name":"Cleartext only","url":"http://a/b","url_resolved":"http://a/b","lastcheckok":1},
          {"stationuuid":"bb","name":"Broken","url_resolved":"https://a/b","lastcheckok":0},
          {"stationuuid":"cc","name":"HLS flag","url_resolved":"https://a/live","hls":1,"lastcheckok":1}
        ]"""
        val items = RadioParser.stations(json)
        assertEquals(listOf("595687e1-9fa0-49d7-8cb7-d6ed63f6d8b3", "24711f7f-8ff5-4141-8e0f-ab17f3da1b89", "cc"), items.map { it.id })
        val xmas = items[0]
        assertEquals(SourceId.RADIO, xmas.source)
        assertEquals("Christmas FM Non-stop!", xmas.title)
        assertEquals("Finland · advent · canti natalizi", xmas.author)
        assertEquals("https://zip.wf/chfm.jpg", xmas.thumbnailUrl)
        assertNull(xmas.durationSec)
        assertEquals("https://xmas-node1.zip.wf/live.aac", RadioParser.streamUrl(xmas))
        assertFalse(RadioParser.isHls(xmas))

        val cn = items[1]
        assertNull(cn.thumbnailUrl) // http favicon would be blocked
        assertTrue(RadioParser.isHls(cn))
        assertEquals("https://lhttp.qingting.fm/live/4804/64k.m3u8?x=1", RadioParser.streamUrl(cn))
        assertTrue(RadioParser.isHls(items[2]))
    }

    @Test
    fun servers() {
        val json = """[{"ip":"91.98.4.78","name":"de1.api.radio-browser.info"},
            {"ip":"2a01:4f8:1c1d:699::1","name":"de1.api.radio-browser.info"},{"ip":"1.2.3.4","name":"fi1.api.radio-browser.info"}]"""
        assertEquals(listOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info"), RadioParser.servers(json))
    }
}
