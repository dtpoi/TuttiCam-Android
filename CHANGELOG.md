# 更新日志 / Changelog

## v1.3 · 2026-10-15

### 新增

- **DJI 录像模式**：DJI 相机在录像类模式（视频、全景视频、慢动作等）时，开始拍摄自动开始录像，停止拍摄时停止，期间不按单张快门；状态里显示还能录多久。
- **DJI 休眠**：外接相机页可以让 DJI 相机休眠省电；按相机上任意按键唤醒，醒来后自动重连。

### 改进

- 连上 DJI 相机后不再自动切到全景拍照，保留相机上选好的拍照或录像模式；延时、直播等其他模式仍会切到拍照。
- 改善连接多台外接相机时的稳定性：逐台连接，连接时暂停扫描，遇到蓝牙错误 133 自动重试；第三台起用均衡连接优先级。
- 开始页图标按安卓自适应图标规范放大。
- 已拍摄媒体的空状态在半屏时居中；重新安装 App 前拍的照片会提示到“文件”里查看。
- 设备页的保存位置链接直接打开系统“文件”App，不再先选应用。
- 外接相机统一用相机图标。

### 修复

- 开始会话时偶尔显示“相机出错（2）”，或预览正常却一直提示“没有就绪的设备”、无法拍摄。

### 安装

下载下方的 `TuttiCam-v1.3.apk` 覆盖安装，照片和设置都会保留。需要 Android 9 及以上。

---

#### New

- **DJI video mode**: when a DJI camera is in a video mode (video, panorama video, slow motion and more), recording starts when shooting starts and stops when shooting stops, with no single shots in between; the status shows how much recording time is left.
- **DJI sleep**: put a DJI camera to sleep from the External Cameras page to save battery; press any button on the camera to wake it, and it reconnects automatically.

#### Improvements

- Connecting to a DJI camera no longer switches it to panorama photo; the photo or video mode chosen on the camera is kept. Other modes such as timelapse or livestream are still switched to photo.
- Improved stability when connecting several external cameras: cameras connect one at a time, scanning pauses while connecting, and Bluetooth error 133 is retried automatically; from the third camera on, a balanced connection priority is used.
- The start screen icon is larger, following the Android adaptive icon guidelines.
- The Captured Media empty state is centered in the half-height sheet; photos taken before reinstalling the app point you to the Files app.
- The save folder link on the Devices page opens the system Files app directly, without asking which app to use.
- External cameras now all use a camera icon.

#### Fixes

- Starting a session sometimes showed “Camera error (2)”, or kept saying no device was ready even though the preview worked, so nothing was captured.

#### Install

Download `TuttiCam-v1.3.apk` below and install it over the old version; your photos and settings are kept. Requires Android 9 or later.

## v1.2 · 2026-10-15

### 新增

- **已拍摄媒体**：设备页新增“已拍摄媒体”，按场景浏览本机照片，显示封面、张数、大小和时间。点进去看缩略图，再点全屏翻看，显示偏差、模糊和是否在动；底部进度条从这个场景的第一张拖到最后一张，快速翻看。可以分享、删除单张照片或整个场景。
- **打开保存文件夹**：设备页最底下的 Documents/TuttiCam 可以点，直接在系统“文件”App 里打开。
- **从机问题提示**：主机取景器上的提示会列出各从机的问题，如“A02：保存失败：…”，包括相机出错、保存失败、错过某组和没有回报。保存失败要从机也更新到 v1.2 才会报告。

### 改进

- 检查更新失败时显示 HTTP 状态码和原因，如“HTTP 403 · API rate limit exceeded”。
- 设备、外接相机、已拍摄媒体三页的左右边距和设置页对齐；卡片换成看得出边缘的底色，外接相机页的空状态和说明也放进卡片。

### 安装

下载下方的 `TuttiCam-v1.2.apk` 覆盖安装，照片和设置都会保留。需要 Android 9 及以上。

---

#### New

- **Captured Media**: the Devices page now has Captured Media, which lists this phone's photos by scene with a cover, photo count, size and time. Open a scene for thumbnails, then a photo to view it full screen with offset, blur and moving status; drag the bar at the bottom to scrub from the scene's first photo to its last. Share or delete single photos or whole scenes.
- **Open the save folder**: tap Documents/TuttiCam at the bottom of the Devices page to open it in the system Files app.
- **Client problems on the host**: the hints on the host's viewfinder now list each client's problems, such as “A02: Save failed: …”, including camera errors, save failures, missed rounds and missing reports. Save failures are reported only when the client is also on v1.2.

#### Improvements

- When the update check fails, the HTTP status code and reason are shown, such as “HTTP 403 · API rate limit exceeded”.
- The Devices, External Cameras and Captured Media pages now use the same side margins as Settings; cards have a background that shows their edges, and the empty states on External Cameras sit in cards too.

#### Install

Download `TuttiCam-v1.2.apk` below and install it over the old version; your photos and settings are kept. Requires Android 9 or later.

## v1.1 · 2026-10-15

### 新增

- **检查更新**：关于页新增“检查更新”，查询 GitHub 上的最新正式版，有新版本时可直接打开下载页。只在点按时联网，不附带设备或照片信息。
- **源代码和官网**：关于页新增 GitHub 源代码（GPL-3.0）和官网 tutticam.com 的链接。

### 改进

- 遥控模式中央的“最小间隔”菜单改为从右侧数值处展开，并标出当前选中的间隔。
- 精简关于页里外接相机协议来源的说明。

### 修复

- 关于页的商标声明里，App 名称显示成了“%1$s”。

### 安装

下载下方的 `TuttiCam-v1.1.apk` 覆盖安装，照片和设置都会保留。需要 Android 9 及以上。

---

#### New

- **Check for updates**: the About page can now check GitHub for the latest release and open the download page when a new version is out. It only goes online when you tap, and sends no device or photo information.
- **Source code and website**: the About page now links to the source code on GitHub (GPL-3.0) and to tutticam.com.

#### Improvements

- In remote mode, the Minimum Interval menu now opens from the value on the right and marks the current choice.
- Shorter notes on the sources of external camera protocols in About.

#### Fixes

- The trademark notice in About showed “%1$s” instead of the app name.

#### Install

Download `TuttiCam-v1.1.apk` below and install it over the old version; your photos and settings are kept. Requires Android 9 or later.

## v1.0 · 2026-10-15

齐拍即合 TuttiCam 安卓版的第一个正式版。它是 iOS 版的原生移植，可以和 iPhone 在同一个会话里同步拍摄。

### 功能

- **多机同步拍摄**：一台当主机，其余当从机；同一 Wi‑Fi 或热点下自动发现，一个会话最多 8 台。安卓和 iPhone 可以混拍，两边都能当主机。
- **遥控模式**：本机不开相机，只给从机和外接相机按快门；画面中央可直接调静止判定和最小间隔。
- **精确对时、按时刻取帧**：NTP 式对时；从连续画面里取曝光时刻离约定时刻最近的一帧，并把偏差回报给主机。
- **静止判定**：三脚架、手持、不判定三种模式，可只看主机；可选强制照片同步。
- **手动参数**：快门、ISO、白平衡、色调、曝光补偿、对焦，可锁定全部参数。
- **多镜头**：单镜头按实际倍率切换（如 0.6x、1x、3x、前置）；支持的机型可以两颗镜头同时拍。
- **照片格式**：JPEG、HEIC、RAW（DNG）、RAW + JPEG。
- **照片信息**：EXIF 和 GPS，可选的拍摄姿态（XMP）和同名 JSON；文件命名与 iOS 版相同，方便混合重建。
- **场景文件夹**：新建、重命名、切换场景，各设备同步。
- **外接相机**：通过蓝牙给 DJI、索尼、佳能、富士、尼康、理光、GoPro 相机按快门。
- **语言**：简体中文、English、日本語，可在设置里单独切换。

### 已知限制

- 和 iPhone 混拍需要连同一个 Wi‑Fi 或热点；不连网络的点对点连接还没有做。
- 已实测机型：三星 Galaxy S24（Android 16）。

### 安装

下载下方的 `TuttiCam-v1.0.apk` 安装，需要 Android 9 及以上，并允许“安装未知应用”。

---

The first release of TuttiCam for Android, a native port of the iOS app. It can shoot in sync with iPhones in the same session.

#### Features

- **Synchronized multi-phone capture**: one phone hosts, the others join; devices on the same Wi‑Fi or hotspot find each other automatically, up to 8 per session. Android phones and iPhones can shoot together, and either can host.
- **Remote mode**: the phone keeps its camera off and only fires clients and external cameras; stillness detection and minimum interval are adjustable right on screen.
- **Precise clock sync, frames picked by time**: NTP-style clock sync; the frame whose exposure is closest to the agreed moment is kept, and the offset is reported to the host.
- **Stillness detection**: tripod, handheld and off modes, optionally checking the host only; optional forced photo sync.
- **Manual controls**: shutter, ISO, white balance, tint, exposure compensation and focus, with a lock for all parameters.
- **Multi-lens**: switch lenses by their real zoom (such as 0.6x, 1x, 3x, Front); supported phones can shoot with two lenses at once.
- **Photo formats**: JPEG, HEIC, RAW (DNG), RAW + JPEG.
- **Photo metadata**: EXIF and GPS, optional camera attitude (XMP) and per-photo JSON; file names match the iOS app for mixed reconstruction.
- **Scene folders**: create, rename and switch scenes, synced across devices.
- **External cameras**: fire DJI, Sony, Canon, Fujifilm, Nikon, Ricoh and GoPro cameras over Bluetooth.
- **Languages**: Simplified Chinese, English and Japanese, switchable in Settings.

#### Known Limitations

- Shooting with iPhones needs the same Wi‑Fi or hotspot; peer-to-peer connection without a network is not available yet.
- Tested on: Samsung Galaxy S24 (Android 16).

#### Install

Download `TuttiCam-v1.0.apk` below. Requires Android 9 or later and permission to install unknown apps.
