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
    }

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
    val nowPlaying get() = t("Now playing", "正在播放")
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
    val nothingQueued get() = t("Nothing queued – pick videos with Browse", "队列为空 – 请通过“浏览”选择视频")
    val noPlaylistsCar get() = t("No playlists yet – create them in the phone app", "还没有播放列表 – 请在手机应用中创建")

    // Phone player
    val pickVideo get() = t("Pick a video", "选择一个视频")
    val pausedCarMoving get() = t("Paused – car is moving", "已暂停 – 车辆行驶中")
    val playingOnCar get() = t("Playing on the car display", "正在车机屏幕上播放")
    val playPause get() = t("Play/pause", "播放/暂停")
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
    val playlistEmpty get() = t("Empty. Tap + next to a video in any tab to add it here.", "列表为空。点击任意视频旁的 + 添加到这里。")
    val moveUp get() = t("Move up", "上移")
    val moveDown get() = t("Move down", "下移")
    val removeFromPlaylist get() = t("Remove from playlist", "从列表移除")
    val renamePlaylist get() = t("Rename playlist", "重命名播放列表")
    val deletePlaylist get() = t("Delete playlist", "删除播放列表")
    fun deleteConfirm(name: String) = t("Delete “$name”?", "删除“$name”？")
    val deleteExplain get() = t("The playlist is removed. The videos themselves are not affected.", "只删除列表，视频本身不受影响。")
    val nothingQueuedPhone get() = t("Nothing queued.", "队列为空。")
    val added get() = t("added", "已添加")
    val name get() = t("Name", "名称")
    val defaultPlaylistName get() = t("Playlist", "播放列表")

    // Account
    val youtubeSignedIn get() = t("Signed in – Subscriptions and Liked videos are available.", "已登录 – 可查看订阅和赞过的视频。")
    val youtubeSignedOut get() = t("Works without signing in (Popular + search). Sign in to add your Subscriptions and Liked videos.", "无需登录即可使用（热门 + 搜索）。登录后可查看订阅和赞过的视频。")
    val signOutYoutube get() = t("Sign out of YouTube", "退出 YouTube")
    val signInGoogle get() = t("Sign in with Google", "使用 Google 登录")
    fun googleFailed(msg: String?) = t("Google sign-in failed: $msg. See README → YouTube sign-in setup.", "Google 登录失败：$msg。请参阅 README 中的 YouTube 登录设置。")
    fun biliSignedIn(name: String?) = t("Signed in${name?.let { " as $it" } ?: ""} – Following feed and higher quality (1080p) are available.", "已登录${name?.let { "（$it）" } ?: ""} – 可查看关注动态和 1080p 画质。")
    val biliSignedOut get() = t("Works without signing in (up to 480p/720p). Sign in for 1080p and your Following feed.", "无需登录即可使用（最高 480p/720p）。登录后可看 1080p 和关注动态。")
    val signOutBili get() = t("Sign out of Bilibili", "退出哔哩哔哩")
    val signInWeb get() = t("Sign in (web)", "网页登录")
    val signInQr get() = t("Sign in (QR code)", "扫码登录")
    val qrTitle get() = t("Bilibili QR login", "哔哩哔哩扫码登录")
    val qrGenerating get() = t("Generating QR code…", "正在生成二维码…")
    fun failed(msg: String?) = t("Failed: $msg", "失败：$msg")
    val qrScanHint get() = t("Scan with the Bilibili app on another device, or tap “Open in Bilibili app”.", "用另一台设备上的哔哩哔哩 App 扫码，或点击“在哔哩哔哩 App 中打开”。")
    val qrScanned get() = t("Scanned – confirm the login in the Bilibili app.", "已扫码 – 请在哔哩哔哩 App 中确认登录。")
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
    val inCarAaos get() = t("Android Automotive OS detected – always on.", "检测到 Android Automotive OS – 始终开启。")
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
    val subPreview get() = t("This is how subtitles will look", "字幕将显示为这样")
    val drivingTitle get() = t("While driving", "行驶时")
    val audioOnly get() = t("Keep audio playing (audio-only mode)", "继续播放声音（纯音频模式）")
    val audioOnlyText get() = t("When the car moves, the picture is hidden and only the sound keeps playing. Turn off to pause completely.", "车辆行驶时隐藏画面、只播放声音。关闭则完全暂停。")
    val phonePlayback get() = t("Phone playback", "手机播放")
    val backgroundPlay get() = t("Background playback", "后台播放")
    val backgroundPlayText get() = t("Keep the sound playing when the screen is off or you switch apps. Controls appear in the notification and on the lock screen.", "锁屏或切换到其他应用时继续播放声音。可在通知栏和锁屏界面控制播放。")
    val audioOnlyDriving get() = t("Audio only while driving", "行驶中 · 纯音频播放")
    val audioOnlyPark get() = t("The picture returns when you park.", "停车后恢复画面。")
    val audioOnlyPhone get() = t("Audio only – car is moving", "纯音频 – 车辆行驶中")

    // Errors shown as-is
    val needBiliLogin get() = t("Sign in to Bilibili on the phone first", "请先在手机上登录哔哩哔哩")
    val finishYoutubeSignIn get() = t("Open Pitstop Video on the phone to finish YouTube sign-in", "请在手机上打开 Pitstop Video 完成 YouTube 登录")
}

/** Current strings inside Compose; recomposes when the language changes. */
val S: Strings
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalStrings.current
