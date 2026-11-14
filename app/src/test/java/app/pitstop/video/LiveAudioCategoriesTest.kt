package app.pitstop.video

import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.live.DouyuParse
import app.pitstop.video.live.TwitchParse
import app.pitstop.video.podcast.PodcastParser
import app.pitstop.video.radio.RadioParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Categories, endless search and paging for Twitch, Douyu, Podcasts and Radio (trimmed real JSON, Nov 2026). */
class LiveAudioCategoriesTest {
    private val viewers: (Long) -> String = { "$it watching" }

    // --- Twitch ---

    @Test fun twitchCategories() {
        val games = JSONObject(
            """{"edges":[
              {"node":{"name":"Just Chatting","displayName":"Just Chatting","boxArtURL":"https://static-cdn.jtvnw.net/ttv-boxart/509658-144x192.jpg"}},
              {"node":{"name":"The Legend of Zelda: Ocarina of Time","displayName":"The Legend of Zelda: Ocarina of Time","boxArtURL":null}},
              {"node":{"name":null}}
            ]}""",
        )
        val cats = TwitchParse.categories(games)
        assertEquals(listOf("Just Chatting", "The Legend of Zelda: Ocarina of Time"), cats.map { it.key })
        assertEquals("https://static-cdn.jtvnw.net/ttv-boxart/509658-144x192.jpg", cats[0].thumbnailUrl)
        assertNull(cats[1].thumbnailUrl)
        assertNull(cats[0].group)
    }

    @Test fun twitchCategoryQueryUsesLanguageVariable() {
        val top = TwitchParse.gameStreamsQuery("Fortnite", first = 100)
        assertFalse(top.getJSONObject("variables").has("langs"))
        assertTrue(top.getString("query").contains("streams(first:100,"))
        val es = TwitchParse.gameStreamsQuery("Fortnite", "ES", first = 100)
        assertEquals("ES", es.getJSONObject("variables").getJSONArray("langs").getString(0))
        assertTrue(es.getString("query").contains("broadcasterLanguages:${'$'}langs"))
    }

    @Test fun twitchCategoryPaging() {
        assertEquals(1, TwitchParse.nextCategoryPage(0, 97))
        assertNull(TwitchParse.nextCategoryPage(0, 12)) // small category: the top page had them all
        assertEquals(5, TwitchParse.nextCategoryPage(4, 0)) // empty language: go on
        assertNull(TwitchParse.nextCategoryPage(TwitchParse.LANGUAGES.size, 50))
    }

    @Test fun twitchSearchCursor() {
        val page = JSONObject(
            """{"channels":{"cursor":"NTA=","pageInfo":{"hasNextPage":true},"edges":[
              {"item":{"login":"clintstevens","displayName":"ClintStevens","stream":{"title":"New Zelda 100% Slowrun",
                "viewersCount":4598,"previewImageURL":"https://static-cdn.jtvnw.net/previews-ttv/live_user_clintstevens-440x248.jpg"}}}
            ]}}""",
        )
        assertEquals("NTA=", TwitchParse.searchCursor(page))
        assertEquals(listOf("clintstevens"), TwitchParse.searchResults(page, viewers).map { it.id })
        val last = JSONObject("""{"channels":{"cursor":"MTAw","pageInfo":{"hasNextPage":false},"edges":[]}}""")
        assertNull(TwitchParse.searchCursor(last))
        val q = TwitchParse.searchQuery("zelda", "NTA=")
        assertEquals("NTA=", q.getJSONObject("variables").getString("c"))
        assertEquals("", TwitchParse.searchQuery("zelda").getJSONObject("variables").getString("c"))
    }

    @Test fun twitchSuggestions() {
        val s = JSONObject(
            """{"edges":[{"node":{"text":"Minecraft"}},{"node":{"text":"Minecraft Dungeons"}},
              {"node":{"text":"minecraft"}},{"node":{"text":"minecraft drops"}},{"node":{"text":null}}]}""",
        )
        assertEquals(listOf("Minecraft", "Minecraft Dungeons", "minecraft drops"), TwitchParse.suggestions(s))
    }

    // --- Douyu ---

    private val douyuCates = """{"code":0,"data":{
        "cate1Info":[{"cate1Id":1,"cate1Name":"网游竞技","shortName":"PCgame"},{"cate1Id":2,"cate1Name":"娱乐天地","shortName":"yl"}],
        "cate2Info":[
          {"cate1Id":1,"cate2Id":1,"cate2Name":"英雄联盟","shortName":"LOL","pic":"https://sta-op.douyucdn.cn/dycatr/f72e.png",
           "icon":"https://sta-op.douyucdn.cn/dycatr/1de1.png","smallIcon":"https://sta-op.douyucdn.cn/dycatr/e2c1.png","count":93,"isVertical":0},
          {"cate1Id":9,"cate2Id":1092,"cate2Name":"DNF手游","shortName":"mdnf","icon":"https://sta-op.douyucdn.cn/dycatr/8ac8.png","count":4},
          {"cate1Id":2,"cate2Id":208,"cate2Name":"一起看","shortName":"yqk","pic":"//sta-op.douyucdn.cn/dycatr/aa.png","icon":"","count":916},
          {"cate1Id":1,"cate2Id":181,"cate2Name":"王者荣耀","shortName":"wzry","icon":"https://sta-op.douyucdn.cn/dycatr/wz.png","count":400}]}}"""

    @Test fun douyuCategories() {
        val cats = DouyuParse.parseCategories(douyuCates) { "全部$it" }
        assertEquals(listOf("1_1", "2_181", "2_1", "1_2", "2_208"), cats.map { it.key })
        assertEquals("全部网游竞技", cats[0].title)
        assertEquals("网游竞技", cats[1].group)
        assertEquals("王者荣耀", cats[1].title)
        assertEquals("https://sta-op.douyucdn.cn/dycatr/1de1.png", cats[2].thumbnailUrl)
        assertEquals("https://sta-op.douyucdn.cn/dycatr/aa.png", cats[4].thumbnailUrl) // icon empty → pic
        assertTrue(cats.all { DouyuParse.isCategoryKey(it.key) })
        assertFalse(DouyuParse.isCategoryKey("3_1"))
        assertFalse(DouyuParse.isCategoryKey("2_../x"))
        assertEquals("/gapi/rkc/directory/mixList/2_208/3", DouyuParse.directoryPath("2_208", 3))
    }

    @Test fun douyuDirectoryPaging() {
        val json = """{"code":0,"msg":"","data":{"ct":{},"pgcnt":8,"rl":[{"type":1,"rid":4549169,"rn":"一起看电影",
            "nn":"某主播","ol":12345,"rs16":"https://rpic.douyucdn.cn/live-cover/a.jpg/dy1"}]}}"""
        assertEquals(8, DouyuParse.pageCount(json))
        assertEquals(1, DouyuParse.parseListPage(json).size)
        assertEquals(2, DouyuParse.nextPage(1, 120, 8, 150))
        assertNull(DouyuParse.nextPage(8, 120, 8, 150))
        assertNull(DouyuParse.nextPage(3, 0, 8, 150))
        assertEquals(4, DouyuParse.nextPage(3, 20, 0, 150)) // page count unknown: go on until empty
        assertNull(DouyuParse.nextPage(150, 20, 0, 150))
    }

    @Test fun douyuSearchTotal() {
        val json = """{"error":0,"msg":"请求成功","data":{"pageSize":20,"relateShow":[],"total":272}}"""
        assertEquals(272, DouyuParse.searchTotal(json))
    }

    // --- Podcasts ---

    @Test fun podcastGenreChart() {
        val json = """{"feed":{"entry":[
            {"im:name":{"label":"The Joe Rogan Experience"},
             "im:image":[{"label":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts211/v4/d8/97/mza_86.jpg/55x55bb.png","attributes":{"height":"55"}},
                         {"label":"https://is1-ssl.mzstatic.com/image/thumb/Podcasts211/v4/d8/97/mza_86.jpg/170x170bb.png","attributes":{"height":"170"}}],
             "id":{"label":"https://podcasts.apple.com/us/podcast/the-joe-rogan-experience/id360084272?uo=2","attributes":{"im:id":"360084272"}},
             "im:artist":{"label":"Joe Rogan"}},
            {"im:name":{"label":"No id"},"id":{"label":"x"}}]}}"""
        val shows = PodcastParser.legacyChartShows(json)
        assertEquals(1, shows.size)
        assertEquals(PodcastParser.SHOW_PREFIX + "360084272", shows[0].id)
        assertEquals("The Joe Rogan Experience", shows[0].title)
        assertEquals("Joe Rogan", shows[0].author)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Podcasts211/v4/d8/97/mza_86.jpg/600x600bb.png", shows[0].thumbnailUrl)
        // A one-show chart has `entry` as an object.
        val single = """{"feed":{"entry":{"im:name":{"label":"凹凸电波"},"id":{"attributes":{"im:id":"1"}}}}}"""
        assertEquals(listOf("凹凸电波"), PodcastParser.legacyChartShows(single).map { it.title })
        assertTrue(PodcastParser.legacyChartShows("""{"feed":{"author":{}}}""").isEmpty())
    }

    @Test fun podcastSearchGenreFilter() {
        val json = """{"resultCount":2,"results":[
            {"collectionId":1,"collectionName":"Stand up Comedy","feedUrl":"https://feeds.example.com/a","genreIds":["1303","26"]},
            {"collectionId":2,"collectionName":"Comedy History","feedUrl":"https://feeds.example.com/b","genreIds":["1487","26"]}]}"""
        assertEquals(2, PodcastParser.itunesShows(json).size)
        assertEquals(listOf("Stand up Comedy"), PodcastParser.itunesShows(json, genreId = 1303).values.map { it.title })
        assertEquals(19, PodcastParser.GENRES.distinct().size)
    }

    @Test fun podcastEpisodePages() {
        val all = (1..120).map { VideoItem(SourceId.PODCASTS, "https://x/$it.mp3", "Ep $it") }
        val p1 = PodcastParser.episodePage(all, 0, 50)
        assertEquals(50, p1.items.size)
        assertEquals("50", p1.next)
        val p3 = PodcastParser.episodePage(all, 100, 50)
        assertEquals(20, p3.items.size)
        assertNull(p3.next)
        assertTrue(PodcastParser.episodePage(all, 500, 50).items.isEmpty())
    }

    // --- Radio ---

    @Test fun radioCategories() {
        val tags = """[{"name":"pop","stationcount":5101},{"name":"music","stationcount":3613},{"name":"punk","stationcount":99}]"""
        val countries = """[{"name":"The United States Of America","iso_3166_1":"US","stationcount":6922},
            {"name":"Germany","iso_3166_1":"DE","stationcount":5592},{"name":"Nowhere","iso_3166_1":"","stationcount":50},
            {"name":"Aruba","iso_3166_1":"AW","stationcount":9}]"""
        val languages = """[{"name":"english","iso_639":"en","stationcount":11258},{"name":"yiddish","iso_639":"yi","stationcount":3}]"""
        val t = RadioParser.tagCategories(tags, "Genres")
        assertEquals(listOf("tag:pop", "tag:music"), t.map { it.key })
        assertEquals("Genres", t[0].group)
        val c = RadioParser.countryCategories(countries, "Countries")
        assertEquals(listOf("country:US", "country:DE"), c.map { it.key })
        assertEquals("The United States Of America", c[0].title)
        assertEquals(listOf("lang:english"), RadioParser.languageCategories(languages, "Languages").map { it.key })
    }

    @Test fun radioCategoryFilters() {
        assertEquals(listOf("tag" to "jazz", "tagExact" to "true"), RadioParser.categoryFilter("tag:jazz"))
        assertEquals(listOf("countrycode" to "FR"), RadioParser.categoryFilter("country:FR"))
        assertEquals(listOf("language" to "german", "languageExact" to "true"), RadioParser.categoryFilter("lang:german"))
        assertNull(RadioParser.categoryFilter("tag:"))
        assertNull(RadioParser.categoryFilter("bogus"))
    }
}
