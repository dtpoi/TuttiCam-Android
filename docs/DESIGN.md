# 齐拍即合 TuttiCam · Android 设计文档

- 状态：随代码更新，最近一次 2026-10-15（进度见 §17）
- 对应 iOS 版：`github.com/dtpoi/TuttiCam`（分支 `tutticam-duo`，功能明细见其中的 FEATURES.md）
- 网络协议：见 [PROTOCOL.md](PROTOCOL.md)，与 iOS 逐字节兼容
- 首台实测设备：三星 Galaxy S24

---

## 1. 目标与范围

把 iOS 版的“齐拍即合”移植为 Android 原生 App：多台手机组队，在所有设备都静止的同一时刻同步拍照，为 3D 高斯泼溅等重建准备照片和拍摄数据。

**目标**

1. 功能与 iOS 版对齐，交互和信息层级一致，视觉语言是 Material 3，不是“假 iOS”。
2. **能和 iPhone 混合组队**：Android 可以加入 iPhone 主机的会话，也可以当主机让 iPhone 加入。
3. 不依赖 Google Play 服务也能完成核心功能（国行安卓大多没有 GMS）。
4. 同步精度不低于 iOS 版；Camera2 能拿到每帧的真实曝光时刻，目标是更好。

**范围**

| 已实现 | 未实现 |
|---|---|
| 主机 / 从机 / 遥控模式 | Wi‑Fi Aware（Android 与 iOS 两端同时接入，可点对点混拍） |
| 局域网连接（与 iOS 互通） | Wi‑Fi Direct、BLE 兜底连接 |
| Camera2 按时刻取帧、手动参数、对焦、锁定 | 帧相位对齐 |
| 多镜头（双后置、前后同时） | 4800 万像素 |
| JPEG、HEIC、RAW（DNG）、RAW + JPEG | 路线小地图 |
| 陀螺仪静止判定、强制照片同步 | |
| 场景文件夹、照片与元数据输出 | 内购（Play 结算 / 国内渠道） |
| 外接相机（蓝牙） | 平板布局细调 |
| 拍摄页、设置页、设备页、已拍摄媒体页、关于页、引导 | |
| 简体中文、英语、日语，App 内切换语言 | |

**非目标**：不做云端、不做账号、不上传任何数据（与 iOS 隐私承诺一致）。

---

## 2. 关键决策

| 方面 | 决定 | 理由 |
|---|---|---|
| 开发方式 | 原生 Kotlin | 相机时序、传感器时间戳、网络都需要贴近系统 |
| 界面 | Jetpack Compose + Material 3（含 Expressive） | 官方方向；动态颜色；悬浮工具栏是 M3 原生组件 |
| 导航风格 | **悬浮工具栏，不用贴底 / 贴顶的栏** | 与 iOS 版观感一致，且是 M3 Expressive 的正式样式 |
| 玻璃效果 | 不做背景模糊：相机页的悬浮层用半透明深色，其他页用 M3 实体色 | 预览是 SurfaceView，Compose 的模糊取不到它的画面 |
| 相机 | **Camera2**（不用 CameraX ConcurrentCamera 作为主路径） | 需要按时刻取帧、`SENSOR_TIMESTAMP`、全手动参数、RAW |
| 连接 | 局域网（NSD）已实现；计划加 **Wi‑Fi Aware（iOS 也同步接入）** → Wi‑Fi Direct → BLE 兜底；Nearby Connections 不作为主干 | 与 iOS 互通（同一 Wi‑Fi 或点对点）、不依赖 GMS、时延可控 |
| 协议 | 沿用 iOS 线上协议 v1（TCP 长度前缀 JSON + UDP JSON） | 混合组队；Android 一侧适配 Swift 的 JSON 形态 |
| 最低系统 | **minSdk 28（Android 9）** | 覆盖面；新能力按版本判断后启用 |
| 外接相机 | 协议逻辑照搬 iOS，传输层用 Android BLE 重写 | iOS 端全部是 BLE 中心角色，可直接对应 |

---

## 3. 平台与工具链

| 项 | 版本 |
|---|---|
| minSdk / targetSdk / compileSdk | 28 / 36 / 37.2 |
| Kotlin | 2.4.21 |
| Android Gradle Plugin | 9.4.1 |
| Gradle | 9.8.1 |
| JDK | 21 |
| Compose BOM | 2026.09.00（Material 3 1.4.0） |

**minSdk 28 带来的分支**（低于所需版本时的退路）：

| 能力 | 所需版本 | 低版本做法 |
|---|---|---|
| 动态颜色 | 31 | 品牌色方案 |
| 新蓝牙权限 BLUETOOTH_SCAN / CONNECT | 31 | 旧权限 + 定位权限 |
| `NEARBY_WIFI_DEVICES` | 33 | 定位权限 |
| `WifiAwareNetworkSpecifier.Builder` | 29 | 28 上不启用 Wi‑Fi Aware |
| 并发相机 `getConcurrentCameraIds` | 30 | 不提供“前置 + 后置”同时拍 |
| 超高分辨率传感器（4800 万像素） | 31 | 只用默认分辨率 |
| 预测性返回 | 33（34 起有动画） | 普通返回 |
| 系统“应用语言”`LocaleManager` | 33 | App 自己保存语言并替换资源配置 |
| 询问会话配置是否支持 `isSessionConfigurationSupported` | 29 | 直接尝试，失败再退回 |
| 通知权限 | 33 | 不需要 |

---

## 4. 工程结构

独立仓库 `TuttiCam-Android`（不与 iOS 仓库合并）：

```
TuttiCam-Android/
├── docs/                 设计文档、协议规范
├── protocol/             纯 Kotlin（JVM）模块：消息、编解码、对时、设置模型、外接相机协议。无 Android 依赖，可在电脑上直接跑单元测试
├── tools/                图标生成、iOS golden 报文工具
└── app/                  Android 应用
    └── src/main/java/com/thinktough/tutticam/
        ├── app/          全局状态（AppModel）、App 语言
        ├── net/          传输层：局域网（NSD + TCP/UDP），统一成 Link 接口
        ├── engine/       主机调度（HostCoordinator）、拍摄执行（CaptureAgent）、静止判定、定位
        ├── camera/       Camera2 封装：能力探测、预览、按时刻取帧、手动参数、白平衡换算、编码
        ├── storage/      场景文件夹、照片与元数据写入、session.json
        ├── accessory/    外接相机：BLE 连接、各品牌驱动、AccessoryHub
        └── ui/           Compose 界面：主题、组件（悬浮按钮、参数条、刻度尺、底部弹层）、各页面
```

- 并发：相机、取帧、编码、网络、蓝牙、存储各自用独立线程，不在主线程做时间敏感的事。
- 状态：`AppModel`、`HostCoordinator`、`CaptureAgent` 用 Compose 状态（`mutableStateOf`），只在主线程修改；界面只读状态、调方法。
- 依赖注入：手动装配（规模不大，不引入 Hilt）。
- 单 Activity，Compose 内导航；相机页常亮（`FLAG_KEEP_SCREEN_ON`）。
- 包名 / applicationId：`com.thinktough.tutticam`（与 iOS 的 `com.ThinkTough.TuttiCam` 对应，Android 包名用小写）。

---

## 5. 界面设计

### 5.1 原则

1. **信息层级与 iOS 一致**：同样的控件、同样的位置关系，用户在两个平台上不用重新学。
2. **视觉语言是 Material 3**：Material Symbols 图标、系统字体（Roboto Flex）、M3 形状与动效、水波纹与状态层、48dp 触控区。
3. **悬浮而非贴边**：顶部、底部、侧边的控件都是悬浮的胶囊 / 卡片，内容铺满全屏（edge‑to‑edge），从控件下面穿过。
4. **不做模糊**：相机页的悬浮层用半透明深色，弹层和其他页用 M3 实体色。

### 5.2 悬浮工具栏

按 Material 3 Expressive 悬浮工具栏的样子自绘组件（`TcFloatingSurface`、`TcFloatingToolbar`、`TcFloatingCard`：全圆角容器、半透明深色、低层级投影、细描边、48dp 触控区）。

| iOS 现有界面 | Android 做法 |
|---|---|
| 顶部胶囊（离开、编号、场景、主机） | 悬浮胶囊 Surface（不用 TopAppBar） |
| 参数条（快门 / 光圈 / ISO / 白平衡 / 色调 / 曝光补偿 / 锁） | 悬浮卡片 |
| 底部控制栏（设置、快门、设备） | 三个独立的悬浮按钮，快门居中；从机为设置、拍摄状态、外接相机 |
| 单 / 多镜头、镜头倍率 | M3 Expressive 连体按钮组，悬浮 |
| 竖栏布局（折叠屏、平板） | 悬浮按钮竖排在侧边 |
| 设置、设备、外接相机、关于 | 自绘底部弹层 `TcSheet`：先占 2/3 屏，上拉全屏，下拉收回或关闭；弹层可以叠加 |

**系统集成**

- 返回以系统手势为主：返回键关闭最上层的弹层，拍摄页上返回先收起调节条，再询问是否离开会话。
- 悬浮栏放在 `WindowInsets.navigationBars` 之上，离底边留足距离，不压在手势条上；三键导航时停在实心导航栏之上。
- `enableEdgeToEdge()`，状态栏、导航栏透明。

### 5.3 悬浮层的颜色

- 压在**相机预览**上的悬浮按钮、参数条、调节条：半透明深色加细描边，**不模糊**，因为预览是 SurfaceView，Compose 的背景模糊取不到它的画面。
- 弹层、列表、卡片：M3 实体色 + 动态颜色；弹层里的分组标题用主题色小字。
- 阴影用 M3 低层级（Level 1–2），不加重投影。

### 5.4 颜色与字体

- 相机页**固定深色**；其他页跟随系统，Android 12+ 用动态颜色（`dynamicDarkColorScheme` / `dynamicLightColorScheme`），更低版本用品牌色方案。
- 品牌色沿用 iOS：手动参数黄（#FFD60A）、静止绿、在动橙、选中蓝（白字对比度 ≥ 4.5:1 的深一档蓝）。
- 数值用等宽数字（`fontFeatureSettings = "tnum"`）。
- 取景画面上的状态文字：按文字背后画面上沿的亮度自动切换白字 / 黑字（带回差，见 6.8），实色、不用细体和半透明灰字。

### 5.5 布局：按可用空间，不按机型

与 iOS（含 iPhone Duo 适配）同一套规则：

| 布局 | 条件 | 结构 |
|---|---|---|
| phone | 普通手机竖屏 | 顶部悬浮胶囊 + 参数条；取景铺满；底部悬浮工具栏 |
| rail | 较宽较矮的竖屏（折叠屏外屏、分屏、平板竖放） | 左侧身份 / 参数条 / 取景框；侧边 `VerticalFloatingToolbar` |
| wide | 横向宽屏（折叠屏展开、平板横放） | 左侧信息面板（状态、设备卡片、路线）+ 取景框 + 侧边竖栏 |

- 用 Material 3 adaptive（`currentWindowAdaptiveInfo()`）取窗口尺寸类别，用 Jetpack WindowManager 的 `FoldingFeature` 取折痕位置（对应 iOS 的 reservedRegions）：书本式对折时信息面板停在折痕左侧，取景框移到右半边；任何按钮都不压在折痕上。
- 折叠 / 展开时界面状态保留（状态放在 ViewModel / AppModel，不放在可组合函数的 `remember` 里），预览不重建。

### 5.6 页面清单

| 页面 | 说明 |
|---|---|
| 引导（3 页） | 功能介绍、组队说明、权限（相机必需，位置可选） |
| 开始页 | 设备名称；创建会话（主机）、遥控模式、加入会话（从机） |
| 拍摄页 | 见 5.7 |
| 设置（底部弹层） | 通用、画质与格式、照片信息、锁定参数、静止判定、拍摄节奏、外接相机提前量、强制照片同步、语言、关于 |
| 设备页（底部弹层） | 设备卡片（编号、名称、状态、对时误差、电量、空间、镜头、外接相机）、外接相机入口、最近几组、已拍摄媒体入口；最底下的保存位置 Documents/TuttiCam 可点，在系统“文件”App 里打开 |
| 已拍摄媒体（底部弹层） | 按场景列出本机照片（封面、张数、大小、时间），点进去是缩略图网格，再点全屏翻看，显示偏差、模糊、是否在动，底部进度条从这个场景的第一张拖到最后一张，可分享、删除；正在拍的场景不能删。只读本 App 通过 MediaStore 写入的文件，重装后旧照片要到“文件”App 里看 |
| 外接相机（底部弹层） | 本机的相机、附近的相机、各品牌配对方法；主机从设备页进，从机从底栏进 |
| 关于（底部弹层） | 版本、官网、检查更新（点按时查 GitHub 最新正式版，失败时显示 HTTP 状态码和原因）、源代码、协议与隐私、开源项目、外接相机协议来源与商标 |

未实现：专业版页（安卓版目前没有内购，也没有免费版设备上限）。

### 5.7 拍摄页（phone 布局）

自上而下：

1. 悬浮胶囊：离开（×）、编号 + 设备名、场景、主机 / 从机。
2. 参数条：快门、光圈、ISO、白平衡、色调、曝光补偿、锁。点按展开刻度尺（自绘 `TickRuler`，横向拖动，惯性 + 吸附 + 触感反馈）。
3. 状态文字：静止 / 在动、模糊量、模式、镜头；组数、偏差；提示。提示里有本机的问题（保存失败、错过、格式退回）；主机还列出各从机的问题，写成“A02：…”，来源是从机状态里的相机错误和 `problem`，以及每组回报里的错过、缺照片、没回报。
4. 取景画面（按比例完整显示，不裁切）。
5. 镜头按钮组 + AF / MF；对焦刻度尺竖向展开。
6. 底部悬浮工具栏：设置、快门（开始 / 停止）、设备（数量角标）。

手势与 iOS 一致：单击收起刻度尺 / 选中多镜头画面；双击或双指轻点恢复自动对焦；长按锁定 / 解锁全部参数。

### 5.8 无障碍与本地化

- TalkBack：每个控件有内容描述；参数单元格读出“快门 自动 1/125”这类完整语义。
- 字号跟随系统；触控区 ≥ 48dp。
- 字符串资源 `values/`（英语）、`values-zh-rCN/`、`values-ja/`；文案从 iOS 的 `Localizable.xcstrings` 迁移，术语保持一致（主机 Host / 親機，从机 Client / 子機，组 Round / 組）。
- 设置里可单独选 App 语言（跟随系统、简体中文、English、日本語）：Android 13 起交给系统的应用语言（`localeConfig` 已声明），更早的系统由 App 保存并替换资源配置；切换时页面就地刷新，相机不中断。

---

### 5.9 应用图标

- 原稿 `TuttiCamIcon.svg` 不放进仓库，由维护者单独保管；用 `tools/icon/svg2adaptive.py` 生成自适应图标的前景与单色图层（矢量），背景白色。
- **按视觉重量居中**（“模糊后看重心”）：`tools/icon/fit_icon.py` 把前景高斯模糊后求重心，迭代调整位置与缩放，使模糊后的重心落在画布中心（误差 < 0.1dp），且最远点不超出 66dp 安全区（离中心 32.5dp）。微调量体现为前景图层里的平移，整张 108dp 画布本身仍按几何居中。
- 单色图层（Android 13 主题图标）把深浅灰换成不同的不透明度。

## 6. 相机

### 6.1 为什么用 Camera2

- CameraX 的 ConcurrentCamera 只解决“前置 + 后置同时开”，并发时只保证较低分辨率的流，也不能同时开两颗后置。
- CameraX 的 ImageCapture 拍照延迟大且不可控，手动参数只能经 Camera2Interop 间接设置。
- Camera2 提供每帧的 `SENSOR_TIMESTAMP`（`SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME` 时与 `SystemClock.elapsedRealtimeNanos()` 同一时基），能精确知道照片在什么时刻曝光；可全手动控制曝光、ISO、白平衡、对焦；支持 RAW。

### 6.2 能力探测

启动时遍历 `CameraManager.cameraIdList` 和逻辑多摄的物理镜头，生成本机的 `lenses` 与可同时使用的组合（对应 iOS 的 `DeviceInfo.lenses / dualCombos`）：

- 镜头种类：前置归为 `f`；后置按与第一颗后置（多为逻辑主摄）的等效焦距之比归为 `uw`（< 0.75 倍）、`w`、`t`（> 1.6 倍）。不按绝对焦距，因为部分机型报的传感器尺寸不准。没有独立编号的长焦用主摄的变焦比例补出来。
- 记录：硬件级别、`TIMESTAMP_SOURCE`、手动传感器能力（`MANUAL_SENSOR`）、RAW、超高分辨率、对焦最近距离、曝光时间 / ISO 范围、`SYNC_MAX_LATENCY`。
- `TIMESTAMP_SOURCE` 不是 REALTIME 的设备：退回“按触发时刻估计”，状态里标出精度受限。

### 6.3 预览

- `SurfaceView`（性能最好）放在 `AndroidView` 里；画面按比例完整显示，不裁切。
- 预览旋转按 `SENSOR_ORIENTATION` 与显示方向计算；倒拿手机时与 iOS 一样，界面翻转、画面保持正向。

### 6.4 同步拍摄：按时刻取帧

参考 Google Research 的 *Wireless Software Synchronization of Multiple Distributed Cameras*（libsoftwaresync）：

1. 相机连续输出预览和全尺寸 YUV（约 30 fps，长曝光时帧间隔等于曝光时长）。
2. 主机安排的拍摄时刻（主机时钟）经对时换算成本机 `elapsedRealtimeNanos`。
3. 取**第一帧曝光开始时刻晚于“目标 − 半个帧间隔”的那一帧**，它就是离目标最近的帧；偏差 = 帧时间戳 − 目标时刻，如实回报给主机（`PhotoReport.offsetMs`）。目标过去 1 秒还没有帧就判这一路没拍到。
4. 选中的帧转成 NV21 交给编码线程，按格式编成 JPEG / HEIC，并写 EXIF、XMP 和同名 JSON。
5. 未实现：**帧相位对齐**（各设备插入一帧加长的帧间隔，把帧起点对齐到同一相位），可把偏差从“半个帧间隔以内”降到亚毫秒。

偏差计算统一用帧的曝光开始时刻（`SENSOR_TIMESTAMP`，即首行开始曝光的时间）；回报字段与 iOS 一致。

### 6.5 手动参数

| 参数 | Camera2 |
|---|---|
| 快门、ISO | `CONTROL_AE_MODE_OFF` + `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY`；只手动一项时，另一项按目标亮度自动调（与 iOS 的“半自动”一致） |
| 曝光补偿 | `CONTROL_AE_EXPOSURE_COMPENSATION`（按 `CONTROL_AE_COMPENSATION_STEP` 换算） |
| 白平衡（色温 / 色调） | `CONTROL_AWB_MODE_OFF` + `COLOR_CORRECTION_GAINS`（色温、色调 → RGGB 增益的换算，按设备的色彩标定插值） |
| 锁定 | `CONTROL_AE_LOCK` / `CONTROL_AWB_LOCK` / 对焦锁 |
| 对焦 | `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE`（屈光度；界面 0–1 映射到最近对焦距离 ↔ 无穷远） |
| 光圈 | 只读（手机固定光圈，可变光圈机型读 `LENS_APERTURE`） |

### 6.6 输出

- **JPEG**（默认）：YUV → `YuvImage.compressToJpeg`（质量 95），方向写 EXIF。
- **HEIC**：`androidx.heifwriter` 编码，先把画面按方向转正再编码（不靠 irot 或 EXIF 方向，各家看图软件认法不一），附 EXIF；编码失败时改存 JPEG。
- **RAW**（只存 DNG，安卓专有）和 **RAW + JPEG**：单镜头且镜头支持 RAW 时，会话里多开一路 `RAW_SENSOR`；拍摄期间才让连续出帧带上它（省电），按传感器时间戳配上与选中 YUV 帧同一次曝光的那张，用 `DngCreator` 写 DNG。多镜头、变焦切出的长焦、系统不接受这样的会话配置时不出 RAW，取景器上提示；某一组没取到 RAW 时只存处理过的照片并记下原因。
- 收到 iPhone 主机的 RAW + HEIC 时照样拍；安卓主机设成 RAW 时给 iPhone 下发 RAW + JPEG。
- **多镜头**：双后置用逻辑多摄的物理子镜头输出（`OutputConfiguration.setPhysicalCameraId`）；前置 + 后置用并发相机（API 30+）。都按能力探测列出，打开失败时退回主镜头单拍并提示。
- 未实现：4800 万像素（`SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION`）。

### 6.7 元数据

- EXIF：拍摄时间、曝光、ISO、焦距、35mm 等效焦距、GPS（开关打开且有权限时），用 `androidx.exifinterface`。所有有理数（GPS、快门、光圈）都由 App 写成“分子/分母”，因为 ExifInterface 1.4.2 把某些小数换成分数时会死循环。
- XMP：陀螺仪姿态（开关打开时），字段与 iOS 的 PhotoXMP 一致。
- 同名 JSON（“输出照片陀螺仪信息”打开时）：字段与 iOS 一致。

### 6.8 状态文字的亮度采样

单镜头时复用连续的全尺寸 YUV 帧，每秒约 4 次在“画面上沿那一条”抽样平均亮度；> 0.62 用黑字、< 0.5 换回白字（与 iOS 相同的回差）。多镜头、遥控模式、相机未就绪时固定白字。

---

## 7. 传感器与静止判定

- `SensorManager`：陀螺仪（`TYPE_GYROSCOPE`，100 Hz）+ 重力（`TYPE_GRAVITY`）+ 姿态（`TYPE_GAME_ROTATION_VECTOR`，各 50 Hz）。
- 传感器事件的 `timestamp` 与相机帧时间戳都基于 `elapsedRealtimeNanos`，可以把“这一帧曝光期间的角速度”精确对齐，算出这张照片的模糊量。
- 模糊估算：模糊像素 ≈ 角速度 × 曝光时间 × 焦距（像素）；焦距由 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 与传感器尺寸换算。
- 静止判定：三脚架 / 手持 / 无判定三种模式，阈值（允许模糊量、静止持续时长）与 iOS 设置字段一致。

---

## 8. 时间与对时

- 本机单调时钟：`SystemClock.elapsedRealtimeNanos() / 1e9`（秒，Double）。等价于 iOS 的 `MonoClock.now()`；两个平台各用各的时钟，对时求的是“主机时钟 − 本机时钟”的偏差，时基不同不影响。
- 算法与 iOS 相同（NTP 式）：一轮连发 30 个探测、间隔 15 ms，取往返最短的样本；刚连上时发出 6 个后先报临时结果；对上后按设置的间隔（默认 20 秒）重新对时，没对上时 0.5 秒后重试。
- 主机收到探测**在网络线程上立刻回复**，不经过主线程。

---

## 9. 连接

### 9.1 传输方式与优先级

| 顺序 | 方式 | 适用 | 备注 |
|---|---|---|---|
| 1 | **局域网**（同一 Wi‑Fi 或主机开热点）：NSD 发现 `_tutticam._tcp` | Android ↔ Android，**Android ↔ iPhone** | iOS 现有的 Bonjour 在普通 Wi‑Fi 上本来就广播，iOS 端无需改动；第一版主路径 |
| 2 | **Wi‑Fi Aware** | Android ↔ Android，**Android ↔ iPhone（iOS 26+）** | 系统自带、不依赖 GMS、点对点低时延，不需要路由器；Android 需硬件支持（`FEATURE_WIFI_AWARE`）和 API 29+，与 iPhone 配对需 Android 14+ 的配对接口；**iOS 端同步接入**（见 9.3） |
| 3 | **Wi‑Fi Direct** | Android ↔ Android | 所有安卓都有；主机当组长（星型）；系统会弹确认框 |
| 4 | **BLE** | 兜底，只传控制消息 | 对时精度明显变差，界面提示 |
| — | Nearby Connections | 不作为主干 | 依赖 GMS；只能安卓之间；底层链路不可控 |

所有方式实现同一个 `Link` 接口（发现、连接、可靠消息、数据报、断开事件），上层（主机调度、对时）不关心底层链路。目前只实现了局域网（`LanLink`），2–4 是计划。

### 9.2 与 iOS 的差异处理

- iOS 用 AWDL 点对点，Android 没有 AWDL。Android 与 iPhone 混拍：同一 Wi‑Fi / 热点下走局域网；不在同一网络时走 Wi‑Fi Aware（两端都支持时）。设备页显示当前链路。
- Android 的 UDP 发送与接收用同一个 `DatagramSocket`；主机的 UDP 端口通过 `udpPort` 消息告知（与 iOS 相同），不依赖 NSD 解析。
- 断线重连：指数退避（1、2、4…秒，上限 30 秒，成功后重置），“重新连接”按钮立即重试，与 iOS 一致。
- 局域网组播：发现期间持有 `WifiManager.MulticastLock`。

### 9.3 Wi‑Fi Aware（两端）

两端用同一个服务名 `_tutticam._tcp`（数据另开 UDP 时用 `_tutticam._udp`），数据链路建立后，上层协议与局域网完全相同（见 PROTOCOL.md），只是地址是 Wi‑Fi Aware 数据链路上的 IPv6 链路本地地址。

| | iOS（26+） | Android（API 29+，与 iPhone 配对需 34+） |
|---|---|---|
| 框架 | WiFiAware（`WAPublisherListener` / `WASubscriberBrowser`，基于 Network 框架） | `WifiAwareManager`：publish / subscribe 会话，`WifiAwareNetworkSpecifier` 建数据链路 |
| 声明 | Info.plist 声明可发布 / 可订阅的服务；Wi‑Fi Aware 能力（entitlement） | `android.hardware.wifi.aware` 特性（非必需）、`NEARBY_WIFI_DEVICES` / 定位权限 |
| 配对 | 设备之间先配对一次（系统的 DeviceDiscoveryUI 配对界面），之后直接连 | 与 iPhone 互通需走 Wi‑Fi Aware 配对（`AwarePairingConfig`）；Android 之间可用开放或口令数据链路 |
| 角色 | 主机发布（publish），从机订阅（subscribe） | 同左 |
| 性能 | `WAPerformanceMode` 选实时（低时延） | 数据链路无单独设置；UDP 对时探测照常 |

- 主机同时在局域网（NSD / Bonjour）和 Wi‑Fi Aware 上提供会话；从机两边都找，优先已在同一局域网的，其次 Wi‑Fi Aware。
- 配对只在第一次需要；设备页提供“与新设备配对”入口。
- 风险：两端的配对互通需真机验证（S24 + iPhone，均需系统版本满足要求）；作为里程碑单独验收。

---

## 10. 网络协议

见 [PROTOCOL.md](PROTOCOL.md)。要点：

- TCP：4 字节大端长度 + JSON；UDP：一个数据报一个 JSON（`{"p": 发送方, "m": 消息}`）。
- JSON 形态与 Swift 自动生成的 Codable 完全一致（枚举外包一层 case 名、无标签参数用 `_0`）。
- **iOS 解码设置时所有字段都必填**：Android 当主机时，`settings` 必须带齐全部字段。
- `protocol` 模块用 iOS 实际报文作为测试样例，保证双向兼容。

---

## 11. 外接相机（BLE）

- 已实现。DJI Osmo 360 已在三星 Galaxy S24 上实测连接、休眠和同步拍摄；其他品牌在安卓端还没有用真相机测试过。
- iOS 端全部是 BLE **中心角色**（`CBCentralManager`），Android 用 `BluetoothLeScanner` + `BluetoothGatt` 对应。
- 照搬：各品牌（DJI、GoPro、索尼、佳能、富士、尼康、理光）的 UUID、报文格式、配对流程、广播识别规则、提前量设置。
- 重写：传输层。Android BLE 需要注意：GATT 操作必须**串行排队**（一次只做一个读 / 写 / 订阅）；MTU 协商；绑定弹窗；Android 12+ 的 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`（带 `neverForLocation`），更低版本需要定位权限；国产 ROM 的后台限制与扫描节流。
- 一致性保证：`protocol` 模块的 `DjiProtocolTest`、`AccessoryProtocolTest` 沿用 iOS `Tests/` 里的用例和报文。
- 界面：外接相机页（扫描、配对码、改名改编号、重新连接、忘记、DJI 休眠）、设备卡片里列出各设备的外接相机、状态面板显示电量和剩余张数、设置里按品牌调触发提前量；第一次连上时提醒固定在同一支架上。
- DJI 模式：连上后不再强制切全景拍照。拍照模式每组按快门；录像类模式（慢动作、视频、低光视频、全景视频、极广角视频）在开始拍摄时用拍录控制 1D03 开录、停止拍摄时停录，只停自己开的，每组不按快门，录像中也不算忙；拍摄中切到录像模式或重连上来也会开录。延时、直播等其他模式连上后切到拍照（Osmo 360 为全景拍照）。
- 多台相机：同一时间只让一台建立连接（连上、MTU、发现服务、握手），其余排队，建立期间暂停扫描；新相机遇到蓝牙错误 133 隔 1 秒重连，最多 2 次；前两台用高连接优先级，第三台起用均衡优先级。安卓同时协商几条连接、一边高频扫描一边连接、几条高频连接一起时，后连的容易报 133。
- DJI 休眠：外接相机页的“休眠”发电源模式命令 001A（power_mode 3），相机应答后停止推送状态，App 也不再给它发数据（DJI 要求，否则可能堵塞）。唤醒要按相机上的按键，醒来后自动重连、恢复拍摄。
- 不做 DJI 蓝牙唤醒：DJI 示例用 2 秒的 `0A FF 'W''K''P' + 反序相机 MAC` 广播唤醒，前提是“最近连过该相机的遥控器”。在 S24 上试过带 / 不带 Flags、可连接 / 不可连接、4 路并发 6 秒，都叫不醒。推测相机按广播的发送地址认遥控器，而安卓应用只能用随机地址广播（指定地址的 `setOwnAddressType` 是系统 API）；iOS 也发不出厂商数据广播。

---

## 12. 存储与输出

与 iOS 输出结构一致，便于混合重建：

- 位置：`Documents/TuttiCam/<场景名>_<日期>_<时间>/`（MediaStore，`RELATIVE_PATH`；在“文件”和相册里可见）。放 Documents 是因为同一文件夹里还有 JSON，Pictures 只允许放图片。
- 照片：`r0001_A01_w.jpg`（组号_设备编号_镜头 uw / w / t / f）；HEIC 为 `.heic`；RAW 为同名 `.dng`。
- `session.json`：设备列表、本次设置、对时质量（字段同 iOS 的 SessionRecord）。
- 同名 JSON、外接相机触发记录：字段同 iOS。
- “强制照片同步”：先写入 `.pending/`，主机确认保留后移入正式目录，与 iOS 一致。

---

## 13. 内购（未实现）

安卓版目前没有内购，也没有免费版的设备上限。以下是计划：

- 抽象 `BillingProvider` 接口：Google Play 结算（订阅月度 / 年度、7 天免费试用、回归优惠、买咖啡）；国内渠道（华为、小米等）后续按需接入。
- 显示规则与 iOS 一致：读不到商品或试用资格时不提免费试用，价格按当前地区实时读取，不写死。
- 专业版权益不与 iOS 互通（不同商店）。主机的专业版决定会话设备上限（免费 3 台、专业版 8 台）——与 iOS 规则一致，混合会话以主机为准。

---

## 14. 权限

| 权限 | 时机 |
|---|---|
| 相机 | 引导页（必需） |
| 位置（精确） | 引导页（可选，用于 GPS 写入照片）；API 28–32 的 Wi‑Fi / BLE 扫描也需要 |
| `NEARBY_WIFI_DEVICES` | API 33+，使用 Wi‑Fi Aware / Direct 时 |
| `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` | API 31+，打开外接相机连接时 |
| 写入媒体 | API 28 需要 `WRITE_EXTERNAL_STORAGE`；29+ 走 MediaStore 不需要 |
| `INTERNET`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE` | 安装时授予 |

---

## 15. 电源与生命周期

- 拍摄页常亮；拍摄中不允许系统锁屏。
- 相机只在前台运行（与 iOS 一致）；进入后台时停止拍摄并释放相机，回到前台自动恢复，网络链路按退避重建。
- 发热：监听 `PowerManager.getCurrentThermalStatus()`（API 29+），对应 iOS 的发热等级，上报给主机。
- 国产 ROM：在设置页提示把 App 加入电池优化白名单（不强制）。

---

## 16. 测试

| 层级 | 内容 |
|---|---|
| 单元测试（JVM） | `protocol` 模块：与 iOS 实际报文互相编解码；对时算法；设置合并 |
| 仪器测试 | 相机能力探测、MediaStore 写入 |
| 真机 | 三星 S24（首测）；后续补 Pixel、小米、OPPO / vivo、荣耀 |
| 互通 | S24 加入 iPhone 主机会话；iPhone 加入 S24 主机会话；混合拍摄同步偏差统计 |
| 同步精度 | 两台设备对着同一块显示毫秒计时的屏幕拍摄，或用 `SENSOR_TIMESTAMP` 回报的偏差统计；目标 < 1 ms（相位对齐后） |

---

## 17. 里程碑

1. **骨架**：工程、主题、悬浮工具栏组件、开始页 / 拍摄页框架。
2. **协议与连接**：`protocol` 模块（与 iOS 互测）、局域网 Link、对时。**验收：S24 能加入 iPhone 主机的会话并显示对时误差**。
3. **相机**：Camera2 预览、能力探测、按时刻取帧、手动参数、保存与元数据。**验收：S24 与 iPhone 混拍，偏差可见**。
4. **主机**：Android 当主机，调度、静止判定、设备页。**验收：iPhone 加入 S24 主机会话**。
5. **完善**：设置页全部项、遥控模式、引导、多语言、布局（rail / wide）。
6. **Wi‑Fi Aware（iOS 与 Android 同时）**：两端发布 / 订阅、配对、数据链路上跑同一协议。**验收：S24 与 iPhone 不连同一 Wi‑Fi，也能互相加入会话**。
7. 后续：外接相机、Wi‑Fi Direct、多镜头、帧相位对齐、内购、路线小地图。

**当前进度（2026-10-15）**：1–5 已在 S24 真机验收，S24 与 iPhone 混拍互通；多镜头、外接相机（未接真相机）、HEIC / RAW 也已完成。实现中定下的细节：

- 降噪 / 锐化按拍摄质量：画质优先用 HIGH_QUALITY（可能拉低帧率，取帧偏差随之变大），均衡、速度优先用 FAST，保证 30 fps。
- S24 的长焦没有独立的相机编号，靠主摄的 `CONTROL_ZOOM_RATIO` 3x 切过去；两颗后置同时拍走逻辑多摄的物理子镜头。
- 手机上界面固定竖屏（`sensorPortrait`，倒拿时系统自己翻转，对应 iOS 的倒拿翻转）；Android 16 起大屏会忽略方向锁定，由 phone / 侧栏两种布局适配。
- 陀螺仪静止噪声底暂时沿用 iPhone 实测值，安卓机型待实测。
- 未做：Wi‑Fi Aware（里程碑 6）、Wi‑Fi Direct、帧相位对齐、4800 万像素、内购、路线小地图。

---

## 18. 风险与未决问题

| 风险 / 问题 | 应对 |
|---|---|
| 三星等厂商对第三方开放的物理镜头有限 | 能力探测为准，不支持的组合不显示 |
| 部分机型 `TIMESTAMP_SOURCE` 不是 REALTIME | 退回按触发时刻估计，界面标注精度受限 |
| Android 与 iPhone 不在同一 Wi‑Fi 时的互通 | Wi‑Fi Aware 两端同时接入（里程碑 6）；配对互通需真机验证 |
| Swift 的 JSON 形态在 Android 端照抄易错 | 协议规范 + 以 iOS 实际报文做双向测试 |
| 悬浮工具栏是实验 API | 自有组件包一层，锁定版本 |
| 国内无 GMS / 无 Google Play | 核心功能不依赖 GMS；内购渠道待定 |
| 8 GB 内存的开发机编译慢 | Gradle 内存参数调优；模块少而精 |

**待你决定**

1. 是否需要覆盖国内没有 Google 服务的安卓机（影响内购渠道）。
2. 国内上架哪些商店。
（已定：iOS 端同步接入 Wi‑Fi Aware。）
