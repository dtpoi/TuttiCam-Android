# 更新日志 / Changelog

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
