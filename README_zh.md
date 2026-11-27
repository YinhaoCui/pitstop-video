# Pitstop Video

[English](README.md) | **简体中文**

一款用于车机屏幕的视频和音乐播放器。视频**只在停车时**播放；行驶中音乐和纯音频播放照常继续。
支持 YouTube、哔哩哔哩、PeerTube 和本机视频，以及 YouTube Music、网易云音乐、SoundCloud、
Bandcamp、播客、电台、Google Drive 和直播，登录与否都能用。目标是做成车机和手机上的一站式娱乐应用。

它有同一应用的两个版本（二选一安装，见[在车机屏幕上使用](#在车机屏幕上使用)）：

- **Pitstop Video（Pitstop 视频）**：主版本。**不使用 Android Auto 的话，请用这个**；不需要全屏的车上也用它。
- **Mirror Video（Mirror 视频）**：同一个应用，但在 Android Auto 上视频铺满整个车机屏幕。它用了单独的
  名字，因为 Android Auto 启动器会截断较长的名字，两个都叫 “Pitstop Video…” 的话在车上看起来一样，
  分不清该打开哪个。

可运行于：

| 平台 | 方式 |
|---|---|
| **Android Auto，任意品牌车辆** | 手机应用投射到车机屏幕。支持宽屏、竖屏和小屏；触屏、旋钮和触控板。 |
| **Android Automotive OS**（Google 内置：沃尔沃、极星、通用、本田……） | 在车上安装 APK。应用直接在车机屏幕上运行；行驶时车辆会阻止视频。 |
| **后装安卓车机** | 在车机上安装 APK，并打开 *账户 → 此设备安装在车内*（基于 GPS 的锁定）。 |
| **手机、平板、折叠屏** | Android 8.0 及以上。平板 / 横屏下左右分栏；画中画；分屏。 |

不支持：Apple CarPlay / iOS（CarPlay 不允许第三方视频应用）、华为 HiCar 和百度 CarLife（SDK 不开放）。

## 功能

应用有三个页面：**视频**、**音乐**和**直播**，每页有一排平台。这一排末尾的按钮（或 *设置 → 平台*）
可以隐藏不用的平台并调整顺序；车机上的浏览列表也遵循同样的选择。

### 平台

| 页面 | 平台 | 不登录 | 登录后 |
|---|---|---|---|
| 视频 | **YouTube** | 热门 / 趋势（无限滚动，涵盖所有趋势榜）、分类、搜索 | 在 Google 自己的页面登录（无需任何设置）：*订阅*、*赞过的视频*、*稍后观看*、*观看记录* |
| 视频 | **哔哩哔哩** | 推荐、热门、视频分区、搜索（≤ 480–720p）；多 P 视频（分P）每一 P 一个条目 | 网页或二维码登录：1080p 和*关注*动态 |
| 视频 | **PeerTube** | 实例动态和分类、搜索、频道 | – |
| 视频 | **本机视频** | 设备媒体库中的所有视频（相机、下载、影片……），可离线播放 | – |
| 音乐 | **B站音乐** | 音乐排行和子分区、搜索 | 更高音质 |
| 音乐 | **YouTube Music** | 排行榜、曲风和心情、搜索 | 同一个 Google 登录：*喜欢的音乐*和你的歌单 |
| 音乐 | **网易云音乐** | 官方榜单、歌单标签、歌手页、搜索；大歌单完整播放 | 网页或二维码登录：*每日推荐*和你的歌单 |
| 音乐 | **SoundCloud**、**Bandcamp** | 排行 / 发现标签、搜索，专辑和歌单以文件夹形式显示 | – |
| 音乐 | **播客** | Apple 热门榜和分类、搜索；节目以单集文件夹形式打开 | – |
| 音乐 | **电台** | radio-browser.info：热门、附近、流派、国家、语言 | – |
| 音乐 | **Google Drive** | *从 Google Drive 选择*（Google 自己的选择器）或*从文件添加*；见 [Google Drive](#google-drive) | 仅限自行构建：浏览整个 Drive |
| 音乐 | **本机音乐** | 存在设备上的歌曲 | – |
| 直播 | **B站直播** | 推荐、热门、直播分区、搜索 | *关注* |
| 直播 | **Twitch** | 热门直播、前 100 分类、搜索；纯音频清晰度 | – |
| 直播 | **斗鱼** | 热门、全部分类、搜索 | – |

付费内容一律不解锁：网易云 VIP 歌曲和哔哩哔哩付费试看片段会被拒绝，不会作为试听播放。
仅限中国大陆播放的 B站歌曲会改从 YouTube Music 或网易云播放。

### 浏览和搜索

- 动态、分类、搜索结果和作者页都可以无限滚动。从播放器（或全屏）返回时，列表会回到原来的位置。
- *分类*会打开一个窗口，列出该平台的所有分类，按组排列并可筛选。
- 搜索时会边输入边给出建议，并记住最近的搜索。*全部平台*标签会同时搜索本页所有平台，
  点*更多*可查看某个平台的完整结果。
- 每个列表都有*排序与筛选*：按标题、作者或时长排序；按时长、关键词或平台筛选。
  也支持平台自己的选项：YouTube 上传日期 / 时长、B站排序和时长、UP主排序、电台排序。
- 点作者名可查看其最新上传，并在那里*关注*；也可以用播放器里的人像按钮或条目菜单关注。每个有作者的视频和音乐平台在“分类”后面有两个标签：*关注的视频* / *关注的音乐*（你在该平台关注的所有作者的最新作品，混在一起）和*关注的作者*（作者列表）。车机上也有同样的两行。
- **关注**（直播页，三个平台通用，无需账号）：点主播名字，或在直播间菜单里点*关注主播*。每个直播平台的第一个标签*关注*列出你关注的所有主播：正在开播的在前，其余变灰并标*未开播*。只在打开或下拉刷新时查询，不在后台检查。播放直播时，下方列出正在开播的关注主播，点一下即可切换；车机的*关注*一行只列正在开播的。
- 星标已取消：0.8.2 第一次运行时，所有加过星标的内容的作者会自动变成关注；*星标*播放列表保留为普通播放列表（可以改名或删除）。

### 队列和播放列表

- 在任何列表（动态、搜索、文件夹、播放列表、队列）中长按条目即可多选：*全选*、*反选*，然后一起
  播放、加入队列、添加到播放列表或移除。每个条目的 + 按钮打开它自己的菜单：*接下来播放*、
  *加入队列*、*关注*（作者）、*下载*、添加到播放列表。
- 队列条目显示缩略图。拖动条目左侧的 ⋮⋮ 调整顺序；按住条目靠近边缘时列表会自动滚动。
  ⋮ 菜单可把条目移到最前或移除；*清空后续*会清空剩下的内容。读屏软件可使用*上移* / *下移*操作。
- 播放列表可以混合所有平台，视频和歌曲都可以（视频之后的歌曲会显示封面，全屏时也一样）。
  可拖动调整顺序（在列表自身的顺序下）、排序并保留排序后的顺序，在手机或车机上播放或随机播放。
- 播放列表中的**查找重复**会把可能是同一首歌或同一个视频的条目分组（同一条目、相同标题，或忽略
  “(Official Video)”等标签后标题相同），多余的副本可以一次移除。
- **导入**（播放列表页）：粘贴链接或歌曲列表，或打开文件。网易云、YouTube / YouTube Music 歌单
  和 B站收藏夹按原样导入。QQ音乐、酷狗、Spotify 和 Apple Music 歌单，CSV 文件（Exportify、
  TuneMyMusic……）以及“歌手 - 歌名”文本会在你选择的平台上逐首匹配。短分享链接会先被解析。
- **导出**：可重新导入的 Pitstop 文件（单个或全部播放列表）、CSV 或纯文本，通过系统分享面板发送。

### 播放

- 手机和车机共用一个播放器：在任意一边选择内容；连接 Android Auto 时视频会显示在车机屏幕上。
- 随机播放（可恢复原顺序）和循环：关闭 / 列表循环 / 单曲循环。
- 视频上的画质按钮（齿轮 + 当前画质），Wi-Fi 和移动数据分别设置默认值：自动 / 720p / 480p /
  360p / *仅音频*。仅音频只获取声音，并像歌曲一样显示封面。
- 只选择设备能解码的流；解码器失败时会以 480p AVC 重试一次。断线后按退避间隔在原位置重连，
  刷新过期的流链接，多次卡顿后自动降低画质。下一项会提前解析。
- 直播从最新位置开始播放。从直播页点开的直播间单独播放：没有队列，没有上一个 / 下一个，也不会自动跳到别的直播间。你自己放进播放列表或队列里的直播间则保留队列。
- **字幕**：YouTube 上传的字幕、自动生成的字幕以及自动翻译成你的语言；B站上传字幕和 AI 字幕
  （AI 字幕通常需要登录）。在*设置*中调整样式：语言、大小、颜色、文字和背景透明度、描边样式，
  并可实时预览。
- **后台播放**：通知栏、锁屏、耳机和蓝牙控制；画中画（Android 12 及以上自动进入）。
  视频播放时屏幕保持常亮。
- 使用移动数据和热点时的**省流量**模式：音频上限约 128 kbps，缓冲更短，不预加载下一首，
  缩略图按显示尺寸加载。

### 下载

- 播放器和每个条目的菜单中都有*下载*：视频可选 1080 / 720 / 480 / 360p 或仅音频；歌曲按原格式保存。
  分离的视频流和音频流会合并为一个 MP4，不重新编码。
- 保存到手机（*Download/Pitstop* 或你选择的文件夹）和 / 或 Google Drive（应用创建的 *Pitstop*
  文件夹，或在 Google 的 Drive 选择器中选的文件夹）。
- 在后台运行并显示进度通知；可在*设置*中取消、重试或打开已完成的文件。
- 公开版本不会保存 YouTube 或 YouTube Music 视频（见[构建选项](#构建选项)）。

### 车机屏幕

在真实车辆上，Pitstop 以停车应用的方式运行：只显示视频，或显示完整的手机应用，手机作为遥控器
（见[在车机屏幕上使用](#在车机屏幕上使用)）。下面描述的模板界面是开发者车机上显示的样子。

- **全屏**：视频填满可见区域，标题和作者显示在 Android Auto 按钮旁。**分屏**：视频在标题、进度
  和接下来播放的旁边（竖屏时在上方）。用*分屏 / 全屏*切换。
- *浏览*以侧边面板打开，视频缩小在旁边。*队列*以侧边面板盖在视频上；点任意条目（或 B站分P）
  即可跳转。*CC* 开关字幕。
- −10 秒 / +10 秒按钮；点进度条跳转，或拖动进度条带预览地快进快退。
- **仅停车时可看视频**：车辆行驶时，车机屏幕上的视频会被隐藏。默认声音继续播放（仅音频），
  也可以改为暂停。音乐不受影响。车速来自车辆，没有时用 GPS。此锁定无法关闭。

### 外观和语言

- 柔和的浅色主题或夜间主题（浅色 / 夜间 / 跟随系统），可选用自己相册中的图片作为背景；窄屏手机上
  可选择播放器布局：带迷你栏的独立页面，或可拖动的悬浮窗。
- 英文和简体中文，在手机和车机上即时切换。
- 顶部头像：默认是作者的头像；在自己的构建中放一张本地 `header_avatar` 图片即可替换。

## 工作原理

- Android Auto 通过地图模板（车机 API 7+）给应用一块绘图表面。*parked* 版本（Pitstop Video）以
  兴趣点（POI）车机应用注册（`MAP_TEMPLATES`、`androidx.car.app.category.POI`），使用
  `MapWithContentTemplate`；*nav* 版本（Mirror Video）以导航应用注册（`NAVIGATION_TEMPLATES`），
  使用 `NavigationTemplate`，视频占满整个屏幕。`CarVideoRenderer` 接收这块表面，在其上创建私有的 `VirtualDisplay`，并在其中显示 `CarPresentation`（一个普通的
  Android 视图树，包含 Media3 `PlayerView`）。
- 播放由 `PlaybackHub` 中唯一的 Media3 ExoPlayer 完成，手机和车机共用。
- YouTube 流通过 [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) 解析。
  个人动态来自 YouTube 网页 API，使用在 Google 自己页面上登录得到的会话（`youtube/YouTubeAccount.kt`）；
  不需要 API 密钥、Cloud 项目或配额。播放从不使用该会话。已订阅的频道会被记住；如果网页 API 变化
  或会话失效，*订阅*会改用这些频道的公开 RSS。
- 哔哩哔哩使用其公开网页 API（WBI 签名），DASH 视频和音频由 ExoPlayer 合并。
- SoundCloud、Bandcamp 和 PeerTube 也通过 NewPipeExtractor 获取。网易云使用其网页 API（weapi 加密），
  Twitch 使用其 HLS 播放列表，斗鱼使用其签名的网页 API。
- 每个平台都实现 `core/VideoSource`（动态、分类、搜索、作者页），因此手机界面、车机界面和搜索
  对它们一视同仁。

```
app/src/main/java/app/pitstop/video/
  core/       数据模型、VideoSource 接口、HTTP、设置、播放列表、I18n（英 / 中）
  youtube/    YouTube + YouTube Music 来源、YouTubeAccount（网页登录、动态、RSS 备用）、字幕
  bilibili/   BiliApi，视频 / 音乐 / 直播来源，Cookie 存储，WBI 签名，CDN 选择
  netease/    网易云音乐 API、加密、来源
  newpipe/    SoundCloud、Bandcamp、PeerTube
  podcast/    Apple 榜单 + iTunes 搜索、RSS 解析
  radio/      radio-browser.info
  live/       Twitch、斗鱼
  gdrive/     Drive 选择器（drive.file）和 Drive 来源
  local/      设备上的视频和歌曲（MediaStore）
  importer/   播放列表导入（网易云、YouTube、B站、QQ音乐、酷狗、Spotify、Apple Music、CSV、文本）和歌曲匹配
  download/   下载服务、流合并、Drive 上传
  player/     PlaybackHub、PlaybackService、StreamSelector、省流量、DrivingMonitor（停车锁定）
  car/        CarAppService、车机界面、VirtualDisplay 渲染器 + 演示界面
  ui/         Compose 手机界面、登录页面、拖动排序
```

## 构建

要求：JDK 17+（使用的是 21）、Android SDK platform 37。应用本身可运行于 Android 8.0（API 26）及以上。

```bash
./gradlew testParkedDebugUnitTest assembleDebug
```

这会构建应用的两个版本（同一个包名，二选一安装）：
`app/build/outputs/apk/parked/debug/app-parked-debug.apk`（Pitstop Video）和
`.../nav/debug/app-nav-debug.apk`（Mirror Video）。区别见[在车机屏幕上使用](#在车机屏幕上使用)。

> Android 构建工具在包含非 ASCII 字符的路径下会失败。如果代码在例如 `OneDrive\桌面` 下，
> 请先映射到一个盘符（`build.ps1` 会这样做）：`subst P: "<项目文件夹>"`，然后在 `P:\` 下构建。

安装到手机：`adb install -r app/build/outputs/apk/parked/debug/app-parked-debug.apk`
（Mirror Video：`tools/install-mirror-video.sh app/build/outputs/apk/nav/debug/app-nav-debug.apk`）。

### 发布

推送版本标签（`git tag v0.6.0 && git push origin v0.6.0`）会运行 `.github/workflows/release.yml`：
运行单元测试，用你自己的密钥构建签名的发布版 APK，并附加到 GitHub release。一次性设置：

1. 创建密钥（妥善保管；以后的每次更新都必须用它签名）：
   `keytool -genkeypair -v -keystore pitstop-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias pitstop`
2. 在仓库的 *Settings → Secrets and variables → Actions* 中添加 `KEYSTORE_BASE64`
   （`base64 -w0 pitstop-release.jks`）、`KEYSTORE_PASSWORD`、`KEY_ALIAS` 和 `KEY_PASSWORD`。

在调试版上安装发布版会失败（密钥不同）：请先卸载调试版。

**测试版。** 带预发布后缀的标签（`v0.7.0-beta.1`、`v0.7.0-rc.1`）会作为 GitHub 预发布版发布。只有打开了
*设置 → 更新与关于 → 测试版* 的应用才会收到；其他人只会收到正式版。把功能合并到 `main`，随时打测试版
标签，一批功能验证可靠后再打正式版标签（`v0.7.0`）。版本按 semver 排序
（0.7.0-beta.1 < 0.7.0-beta.2 < 0.7.0-rc.1 < 0.7.0），并且每个打标签的构建（包括测试版）的
`versionCode` 都必须比上一个高，因为安卓不会用较低的版本覆盖较高的版本。

### 构建选项

默认关闭，公开发布版中也是关闭的。在自己的构建中可在 `local.properties` 中打开
（或在 Gradle 命令行用 `-P`）：

| 属性 | 作用 |
|---|---|
| `pitstop.youtubeDownloads=true` | 允许把 YouTube / YouTube Music 视频保存到设备。 |
| `pitstop.driveFullAccess=true` | 登录后浏览整个 Google Drive（受限的 `drive.readonly` 权限，见 *Google Drive*）。 |
| `pitstop.idSuffix=.dev`、`pitstop.appLabel=名称` | 构建一个有独立包名和名称的测试版，可与发布版同时安装。 |

## 在车机屏幕上使用

Android Auto 只有在开发者模式允许时，才会显示不是来自 Play 商店的应用：

1. 在手机上：**设置 → 已连接的设备 → 连接偏好设置 → Android Auto**。
2. 向下滚动，点击**版本**约 10 次 → 同意开启开发者模式。
3. ⋮ 菜单 → **开发者设置** → 开启**未知来源**。
4. **自定义启动器** → 确认已勾选 *Pitstop Video*。
5. 连接车辆（有线或无线），从 Android Auto 启动器中打开 *Pitstop Video*。

每个发布版本都带同一应用的两个 APK。**二选一**安装：它们包名相同，装一个会替换另一个（数据和登录都会保留），
并且各自只更新到自己那一种。不用 Android Auto？选 *Pitstop Video*。

- `PitstopVideo-vX.Y.Z.apk` → **Pitstop Video**：**停车应用**加下面介绍的模板界面。怎么安装都可以；
  在*设置 → 更新与关于*里自行更新。
- `PitstopVideo-vX.Y.Z.mirror.apk` → **Mirror Video**：只作为**导航**模板应用，视频**全屏**（Android Auto 的
  按钮浮在视频上方并会自动淡出；点一下即可再出现）。车辆开动时视频照样停止。**它不能用普通方式安装：**
  真实车辆只在安装来源记录为 Play 商店时才列出它，而安卓只会为 *shell* 用户（adb）发起的安装记录这一点。
  打开 USB 调试、连接手机后运行

  ```bash
  tools/install-mirror-video.sh
  ```

  （Windows 上用 Git Bash，或 `powershell -File tools/install-mirror-video.ps1`；两者都会下载最新版本，
  也可以把 APK 文件作为参数传入），然后在手机上确认。直接在手机上安装 APK（即使是覆盖 Mirror Video）
  会让 Android Auto 再次隐藏它。

  **不用电脑更新：** 安装并启动 [Shizuku](https://shizuku.rikka.app/)（它与手机的无线调试配对一次，
  手机重启后需要重新启动）。Shizuku 运行时，Mirror Video 的*设置 → 更新与关于*里会出现*下载并安装*按钮，
  安装方式与脚本相同。没有 Shizuku 时只提供发布页链接。*设置 → 更新与关于 → Android Auto*
  会显示车上是否会列出已安装的副本；不会列出时，可以点*用 Shizuku 重新安装*。

如果装的是 Mirror Video，上面步骤中的 *Pitstop Video* 请换成 *Mirror Video*。

Pitstop 有两种方式进入车机屏幕，由 Android Auto 决定允许哪一种：

- **停车应用（真实车辆）。** 车辆停着时，Android Auto 会在车机屏幕上显示 Pitstop 自己的界面。
  默认**只显示视频**，尽可能大；点一下可暂停、切换、快进快退和调整画质。手机应用同时可用，作为遥控器：
  在手机上选视频，就在车机屏幕上播放，手机则显示*正在车机屏幕上播放*以及控制按钮和队列。
  *设置 → 车机屏幕：仅显示视频*（或车机屏幕右上角的按钮）可改为在车机屏幕上显示完整应用。
  车辆开动时，Android Auto 会关闭它（*行驶时不可用*）。无论应用是怎么安装的，这种方式都可用。
  停车应用需要 Android 15 或更高版本，以及 Android Auto 已开放的类别；视频类别尚未开放，
  所以应用声明自己是游戏（手机可能把它列在游戏中）。
- **Car App Library 界面（开发者车机）。** 即[车机屏幕](#车机屏幕)中介绍的、带*浏览*、*队列*和
  *分屏 / 全屏*的模板界面。在真实车辆上，除非安装来源记录为 Play 商店，否则 Android Auto 会拒绝它
  （日志：`CAR.VALIDATOR: Package DENIED; failed all other checks`），与*未知来源*设置或应用类别无关；
  `adb install -i com.android.vending` 不算数，应用自己发起的安装也不算（安卓会丢掉 Play 商店的名字）。
  `tools/install-mirror-video.sh` 从 adb 发送系统安装器的安装请求并指明 Play 商店，这样才算数。
  桌面车机模拟器（DHU）等开发者车机不做这项检查。

安装或更新后的第一次启动可能会显示*目前无法在 Android Auto 上访问此应用*；再打开一次即可。

Pitstop 在车机屏幕上打开时，会提醒你专心看路；如果模板界面连接时手机应用正开着，手机上也会显示。

Pitstop Video 位于 Android Auto 的应用列表中（按字母排序，在*电话*之后）。它把视频画在
Android Auto 的地图表面上；用*浏览 → 退出*暂停并关闭。切换到其他车机应用会暂停视频。

马自达 Connect 说明：触摸屏只在停车时可用（马自达的设计）；旋钮可在屏幕上的按钮间移动
（*浏览*、播放/暂停、*分屏/全屏*、下一个、±10 秒）。车机搜索用的屏幕键盘也只在停车时可用——
也可以在手机上搜索，结果会直接在车机屏幕上播放。

## YouTube 登录

*设置 → YouTube → 使用 Google 登录*会打开 Google 自己的登录页面。返回 YouTube 后，应用把 youtube.com
的会话 Cookie 保存在私有存储中，只用来列出你的动态。不需要在 Google 注册任何东西，所以任何构建都能用。

如果 Google 提示*“此浏览器或应用可能不安全”*，请稍后再试或换个网络；Google 有时会拦截它认为是
内嵌的登录页面。退出登录后，*订阅*仍会根据记住的频道继续更新。

## Google Drive

在 *音乐 → Google Drive* 中有三种添加歌曲的方式：

- **添加文件夹**（歌曲多时最好用）：在手机的文件夹选择器中选一次 Drive 文件夹，其中及子文件夹里的
  所有歌曲都会通过一个权限加入。需要手机上安装 Google Drive 应用。
- **从 Google Drive 选择**：Google 自己的 Drive 选择器。选择歌曲（可一次选多首）；应用只能看到这些歌曲
  （非敏感的 `drive.file` 权限），也不需要安装 Google Drive 应用。这里不能选文件夹：该权限只覆盖
  文件夹本身，不包括其中的歌曲。
- **从文件添加**：手机的文件选择器。安卓最多只为每个应用保留 512 个这样添加的文件，并会悄悄移除
  最早的；无法访问的歌曲会被标出，*用我的文件夹修复*会把播放列表改指向已添加文件夹中的同名文件。

下载也可以保存到 Drive。

要发布自己的构建？选择器需要为你的签名密钥做一次 Google Cloud 设置：
见 [docs/google-drive-setup.md](docs/google-drive-setup.md)。

## 哔哩哔哩登录

- **登录（网页）**会打开哔哩哔哩自己的移动端登录页面（密码 / 短信），并保存会话 Cookie。
- **登录（二维码）**会显示二维码，用另一台设备上的哔哩哔哩应用扫码，或在本机打开哔哩哔哩应用确认。

Cookie 只保存在应用的私有存储中。

在中国大陆以外，应用会优先使用哔哩哔哩的 Akamai 服务器，并避开其 P2P（PCDN）节点，
这些节点在海外卡顿严重（`bilibili/BiliCdn.kt`）。

## 注意事项

- YouTube 经常变化；如果 YouTube 播放失效，请把 `gradle/libs.versions.toml` 中的 `newpipe`
  更新到最新的 NewPipeExtractor 版本。
- Google Play 不会接受这个应用；它需要直接安装。
- 驾驶时观看视频既不安全也违法。仅停车时可看视频的锁定是有意设计的，无法关闭。

## 法律声明

- Pitstop Video 是一个独立项目，与 Google、YouTube、哔哩哔哩、网易、Twitch、斗鱼或它能打开的
  任何其他服务都没有关联，也未获得其认可或赞助。提到这些名称只是为了说明功能适用于哪个服务；
  应用不使用它们的标志或其他品牌素材。
- 应用不托管任何内容。它播放的是各服务提供给普通访客或你自己账户的内容，你有责任遵守各服务的条款。
- 付费内容一律不解锁：需要你没有的购买或会员资格的歌曲和视频会被拒绝（网易云 VIP 歌曲、B站付费
  试看片段），不会作为试听播放。
- 公开版本不会把 YouTube 或 YouTube Music 视频保存到设备。
- 权利人如有疑虑，可在本仓库提交 issue。
- 隐私：应用不为开发者收集任何信息；见 [PRIVACY.md](PRIVACY.md)。

## 许可证

Copyright (C) 2026 Pitstop Video contributors

Pitstop Video 是自由软件：你可以根据自由软件基金会发布的 GNU 通用公共许可证（GPL）第 3 版，
或（由你选择）任何更新的版本，重新分发和 / 或修改它。发布它是希望它有用，但不提供任何担保；
甚至不包括对适销性或特定用途适用性的默示担保。完整条款见 [LICENSE](LICENSE)。

简而言之：任何人都可以使用、研究、修改和分享这个应用。任何分发它的人，无论是否修改，
都必须使用相同的许可证，并提供完整的源代码。

它包含 [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor)（GPL-3.0）以及其他
以兼容许可证发布的开源库。
