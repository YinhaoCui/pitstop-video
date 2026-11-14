package app.pitstop.video.core

import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-app UI language (independent of the system language). English is the default. */
enum class AppLanguage(val tag: String, val label: String) {
    EN("en", "English"),
    ZH("zh-CN", "简体中文"),
}

object I18n {
    private var prefs: SharedPreferences? = null
    private val _language = MutableStateFlow(AppLanguage.EN)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    /** Strings for the current language; read at render time by phone and car UIs. */
    val s: Strings get() = Strings(_language.value)

    fun init(p: SharedPreferences) {
        prefs = p
        _language.value = AppLanguage.entries.firstOrNull { it.name == p.getString("language", null) } ?: AppLanguage.EN
    }

    fun set(lang: AppLanguage) {
        prefs?.edit()?.putString("language", lang.name)?.apply()
        _language.value = lang
    }
}

val LocalStrings = staticCompositionLocalOf { Strings(AppLanguage.EN) }

/** Every user-visible text, English first, Simplified Chinese second. */
class Strings(val language: AppLanguage) {
    private fun t(en: String, zh: String) = if (language == AppLanguage.ZH) zh else en

    // Sources and feeds
    val youtube get() = "YouTube"
    val bilibili get() = t("Bilibili", "哔哩哔哩")
    val onThisDevice get() = t("On this device", "本机视频")
    val popular get() = t("Popular", "热门")
    val subscriptions get() = t("Subscriptions", "订阅")
    val liked get() = t("Liked videos", "赞过的视频")
    val recommended get() = t("Recommended", "推荐")
    val following get() = t("Following", "关注动态")
    val allVideos get() = t("All videos", "全部视频")
    fun sourceName(id: SourceId) = when (id) {
        SourceId.YOUTUBE -> youtube
        SourceId.BILIBILI -> bilibili
        SourceId.LOCAL -> onThisDevice
        SourceId.BILI_MUSIC -> biliMusic
        SourceId.YT_MUSIC -> ytMusic
        SourceId.NETEASE -> netease
        SourceId.GDRIVE -> googleDrive
        SourceId.LOCAL_MUSIC -> musicOnDevice
        SourceId.PEERTUBE -> "PeerTube"
        SourceId.SOUNDCLOUD -> "SoundCloud"
        SourceId.BANDCAMP -> "Bandcamp"
        SourceId.PODCASTS -> podcasts
        SourceId.RADIO -> radio
        SourceId.BILI_LIVE -> biliLive
        SourceId.TWITCH -> "Twitch"
        SourceId.DOUYU -> douyu
    }
    val podcasts get() = t("Podcasts", "播客")
    val radio get() = t("Radio", "电台")
    val biliLive get() = t("Bilibili Live", "B站直播")
    val douyu get() = t("Douyu", "斗鱼")
    val live get() = t("Live", "直播")
    val topCharts get() = t("Top charts", "排行榜")
    val newReleases get() = t("New releases", "新发布")
    val featured get() = t("Featured", "精选")
    val radioTop get() = t("Most popular", "最受欢迎")
    val radioLocal get() = t("Near you", "本地电台")
    val radioChinese get() = t("Chinese stations", "中文电台")
    val streamOffline get() = t("This channel isn't live right now.", "该主播当前未开播。")
    val liveUnavailable get() = t("Couldn't get this live stream; the site may have changed.", "无法获取直播流，网站可能已更改。")
    fun viewers(n: Long) = t(if (n >= 1000) "%.1fk watching".format(n / 1000.0) else "$n watching", if (n >= 10000) "%.1f万人观看".format(n / 10000.0) else "${n}人观看")

    // --- Strings for Bilibili Live + Twitch (add new ones here) ---

    // --- Strings for Douyu (add new ones here) ---

    // --- Strings for Podcasts + Radio (add new ones here) ---
    val podcastNoFeed get() = t("This podcast has no public feed", "该播客没有公开的订阅源")
    val radioNoStream get() = t("This station has no playable stream", "该电台没有可播放的音频流")

    // --- Categories & search ---
    val categories get() = t("Categories", "分类")
    val allCategories get() = t("All categories", "全部分类")
    val filterCategories get() = t("Filter categories", "筛选分类")
    val noCategories get() = t("No categories here.", "这里没有分类。")
    val recentSearches get() = t("Recent searches", "最近搜索")
    val clearHistory get() = t("Clear", "清除")
    val clearCategory get() = t("Clear category", "取消分类")
    val searchResults get() = t("Search results", "搜索结果")

    // --- Playlist import / export, search everywhere ---
    val importPlaylist get() = t("Import", "导入")
    val importTitle get() = t("Import a playlist", "导入歌单")
    val importHint get() = t("Paste a playlist link (NetEase, QQ Music, Kugou, Spotify, Apple Music, YouTube, Bilibili) or a list of songs, one \"Artist - Title\" per line.", "粘贴歌单链接（网易云、QQ音乐、酷狗、Spotify、Apple Music、YouTube、B站），或歌曲列表，每行一首“歌手 - 歌名”。")
    val importOpenFile get() = t("Open file", "打开文件")
    val importAsMusic get() = t("Songs", "歌曲")
    val importAsVideo get() = t("Videos", "视频")
    val importMatchOn get() = t("Find songs on", "在这些平台查找")
    val importStart get() = t("Import", "开始导入")
    val importReading get() = t("Reading the playlist…", "正在读取歌单…")
    fun importMatching(done: Int, total: Int) = t("Finding songs: $done / $total", "正在匹配歌曲：$done / $total")
    fun importSummary(found: Int, total: Int) = t("Found $found of $total", "找到 $found / $total 首")
    val importNotMatched get() = t("Not found", "未找到")
    val importSave get() = t("Save playlist", "保存歌单")
    val importName get() = t("Playlist name", "歌单名称")
    val importUnknownLink get() = t("This link isn't a playlist Pitstop can read.", "无法识别这个歌单链接。")
    val importNothingFound get() = t("No songs found in this text.", "没有在文本中找到歌曲。")
    val importText get() = t("Text", "文本")
    fun importedPlaylists(n: Int) = t("Imported $n playlist(s)", "已导入 $n 个歌单")
    fun importFrom(platform: String) = t("From $platform", "来自 $platform")
    val exportPlaylist get() = t("Export", "导出")
    val exportPitstop get() = t("Pitstop file (re-importable)", "Pitstop 文件（可再次导入）")
    val exportCsv get() = t("CSV spreadsheet", "CSV 表格")
    val exportText get() = t("Text (Artist - Title)", "文本（歌手 - 歌名）")
    val exportAll get() = t("Export all", "全部导出")
    val exported get() = t("Saved", "已保存")
    val searchEverywhere get() = t("All platforms", "全部平台")
    val moreResults get() = t("More", "更多")

    // --- Category strings: Bilibili family (add new ones here) ---

    // --- Category strings: YouTube / YouTube Music / SoundCloud / Bandcamp / PeerTube (add new ones here) ---
    val catTrending get() = t("Trending", "时下流行")
    val catTopics get() = t("Topics", "主题")
    val catGenres get() = t("Genres", "流派")
    val catMoods get() = t("Moods & activities", "心情与场景")

    /** YouTube trending kiosks and topic categories; null for an unknown key. */
    fun ytCategory(key: String): String? = when (key) {
        "Trending" -> t("Trending now", "时下流行")
        "trending_music" -> t("Trending music", "热门音乐")
        "trending_gaming" -> t("Trending gaming", "热门游戏")
        "trending_movies_and_shows" -> t("Movies & trailers", "电影与预告片")
        "trending_podcasts_episodes" -> t("Podcasts", "播客")
        "live" -> t("Live now", "正在直播")
        "news" -> t("News", "新闻")
        "sports" -> t("Sports", "体育")
        "music" -> t("Music", "音乐")
        "gaming" -> t("Gaming", "游戏")
        "comedy" -> t("Comedy", "搞笑")
        "education" -> t("Education", "教育")
        "science" -> t("Science & tech", "科技")
        "travel" -> t("Travel", "旅行")
        "cooking" -> t("Cooking", "美食")
        "cars" -> t("Cars", "汽车")
        "kids" -> t("Kids", "儿童")
        "documentary" -> t("Documentaries", "纪录片")
        "fitness" -> t("Fitness", "健身")
        "animals" -> t("Animals", "动物")
        "diy" -> t("DIY & crafts", "手工 DIY")
        "fashion" -> t("Fashion & beauty", "时尚美妆")
        else -> null
    }

    /** What a YouTube topic category (or a trending list once it runs out) searches for, in the app language. */
    fun ytTopicQuery(key: String): String = when (key) {
        "news" -> t("news today", "今日新闻")
        "sports" -> t("sports highlights", "体育集锦")
        "music" -> t("official music video", "音乐 MV")
        "gaming" -> t("gameplay", "游戏实况")
        "comedy" -> t("comedy", "搞笑视频")
        "education" -> t("explained", "知识科普")
        "science" -> t("science and technology", "科技")
        "travel" -> t("travel vlog", "旅行 vlog")
        "cooking" -> t("cooking recipe", "美食做法")
        "cars" -> t("car review", "汽车评测")
        "kids" -> t("kids cartoons", "儿童动画")
        "documentary" -> t("documentary", "纪录片")
        "fitness" -> t("workout", "健身")
        "animals" -> t("funny animals", "萌宠动物")
        "diy" -> t("DIY", "手工 DIY")
        "fashion" -> t("fashion", "时尚穿搭")
        "trailers" -> t("official trailer", "电影预告片")
        "podcasts" -> t("podcast", "播客")
        "live" -> t("live", "直播")
        "popular" -> t("trending", "热门")
        else -> ytCategory(key) ?: key
    }

    /** Music genre / mood names (YouTube Music, SoundCloud, Bandcamp); null for an unknown key. */
    fun musicGenre(key: String): String? = when (key) {
        "pop" -> t("Pop", "流行")
        "rock" -> t("Rock", "摇滚")
        "hiphop" -> t("Hip-hop", "嘻哈说唱")
        "rnb" -> t("R&B & soul", "R&B 与灵魂乐")
        "jazz" -> t("Jazz", "爵士")
        "classical" -> t("Classical", "古典")
        "electronic" -> t("Electronic", "电子")
        "lofi" -> t("Lo-fi", "Lo-fi")
        "kpop" -> t("K-pop", "韩流")
        "cpop" -> t("C-pop", "华语流行")
        "jpop" -> t("J-pop", "日语流行")
        "mandopop" -> t("Mandopop", "国语流行")
        "cantopop" -> t("Cantopop", "粤语流行")
        "country" -> t("Country", "乡村")
        "metal" -> t("Metal", "金属")
        "soundtrack" -> t("Soundtracks", "影视原声")
        "folk" -> t("Folk", "民谣")
        "indie" -> t("Indie", "独立音乐")
        "alternative" -> t("Alternative", "另类")
        "blues" -> t("Blues", "蓝调")
        "reggae" -> t("Reggae", "雷鬼")
        "latin" -> t("Latin", "拉丁")
        "punk" -> t("Punk", "朋克")
        "ambient" -> t("Ambient", "氛围音乐")
        "dance" -> t("Dance & EDM", "舞曲与 EDM")
        "house" -> t("House", "浩室")
        "techno" -> t("Techno", "科技舞曲")
        "funk" -> t("Funk", "放克")
        "experimental" -> t("Experimental", "实验音乐")
        "world" -> t("World", "世界音乐")
        "acoustic" -> t("Acoustic", "原声")
        "kids" -> t("Kids", "儿歌")
        "workout" -> t("Workout", "健身")
        "chill" -> t("Chill", "放松")
        "focus" -> t("Focus", "专注")
        "sleep" -> t("Sleep", "睡眠")
        "party" -> t("Party", "派对")
        "romance" -> t("Romance", "浪漫情歌")
        "driving" -> t("Driving", "开车听")
        "happy" -> t("Feel good", "开心")
        "sad" -> t("Sad", "伤感")
        else -> null
    }

    /** What a music genre / mood searches for, in the app language. */
    fun musicGenreQuery(key: String): String = when (key) {
        "pop" -> t("pop hits", "流行歌曲")
        "rock" -> t("rock songs", "摇滚")
        "hiphop" -> t("hip hop", "说唱")
        "rnb" -> t("r&b soul", "R&B")
        "jazz" -> t("jazz", "爵士乐")
        "classical" -> t("classical music", "古典音乐")
        "electronic" -> t("electronic music", "电子音乐")
        "lofi" -> t("lofi", "lofi")
        "kpop" -> t("kpop", "kpop")
        "cpop" -> t("cpop", "华语流行")
        "jpop" -> t("jpop", "日语歌")
        "mandopop" -> t("mandopop", "国语流行歌曲")
        "cantopop" -> t("cantopop", "粤语歌")
        "country" -> t("country music", "乡村音乐")
        "metal" -> t("metal", "金属乐")
        "soundtrack" -> t("movie soundtrack", "影视原声")
        "folk" -> t("folk music", "民谣")
        "indie" -> t("indie", "独立音乐")
        "alternative" -> t("alternative rock", "另类摇滚")
        "blues" -> t("blues", "蓝调")
        "reggae" -> t("reggae", "雷鬼")
        "latin" -> t("latin hits", "拉丁音乐")
        "punk" -> t("punk rock", "朋克")
        "ambient" -> t("ambient", "氛围音乐")
        "dance" -> t("dance edm", "电音舞曲")
        "house" -> t("house music", "house 音乐")
        "techno" -> t("techno", "techno")
        "funk" -> t("funk", "放克")
        "experimental" -> t("experimental music", "实验音乐")
        "world" -> t("world music", "世界音乐")
        "acoustic" -> t("acoustic", "原声吉他")
        "kids" -> t("kids songs", "儿歌")
        "workout" -> t("workout music", "健身音乐")
        "chill" -> t("chill music", "放松音乐")
        "focus" -> t("focus music", "专注音乐")
        "sleep" -> t("sleep music", "助眠音乐")
        "party" -> t("party songs", "派对歌曲")
        "romance" -> t("love songs", "情歌")
        "driving" -> t("driving songs", "开车音乐")
        "happy" -> t("happy songs", "欢快歌曲")
        "sad" -> t("sad songs", "伤感歌曲")
        else -> musicGenre(key) ?: key
    }

    // --- Category strings: Twitch / Douyu / Podcasts / Radio (add new ones here) ---
    /** Douyu: the "everything in this area" entry heading each parent area (e.g. 网游竞技). */
    fun douyuAllOf(area: String) = t("All of $area", "全部$area")
    val radioGenres get() = t("Genres", "流派")
    val radioCountries get() = t("Countries", "国家和地区")
    val radioLanguages get() = t("Languages", "语言")
    /** Apple podcast genre names by genre id (PodcastParser.GENRES). */
    fun podcastGenre(id: Int) = when (id) {
        1301 -> t("Arts", "艺术")
        1321 -> t("Business", "商业")
        1303 -> t("Comedy", "喜剧")
        1304 -> t("Education", "教育")
        1483 -> t("Fiction", "小说")
        1511 -> t("Government", "政府")
        1512 -> t("Health & Fitness", "健康与健身")
        1487 -> t("History", "历史")
        1305 -> t("Kids & Family", "儿童与家庭")
        1502 -> t("Leisure", "休闲")
        1310 -> t("Music", "音乐")
        1489 -> t("News", "新闻")
        1314 -> t("Religion & Spirituality", "宗教与灵修")
        1533 -> t("Science", "科学")
        1324 -> t("Society & Culture", "社会与文化")
        1545 -> t("Sports", "体育")
        1309 -> t("TV & Film", "电视与电影")
        1318 -> t("Technology", "科技")
        1488 -> t("True Crime", "真实罪案")
        else -> id.toString()
    }

    // --- Category strings: NetEase (add new ones here) ---
    // Playlist importers (QQ Music / Kugou / Spotify / Apple Music)
    val importBadLink get() = t("This link is not a playlist that can be imported.", "这个链接不是可导入的歌单。")
    val importNotFound get() = t("Playlist not found, or it is private. Make it public and try again.", "歌单不存在或未公开，请设为公开后再试。")
    val importEmpty get() = t("No readable tracks in this playlist.", "这个歌单里没有可读取的歌曲。")
    val importFailed get() = t("Could not read the playlist right now. Try again later.", "暂时无法读取歌单，请稍后再试。")
    val importKugouList get() = t("Kugou playlist", "酷狗歌单")
    val neteaseCharts get() = t("Charts", "排行榜")

    // --- Strings for SoundCloud / Bandcamp / PeerTube (add new ones here) ---
    val newAndHot get() = t("New & hot", "新歌热榜")
    val bandcampRadio get() = t("Bandcamp Radio", "Bandcamp 电台")
    val mostLiked get() = t("Most liked", "最多点赞")
    val peertubeLocal get() = t("This instance", "本站")
    val noAudioStream get() = t("No playable audio for this track.", "这首歌没有可播放的音频。")
    val noVideoStream get() = t("No playable stream for this video.", "这个视频没有可播放的流。")

    // Music
    val video get() = t("Video", "视频")
    val music get() = t("Music", "音乐")
    val biliMusic get() = t("Bilibili Music", "B站音乐")
    val ytMusic get() = "YouTube Music"
    val netease get() = t("NetEase Music", "网易云音乐")
    val googleDrive get() = "Google Drive"
    val musicOnDevice get() = t("On this phone", "本机音乐")
    val musicRanking get() = t("Music charts", "音乐排行")
    val musicHot get() = t("Hot songs", "热歌榜")
    val musicNew get() = t("New songs", "新歌榜")
    val musicRising get() = t("Rising", "飙升榜")
    val musicPlaylists get() = t("Playlists for you", "推荐歌单")
    val dailySongs get() = t("Daily picks", "每日推荐")
    val myPlaylists get() = t("My playlists", "我的歌单")
    val topSongs get() = t("Top songs", "热门歌曲")
    val likedMusic get() = t("Liked music", "喜欢的音乐")
    val allMusic get() = t("All music", "全部音乐")
    val folders get() = t("Folders", "文件夹")
    val allSongs get() = t("All songs", "全部歌曲")
    val downloads get() = t("Downloads", "下载")
    val download get() = t("Download", "下载")
    val downloadTitle get() = t("Download", "下载")
    val downloadFormat get() = t("Format", "格式")
    val downloadAudioOnly get() = t("Audio only", "仅音频")
    fun downloadVideoP(p: Int) = t("Video ${p}p", "视频 ${p}p")
    val downloadSaveTo get() = t("Save to", "保存到")
    val downloadToPhone get() = t("This phone (Download/Pitstop)", "本机（Download/Pitstop）")
    val downloadPhoneShort get() = t("Phone", "本机")
    // Sort & filter
    val sortFilter get() = t("Sort & filter", "排序与筛选")
    fun onPlatform(name: String) = t("From $name", "来自$name")
    val inThisList get() = t("In this list", "当前列表")
    val inThisListHint get() = t("Applies to what has loaded so far; more keeps loading as you scroll.", "作用于已加载的内容，继续下滑会加载更多。")
    val optSort get() = t("Sort by", "排序")
    val optLength get() = t("Length", "时长")
    val optUploaded get() = t("Uploaded", "上传时间")
    val optAny get() = t("Any", "不限")
    val optRelevance get() = t("Relevance", "综合")
    val optNewest get() = t("Newest", "最新发布")
    val optMostPlayed get() = t("Most played", "最多播放")
    val optMostViewed get() = t("Most viewed", "最多观看")
    val optTopRated get() = t("Top rated", "评分最高")
    val optMostDanmaku get() = t("Most danmaku", "最多弹幕")
    val optMostFavourited get() = t("Most favourited", "最多收藏")
    val optTopVoted get() = t("Top voted", "最多点赞")
    val optName get() = t("Name", "名称")
    val optRecentlyUpdated get() = t("Recently updated", "最近更新")
    val optLastHour get() = t("Last hour", "1 小时内")
    val optToday get() = t("Today", "今天")
    val optThisWeek get() = t("This week", "本周")
    val optThisMonth get() = t("This month", "本月")
    val optThisYear get() = t("This year", "今年")
    fun optUnderMin(n: Int) = t("Under $n min", "$n 分钟以下")
    fun optMinRange(a: Int, b: Int) = t("$a–$b min", "$a–$b 分钟")
    fun optOverMin(n: Int) = t("Over $n min", "$n 分钟以上")
    val sortAsListed get() = t("As listed", "默认顺序")
    val sortTitle get() = t("Title A–Z", "标题 A–Z")
    val sortAuthor get() = t("Creator", "作者")
    val sortShortest get() = t("Shortest first", "最短优先")
    val sortLongest get() = t("Longest first", "最长优先")
    val filterTextHint get() = t("Only titles or creators containing…", "仅显示标题或作者包含…")
    val filterPlatforms get() = t("Platforms", "平台")
    val filterReset get() = t("Reset", "重置")
    val keepThisOrder get() = t("Keep this order", "保存此顺序")
    val filterDone get() = t("Done", "完成")
    fun hiddenByFilters(n: Int) = t("$n hidden by filters", "$n 项被筛选隐藏")
    val biliSongUnavailable get() = t("Bilibili doesn't offer this song in your region, and it wasn't found on YouTube Music or NetEase.", "该歌曲在你所在地区无法通过哔哩哔哩播放，且未在 YouTube Music 或网易云音乐找到。")
    val downloadToPhoneOnly get() = t("This phone", "本机")
    val downloadToDriveOnly get() = "Google Drive"
    fun downloadFolder(name: String) = t("Folder: $name", "文件夹：$name")
    val downloadChangeFolder get() = t("Change", "更改")
    val downloadDefaultFolder get() = t("Default", "默认")
    val downloadPhoneDefault get() = "Download/Pitstop"
    val downloadDriveDefault get() = t("Pitstop (made by the app)", "Pitstop（应用自动创建）")
    val downloadFolders get() = t("Download folders", "下载文件夹")
    val drivePickAFolder get() = t("Pick a folder (not a file) in the Drive picker.", "请在 Drive 选择器中选择一个文件夹（而不是文件）。")
    fun downloadFolderLost(name: String) = t("Can't write to \"$name\" any more. Pick the phone folder again.", "无法再写入“$name”，请重新选择手机文件夹。")
    val downloadToDrive get() = t("Google Drive (Pitstop folder)", "Google Drive（Pitstop 文件夹）")
    val downloadStart get() = t("Download", "开始下载")
    val downloadStarted get() = t("Download started. Progress is in Settings → Downloads and in the notification.", "已开始下载，进度见 设置 → 下载 和通知栏。")
    val downloadNothing get() = t("Nothing downloaded yet. Use the download button in the player or in a video's + menu.", "还没有下载。可在播放器或视频的 + 菜单中点击下载。")
    val downloadClearFinished get() = t("Clear finished", "清除已完成")
    val downloadOpen get() = t("Open", "打开")
    val downloadPreparing get() = t("Preparing download…", "正在准备下载…")
    val driveUploadSignIn get() = t("Allow Google Drive access to save downloads there.", "请允许访问 Google Drive 以保存下载。")
    val cantDownloadStream get() = t("This can't be downloaded (live or streaming-only).", "该内容无法下载（直播或仅支持在线播放）。")
    val cantMerge get() = t("This video's picture and sound can't be merged into one file.", "该视频的画面和声音无法合并为一个文件。")
    fun moreQueued(n: Int) = t("$n more queued", "还有 $n 个排队中")
    fun downloadsFinished(done: Int, failed: Int) =
        if (failed == 0) t("$done downloads finished", "$done 个下载已完成")
        else t("$done finished, $failed failed", "$done 个完成，$failed 个失败")
    fun downloadStage(stage: app.pitstop.video.download.DownloadTask.Stage) = when (stage) {
        app.pitstop.video.download.DownloadTask.Stage.QUEUED -> t("Waiting", "等待中")
        app.pitstop.video.download.DownloadTask.Stage.PREPARING -> t("Preparing", "准备中")
        app.pitstop.video.download.DownloadTask.Stage.DOWNLOADING -> t("Downloading", "下载中")
        app.pitstop.video.download.DownloadTask.Stage.MERGING -> t("Merging picture and sound", "合并画面与声音")
        app.pitstop.video.download.DownloadTask.Stage.SAVING -> t("Saving to phone", "保存到本机")
        app.pitstop.video.download.DownloadTask.Stage.UPLOADING -> t("Uploading to Google Drive", "上传到 Google Drive")
        app.pitstop.video.download.DownloadTask.Stage.DONE -> t("Done", "已完成")
        app.pitstop.video.download.DownloadTask.Stage.FAILED -> t("Failed", "失败")
        app.pitstop.video.download.DownloadTask.Stage.CANCELLED -> t("Cancelled", "已取消")
    }
    fun tracks(n: Int) = t("$n tracks", "$n 首")
    val collection get() = t("Playlist", "歌单")
    val folder get() = t("Folder", "文件夹")
    val allowMusicAccess get() = t("Allow music access", "允许访问音乐")
    val allowMusicText get() = t("Play songs saved on this phone, including files you downloaded from Google Drive.", "播放手机里的歌曲，包括从 Google Drive 下载的文件。")
    val allowMusicHint get() = t("Allow music access in the Music tab first", "请先在“音乐”页允许访问音乐")
    val signIn get() = t("Sign in", "登录")
    val signInFor get() = t("Sign in to see your own music", "登录后可看到你自己的音乐")
    fun neteaseSignedIn(name: String?) = t("Signed in${name?.let { " as $it" } ?: ""}. Daily picks, your playlists and VIP quality are on.", "已登录${name?.let { "：$it" } ?: ""}。可用每日推荐、我的歌单和会员音质。")
    val neteaseSignedOut get() = t("Free songs play without an account. Sign in for daily picks, your playlists and VIP songs.", "免费歌曲不用登录。登录后可用每日推荐、我的歌单和 VIP 歌曲。")
    val signOutNetease get() = t("Sign out of NetEase", "退出网易云音乐")
    val signInPhoneWeb get() = t("Sign in (phone / password)", "手机号 / 密码登录")
    val neteaseQrHint get() = t("Scan with the NetEase Cloud Music app on another phone.", "请在另一台手机上用网易云音乐 App 扫码。")
    val neteaseVip get() = t("NetEase blocks this song here. It needs VIP, or plays only in mainland China.", "网易云在此无法播放这首歌：需要 VIP，或仅限中国大陆。")
    val needNeteaseLogin get() = t("Sign in to NetEase Cloud Music in the Music tab first", "请先在“音乐”页登录网易云音乐")
    val driveSignedIn get() = t("Signed in. Audio files in your Drive stream from here.", "已登录。Drive 里的音频文件可直接在线播放。")
    val driveSignedOut get() = t("Sign in to stream music stored in your Google Drive folders.", "登录后可在线播放 Google Drive 文件夹中的音乐。")
    val signOutDrive get() = t("Sign out of Google Drive", "退出 Google Drive")
    val needDriveLogin get() = t("Sign in to Google Drive in the Music tab first", "请先在“音乐”页登录 Google Drive")
    val finishDriveSignIn get() = t("Finish Google Drive sign-in on the phone", "请在手机上完成 Google Drive 登录")
    val driveGooglePicked get() = t("Drive picker", "Drive 选择器")
    val driveGooglePick get() = t("Google Drive picker", "Google Drive 选择器")
    val drivePickAgain get() = t("Open the Drive picker again to restore access", "请重新打开 Drive 选择器以恢复访问")
    val drivePickerFolderEmpty get() = t("No songs found in this folder (or the picker gave no access to them)", "此文件夹中没有找到歌曲（或选择器未授予访问权限）")
    fun drivePickerFailed(why: String?) = t("Drive picker failed: $why", "Drive 选择器失败：$why")
    fun drivePickerAdded(n: Int) = t("Added $n from Drive", "已从 Drive 添加 $n 项")
    val drivePicked get() = t("Picked songs", "已选歌曲")
    val drivePickHint get() = t("No sign-in? Tap Add songs, open the menu (☰) in the picker and choose Google Drive.", "不想登录？点“添加歌曲”，在文件选择器里打开菜单（☰）选择 Google Drive。")
    val driveAddSongs get() = t("Add songs", "添加歌曲")
    val driveClear get() = t("Clear list", "清空列表")
    val ytMusicSignedOut get() = t("Sign in with Google to add your liked songs and playlists.", "用 Google 登录，加入你喜欢的歌曲和歌单。")
    val biliMusicLogin get() = t("Sign in to Bilibili for higher audio quality.", "登录哔哩哔哩可获得更高音质。")

    // Creators
    val saveCreator get() = t("Save", "收藏")
    val savedCreator get() = t("Saved", "已收藏")
    fun creatorVideos(name: String) = t("Videos from $name", "$name 的视频")
    val creatorHint get() = t("Tap a creator's name to see their videos.", "点击作者名可查看其所有视频。")

    // Common
    val playlists get() = t("Playlists", "播放列表")
    val account get() = t("Settings", "设置")
    val accounts get() = t("Accounts", "账户")
    val browse get() = t("Browse", "浏览")
    val queue get() = t("Queue", "队列")
    fun queueCount(pos: Int, total: Int) = t("Queue ($pos/$total)", "队列（$pos/$total）")
    val search get() = t("Search", "搜索")
    fun searchIn(name: String) = t("Search $name", "搜索$name")
    fun searchFor(q: String) = t("Search: $q", "搜索：$q")
    val typeSearch get() = t("Type a search and press enter", "输入关键词后按回车")
    val split get() = t("Split", "分屏")
    val full get() = t("Full", "全屏")
    val upNext get() = t("Up next", "接下来")
    fun upNextCount(n: Int) = t("Up next ($n)", "接下来（$n）")
    val noVideos get() = t("No videos", "没有视频")
    val nothingPlaying get() = t("Nothing playing", "未在播放")
    val loading get() = t("Loading…", "加载中…")
    val buffering get() = t("Buffering…", "缓冲中…")
    val reconnecting get() = t("Connection lost. Reconnecting…", "网络中断，正在重新连接…")
    fun qualityLowered(h: Int) = t("Slow connection: switched to ${h}p", "网络较慢，已切换到 ${h}p")
    val nowPlaying get() = t("Now playing", "正在播放")
    val carPickSomething get() = t("Pick something in Browse.", "在“浏览”里选一个开始播放。")
    val close get() = t("Close", "关闭")
    val cancel get() = t("Cancel", "取消")
    val delete get() = t("Delete", "删除")
    val retry get() = t("Retry", "重试")
    val back get() = t("Back", "返回")
    val ok get() = t("OK", "确定")
    val exitApp get() = t("Exit Pitstop Video", "退出 Pitstop Video")
    fun cc(label: String?) = t("CC: ${label ?: "Off"}", "字幕：${label ?: "关"}")

    // Car screen
    val tapBrowse get() = t("Tap Browse to pick a video,\nor choose one on your phone.", "点击“浏览”选择视频，\n或在手机上选择。")
    val pausedMoving get() = t("Video is paused while the car is moving.\nPark to keep watching.", "车辆行驶中，视频已暂停。\n停车后可继续观看。")
    val nothingQueued get() = t("Nothing queued. Pick videos in Browse.", "队列为空。请在“浏览”中选择视频。")
    val noPlaylistsCar get() = t("No playlists yet. Create them in the phone app.", "还没有播放列表。请在手机上创建。")

    // Phone player
    val appName get() = t("Pitstop Video", "Pitstop 视频")
    val pickVideoHint get() = t("It plays here or on the car screen.", "可在这里或车机屏幕上播放。")
    val pickVideo get() = t("Pick something to play", "选一个开始播放")
    val pausedCarMoving get() = t("Paused while the car moves", "车辆行驶中，已暂停")
    val playingOnCar get() = t("Playing on the car display", "正在车机屏幕上播放")
    val playPause get() = t("Play/pause", "播放/暂停")
    val previous get() = t("Previous", "上一个")
    val next get() = t("Next", "下一个")
    val addToPlaylist get() = t("Add to playlist", "添加到播放列表")
    val carLayout get() = t("Car layout", "车机布局")
    val fullScreen get() = t("Full screen", "全屏")
    val exitFullScreen get() = t("Exit full screen", "退出全屏")
    val allowLocalText get() = t("Allow access to videos stored on this device to play them offline.", "允许访问本机视频以便离线播放。")
    val allowVideoAccess get() = t("Allow video access", "允许访问视频")
    val allowVideoAccessHint get() = t("Allow video access in Pitstop Video (On this device tab)", "请在 Pitstop Video 的“本机视频”页允许访问视频")

    // Playlists
    fun playlistCount(n: Int) = t("$n playlist(s)", "$n 个播放列表")
    fun videoCount(n: Int) = t("$n video(s)", "$n 个视频")
    val newPlaylist get() = t("New playlist", "新建播放列表")
    val noPlaylists get() = t("No playlists yet. Tap + next to any video to add it to a playlist, or create one here.", "还没有播放列表。点击视频旁的 + 添加，或在此新建。")
    val playAll get() = t("Play all", "全部播放")
    val shuffle get() = t("Shuffle", "随机播放")
    val playerStyle get() = t("Player", "播放器")
    val playerStylePage get() = t("Separate page", "独立页面")
    val playerStyleFloating get() = t("Floating window", "悬浮窗")
    val playerStyleText get() = t("On phones in portrait: open the player as its own page, or keep it in a small window you can drag around.", "竖屏手机上：播放器作为独立页面打开，或显示为可拖动的小悬浮窗。")
    fun songsInFolder(n: Int) = t("$n song(s) in this folder", "此文件夹中有 $n 首歌曲")
    val repeatOff get() = t("Repeat off", "不循环")
    val repeatAll get() = t("Repeat all", "列表循环")
    val repeatOne get() = t("Repeat one", "单曲循环")
    val playlistEmpty get() = t("Empty. Tap + next to a video in any tab to add it here.", "列表为空。点击任意视频旁的 + 添加到这里。")
    val queueOptions get() = t("Queue options", "队列选项")
    val playNow get() = t("Play now", "立即播放")
    val playNext get() = t("Play next", "下一首播放")
    val addToQueue get() = t("Add to queue", "加入队列")
    val moveToTop get() = t("Move to top", "移到最前")
    val removeFromQueue get() = t("Remove from queue", "从队列移除")
    val clearUpcoming get() = t("Clear upcoming", "清空后续")
    val queueEditHint get() = t("Long-press an item to reorder or remove it.", "长按条目可调整顺序或移除。")
    fun addedToQueue(n: Int) = t(if (n == 1) "Added to queue" else "$n added to queue", "已加入队列（$n）")
    val moveUp get() = t("Move up", "上移")
    val moveDown get() = t("Move down", "下移")
    val removeFromPlaylist get() = t("Remove from playlist", "从列表移除")
    val renamePlaylist get() = t("Rename playlist", "重命名播放列表")
    val deletePlaylist get() = t("Delete playlist", "删除播放列表")
    fun deleteConfirm(name: String) = t("Delete “$name”?", "删除“$name”？")
    val deleteExplain get() = t("Only the playlist goes. Your videos stay.", "只删除列表，视频不受影响。")
    val nothingQueuedPhone get() = t("Nothing queued.", "队列为空。")
    val added get() = t("added", "已添加")
    val name get() = t("Name", "名称")
    val defaultPlaylistName get() = t("Playlist", "播放列表")

    // Account
    val youtubeSignedIn get() = t("Signed in. Subscriptions and Liked videos are on.", "已登录。可查看订阅和赞过的视频。")
    val youtubeSignedOut get() = t("Popular and search work without an account. Sign in to add Subscriptions and Liked videos.", "不登录也能看热门和搜索。登录后可加入订阅和赞过的视频。")
    val signOutYoutube get() = t("Sign out of YouTube", "退出 YouTube")
    val youtubeSignInAgain get() = t("YouTube access expired. Sign in to YouTube again in Settings.", "YouTube 授权已失效，请在设置中重新登录 YouTube。")
    val youtubeQuota get() = t("YouTube's daily limit for this app is used up. Try again tomorrow.", "本应用今日的 YouTube 接口额度已用完，请明天再试。")
    val youtubeApiDisabled get() = t("The YouTube Data API is turned off for this app's Google Cloud project.", "本应用的 Google Cloud 项目未启用 YouTube Data API。")
    val signInGoogle get() = t("Sign in with Google", "使用 Google 登录")
    fun googleFailed(msg: String?) = t("Google sign-in failed: $msg. See README → YouTube sign-in setup.", "Google 登录失败：$msg。请参阅 README 中的 YouTube 登录设置。")
    fun biliSignedIn(name: String?) = t("Signed in${name?.let { " as $it" } ?: ""}. Following feed and 1080p are on.", "已登录${name?.let { "（$it）" } ?: ""}。可看关注动态和 1080p。")
    val biliSignedOut get() = t("Plays up to 720p without an account. Sign in for 1080p and your Following feed.", "不登录最高 720p。登录后可看 1080p 和关注动态。")
    val signOutBili get() = t("Sign out of Bilibili", "退出哔哩哔哩")
    val signInWeb get() = t("Sign in (web)", "网页登录")
    val signInQr get() = t("Sign in (QR code)", "扫码登录")
    val qrTitle get() = t("Bilibili QR login", "哔哩哔哩扫码登录")
    val qrGenerating get() = t("Generating QR code…", "正在生成二维码…")
    fun failed(msg: String?) = t("Failed: $msg", "失败：$msg")
    val qrScanHint get() = t("Scan with the Bilibili app on another device, or tap “Open in Bilibili app”.", "用另一台设备上的哔哩哔哩 App 扫码，或点击“在哔哩哔哩 App 中打开”。")
    val qrScanned get() = t("Scanned. Confirm the login in the Bilibili app.", "已扫码。请在哔哩哔哩 App 中确认登录。")
    val qrExpired get() = t("QR code expired. Close and try again.", "二维码已过期，请关闭后重试。")
    val qrCode get() = t("QR code", "二维码")
    val openInBili get() = t("Open in Bilibili app", "在哔哩哔哩 App 中打开")
    val safetyTitle get() = t("Parked-only safety lock", "仅停车时播放")
    val safetyText get() = t("Video on the car screen pauses and hides whenever the car moves. Speed comes from the car when Android Auto provides it, otherwise from the phone's GPS.", "车辆行驶时，车机屏幕上的视频会暂停并隐藏。车速来自车辆（若 Android Auto 提供），否则来自手机 GPS。")
    fun speedSource(src: String, moving: Boolean) = t("Speed source: $src · ${if (moving) "moving" else "stopped"}", "车速来源：$src · ${if (moving) "行驶中" else "已停车"}")
    val speedCar get() = t("car", "车辆")
    val speedGps get() = t("phone GPS", "手机 GPS")
    val speedNone get() = t("none yet", "暂无")
    val allowLocation get() = t("Allow location (GPS speed fallback)", "允许定位（GPS 车速备用）")
    val inCarTitle get() = t("This device is installed in a car", "此设备安装在车内")
    val inCarAaos get() = t("Android Automotive OS detected. Always on.", "检测到 Android Automotive OS，始终开启。")
    val inCarText get() = t("For aftermarket Android head units and car-mounted tablets: video on this screen pauses while driving (needs location).", "适用于后装安卓车机和车载平板：行驶时本屏视频会暂停（需要定位权限）。")
    val usingInCar get() = t("Using it in the car", "在车上使用")
    val aaosHelp get() = t("This car runs Android Automotive OS: use Pitstop Video directly on this screen. The car blocks video while driving.", "此车运行 Android Automotive OS：可直接在本屏使用 Pitstop Video。行驶时车辆会阻止视频播放。")
    val aaHelp get() = t(
        "Android Auto (any car brand):\n1. Android Auto settings → tap Version 10× → ⋮ → Developer settings → enable “Unknown sources”.\n2. Connect to the car; Pitstop Video is in the app grid (A–Z, after Phone).\n3. ✕ on the car screen opens Browse; Exit is at the bottom of Browse.\n\nAftermarket Android head unit: install the APK on the unit and turn on “This device is installed in a car” above.\n\nVideo only plays while the car is parked.",
        "Android Auto（任意品牌车辆）：\n1. Android Auto 设置 → 连续点按“版本”10 次 → ⋮ → 开发者设置 → 开启“未知来源”。\n2. 连接车辆；Pitstop Video 位于应用列表中（按字母排序，在“电话”之后）。\n3. 车机屏幕上的 ✕ 打开“浏览”；“退出”在浏览列表底部。\n\n后装安卓车机：在车机上安装 APK，并开启上方的“此设备安装在车内”。\n\n视频仅在停车时播放。",
    )

    // Settings page
    val appLanguage get() = t("App language", "应用语言")
    val subtitlesTitle get() = t("Subtitles", "字幕")
    val subShowByDefault get() = t("Show subtitles by default", "默认显示字幕")
    val subLanguage get() = t("Preferred subtitle language", "首选字幕语言")
    val subLanguageAuto get() = t("Same as app language", "与应用语言相同")
    fun subSize(p: Int) = t("Text size: $p%", "文字大小：$p%")
    val subColor get() = t("Text colour", "文字颜色")
    fun subTextOpacity(p: Int) = t("Text opacity: $p%", "文字不透明度：$p%")
    fun subBgOpacity(p: Int) = t("Background opacity: $p%", "背景不透明度：$p%")
    val subEdge get() = t("Text edge", "文字边缘")
    val edgeNone get() = t("None", "无")
    val edgeOutline get() = t("Outline", "描边")
    val edgeShadow get() = t("Shadow", "阴影")
    val colorWhite get() = t("White", "白色")
    val colorYellow get() = t("Yellow", "黄色")
    val colorCyan get() = t("Cyan", "青色")
    val colorGreen get() = t("Green", "绿色")
    val subPreview get() = t("Sample subtitle text", "字幕示例")
    val drivingTitle get() = t("While driving", "行驶时")
    val audioOnly get() = t("Keep audio playing (audio-only mode)", "继续播放声音（纯音频模式）")
    val audioOnlyText get() = t("When the car moves, the picture hides and the sound keeps playing. Turn this off to pause instead.", "车辆行驶时隐藏画面、继续播放声音。关闭后改为暂停。")
    val appearance get() = t("Appearance", "外观")
    val themeLight get() = t("Light", "浅色")
    val themeDark get() = t("Night", "夜间")
    val themeSystem get() = t("System", "跟随系统")
    val backgroundImage get() = t("Background picture", "背景图片")
    val backgroundImageText get() = t("Show a picture from your phone behind the app. It stays on your phone.", "选一张手机里的图片作为背景，图片只存在你的手机上。")
    val choosePicture get() = t("Choose picture", "选择图片")
    val removePicture get() = t("Remove", "移除")
    fun versionLine(v: String) = t("Pitstop Video $v", "Pitstop 视频 $v")
    val phonePlayback get() = t("Phone playback", "手机播放")
    val backgroundPlay get() = t("Background playback", "后台播放")
    val backgroundPlayText get() = t("Keep the sound playing with the screen off or in another app. Control it from the notification or the lock screen.", "锁屏或切到其他应用时继续播放声音，可在通知栏或锁屏控制。")
    val audioOnlyDriving get() = t("Audio only while driving", "行驶中，仅播放声音")
    val audioOnlyPark get() = t("The picture returns when you park.", "停车后恢复画面。")
    val audioOnlyPhone get() = t("Audio only while the car moves", "车辆行驶中，仅播放声音")
    val platforms get() = t("Platforms", "平台")
    val platformsText get() = t("Choose which platforms appear in the Video and Music bars (and on the car screen). At least one stays in each bar.", "选择在“视频”和“音乐”栏（以及车机屏幕）中显示哪些平台。每栏至少保留一个。")
    val choosePlatforms get() = t("Choose platforms", "选择平台")
    val done get() = t("Done", "完成")
    val dataSaver get() = t("Video quality & data", "画质与流量")
    val dataSaverText get() = t("Lower quality uses less data. \"Audio only\" fetches just the sound of videos, great for music and podcasts. On mobile data the app also streams music at about 128 kbps, buffers less ahead, skips preloading the next track and loads smaller thumbnails.", "画质越低越省流量。“仅音频”只获取视频的声音，适合听歌和播客。使用移动数据时，还会以约 128 kbps 播放音乐、减少预缓冲、不预加载下一首，并加载较小的缩略图。")
    val onWifi get() = t("On Wi-Fi", "Wi-Fi 下")
    val onMobileData get() = t("On mobile data", "移动数据下")
    val qualityAuto get() = t("Auto", "自动")
    val qualityAudio get() = t("Audio only", "仅音频")
    val quality get() = t("Quality", "画质")
    fun qualityFor(metered: Boolean) = if (metered) t("Quality on mobile data", "移动数据画质") else t("Quality on Wi-Fi", "Wi-Fi 画质")

    // Errors shown as-is
    val needBiliLogin get() = t("Sign in to Bilibili on the phone first", "请先在手机上登录哔哩哔哩")
    val finishYoutubeSignIn get() = t("Open Pitstop Video on the phone to finish YouTube sign-in", "请在手机上打开 Pitstop Video 完成 YouTube 登录")
}

/** Current strings inside Compose; recomposes when the language changes. */
val S: Strings
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalStrings.current
