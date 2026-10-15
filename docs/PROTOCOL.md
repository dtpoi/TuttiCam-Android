# 齐拍即合 TuttiCam · 网络协议 v1

本文是 iOS 版现行协议的书面规范（以 iOS 源码 `Network/PeerLink.swift`、`Models/Messages.swift`、`Models/CaptureSettings.swift` 为准），Android 版必须与之**逐字节兼容**，才能与 iPhone 混合组队。

- 适用：iOS 齐拍即合 1.1 起；Android 第一版
- 传输：局域网（Bonjour / NSD）；以后的 Wi‑Fi Aware 数据链路沿用同一协议

---

## 1. 时间

- 每台设备用**自己的单调时钟**，单位**秒**，`Double`：
  - iOS：`mach_absolute_time` 换算的秒
  - Android：`SystemClock.elapsedRealtimeNanos() / 1e9`
- 两台设备的时钟起点不同。对时求的是偏差 θ = 主机时钟 − 本机时钟（见 §6），协议中所有时间字段都注明是谁的时钟。

## 2. 发现

| 项 | 值 |
|---|---|
| TCP 服务类型 | `_tutticam._tcp` |
| UDP 服务类型 | `_tutticam._udp`（主机也广播；从机现在通过 `udpPort` 消息拿端口，不依赖它） |
| 服务实例名 | **会话名 = 主机的设备名称**（界面上“加入会话”列表显示的名字） |
| 域 | `local.` |

主机同时监听一个 TCP 端口和一个 UDP 端口（都由系统分配）。

## 3. 帧格式

### 3.1 TCP

```
| 长度 L（4 字节，大端，无符号） | JSON 正文（L 字节，UTF‑8） |
```

- 0 < L < 16 000 000，否则断开。
- TCP 选项：`TCP_NODELAY`；keepalive（空闲 2 秒、间隔 1 秒、3 次）。

### 3.2 UDP

一个数据报就是一个 JSON 对象（不加长度前缀）：

```json
{"p": "<发送方>", "m": <Message>}
```

- `p`：主机发出的数据报填 `"host"`；从机填自己的 `key`（握手时的 UUID 字符串，见 §4）。
- 主机按 `p` 把数据报归到对应设备，并记住该设备的 UDP 对端地址，之后回复都发往这个地址。

## 4. 连接与握手

```
从机                                       主机
 │ ── TCP 连接 ───────────────────────────▶ │
 │ ── 帧1：LinkHello {"key","name"} ──────▶ │  （第一帧必须是 LinkHello，否则主机断开）
 │ ◀────────────── udpPort {UDP 端口} ───── │
 │ ── hello(DeviceInfo) ──────────────────▶ │
 │ ◀──────── welcome(index, code) ───────── │  或 rejected(reason) 后不再处理
 │ ◀──────── settings(CaptureSettings) ──── │
 │ ◀──────── start(session)（如在拍摄中）── │
 │ ── 对时、状态（见 §6、§7）──────────────▶ │
```

- **LinkHello**（不是 Message，单独的结构）：
  ```json
  {"key": "6F1C0A7E-…（UUID 字符串）", "name": "设备名称"}
  ```
  `key` 每次启动 App 生成一次；主机用它识别同一台设备的 TCP 与 UDP。主机若已有同一 `key` 的旧 TCP 连接，断开旧的。
- 从机收到 `udpPort` 后，用 **TCP 连接的对端 IP** + 该端口建立 UDP。UDP 建好之前，探测与状态走 TCP。
- 主机收到 `hello` 后分配设备序号 `index`（主机自己是 1，从机 2…8）和编号 `code`；同名设备已在线时回 `rejected`；会话已满（含主机 8 台）时回 `rejected`。iOS 主机是免费版时，超过 3 台也回 `rejected`（安卓版没有这条限制）。
- 同名设备断线后用相同名称重连：沿用原来的 `index` 和 `code`。

## 5. JSON 编码规则（Swift Codable 形态）

Android 必须按 Swift `JSONEncoder` / `JSONDecoder` 自动生成的形态编解码：

1. **枚举消息**外包一层，键是 case 名：
   - 带标签参数：`{"ping": {"seq": 1, "t0": 12.5}}`
   - 无标签参数：键为 `_0`，如 `{"hello": {"_0": {…DeviceInfo…}}}`
   - 无参数：`{"stop": {}}`
2. **可选字段**为空时**省略**（不写 `null`）；解码时缺失视为空。
3. **非可选字段解码时必填**，即使 Swift 里给了默认值。Android 发出的 `settings`、`status`、`hello` 等必须**带齐全部字段**，否则 iOS 整条丢弃。
4. 整数字段（`Int`、`Int64`、`UInt16`）必须写成**整数**（不能带小数点）；`Double` / `Float` 写普通数字。
5. 以 `Int` 为键的字典编码为 **JSON 对象，键是数字字符串**：`{"2": {…}, "5": {…}}`。
6. 原始值枚举编码为字符串（见 §8）。
7. 未知字段：两端都应**忽略**（便于以后加字段）。Swift 自动生成的解码会忽略多余的键。

## 6. 对时（NTP 式）

```
从机 t0 ── ping(seq, t0) ──▶ 主机 t1（收到时刻）
从机 t3 ◀── pong(seq, t0, t1, t2) ── 主机 t2（发出时刻）
```

- t0、t3：从机时钟；t1、t2：主机时钟。
- 偏差 θ = ((t1 − t0) + (t2 − t3)) / 2（主机 − 从机）；往返 δ = (t3 − t0) − (t2 − t1)；误差上界 = δ / 2。
- 一轮：连发 30 个 ping，间隔 15 ms，结束后再等 300 ms，取 **δ 最小**的样本作为 θ。还没对上时，发出 6 个后先用当前最好的样本（临时结果），并上报。
- 对上后按设置 `syncInterval`（默认 20 秒）重做；没对上（一轮没收到任何 pong）0.5 秒后重试，且这条连接改用 TCP 发探测。
- **主机收到 ping 必须在网络线程上立刻回 pong**（不经过主线程），从哪条路收到（TCP / UDP）就从哪条路回。
- 每轮结束后从机发 `syncQuality(rttMs, errorMs)`（单位毫秒）。

本机时刻与主机时刻换算：`本机 = 主机 − θ`，`主机 = 本机 + θ`。

## 7. 状态

- 从机每 **50 ms** 发一次 `status(DeviceStatus)`，走 UDP（UDP 未就绪时走 TCP）。
- 主机认为 1 秒内收到的状态是“新鲜”的；不新鲜的设备按“在动”处理，不参与静止判定。
- 主机自己的状态不发出去，只在本机使用。

## 8. 类型

### 原始值枚举（JSON 字符串）

| 类型 | 取值 |
|---|---|
| `LensKind` | `"uw"` 超广角、`"w"` 主摄、`"t"` 长焦、`"f"` 前置 |
| `QualityMode` | `"quality"`、`"balanced"`、`"speed"` |
| `PhotoFormat` | `"jpeg"`、`"heic"`、`"rawJpeg"`、`"rawHeic"`；安卓另有 `"raw"`（只存 DNG），安卓主机发给 iPhone 时换成 `"rawJpeg"`，安卓解码时不认识的值按 `"jpeg"` |
| `StabilityMode` | `"tripod"`、`"handheld"`、`"off"` |
| `FrontFraming` | `"square"`、`"wide43"`、`"tall43"`（解码兼容旧值 `"wide"` → wide43、`"tall"` → tall43，其他 → square） |
| `CameraBrand` | `"dji"`、`"gopro"`、`"sony"`、`"canon"`、`"fujifilm"`、`"nikon"`、`"ricoh"` |

### 结构

```
DeviceCode     { group: String, number: Int }                     // 编号 = group + 两位数字，如 A01
LensConfig     { primary: LensKind, secondary?: LensKind }        // secondary 为空 = 单镜头
StillnessParams{ blurPx: Double, holdSeconds: Double }

DeviceInfo {
  name: String, model: String, systemVersion: String,
  lenses: [LensKind],               // 本机可用的单镜头
  dualCombos: [LensConfig],         // 本机可同时用的双镜头组合（主镜头在前）
  squareFront?: Bool                // 前置是方形传感器
}

DeviceStatus {                       // 全部字段必填（可选的除外）
  still: Bool, blurPx: Double,
  ready: Bool,                      // 上一组已保存完、外接相机空闲
  cameraReady: Bool, cameraError?: String,
  lens?: LensConfig,                // 实际在用的镜头
  battery: Float,                   // 0…1，未知为 -1
  freeBytes: Int64,                 // 剩余存储字节
  thermal: Int,                     // 0 正常 … 3 危急
  accessories: [AccessoryBrief]
}

AccessoryBrief {
  name: String, number: Int, code: String, brand: String,   // brand 为 CameraBrand 的取值
  connected: Bool, battery?: Int, remainPhotos?: Int,
  capturing: Bool, tooHot: Bool, sleeping: Bool
}

PhotoReport {
  lens: LensKind, file: String,     // 文件名，如 "r0012_A02_w.jpg"
  offsetMs?: Double,                // 实际曝光时刻 − 约定时刻（毫秒），正数 = 晚
  moving: Bool, blurPx?: Double
}

RoundReport { round: Int, deviceIndex: Int, missed: Bool, photos: [PhotoReport], error?: String }
```

### CaptureSettings（`settings` 消息，iOS 解码时**每个字段都必填**）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `lens` | LensConfig | `{"primary":"w"}` | 全局镜头 |
| `lensOverrides` | {Int: LensConfig} | `{}` | 按设备序号单独指定（JSON 键为数字字符串） |
| `quality` | QualityMode | `"balanced"` | |
| `format` | PhotoFormat | `"jpeg"` | |
| `writeAttitude` | Bool | false | 姿态写入 XMP |
| `writeLocation` | Bool | true | GPS 写入照片 |
| `saveToPhotos` | Bool | false | 同时存入系统相册 |
| `writePhotoJSON` | Bool | false | 每张照片配同名 JSON |
| `lockAll` | Bool | false | 锁定全部相机参数 |
| `prefer48MP` | Bool | true | 主摄优先 4800 万像素 |
| `manualShutter` | Bool | false | |
| `manualISO` | Bool | false | |
| `shutterSeconds` | Double | 0.004 | 1/250 |
| `iso` | Float | 100 | |
| `lockFocus` | Bool | false | |
| `lockWhiteBalance` | Bool | false | |
| `whiteBalanceKelvin` | Float | 5500 | |
| `whiteBalanceTint` | Float | 0 | |
| `exposureBias` | Float | 0 | EV |
| `stability` | StabilityMode | `"tripod"` | |
| `tripod` | StillnessParams | `{1.0, 0.3}` | |
| `handheld` | StillnessParams | `{3.0, 0.3}` | |
| `hostStillnessOnly` | Bool | false | 只看主机的陀螺仪 |
| `minInterval` | Double | 2.0 | 两组之间的最短间隔（秒） |
| `triggerLead` | Double | 0.25 | 安排拍摄时刻的提前量（秒） |
| `syncInterval` | Double | 20.0 | 对时间隔（秒） |
| `forceSync` | Bool | false | 强制照片同步（整组保留或作废） |
| `frontFraming` | FrontFraming | `"square"` | 方形前置的画幅 |
| `osmoLead` | Double | 0.0 | DJI 外接相机提前量（秒） |
| `accessoryLeads` | {String: Double} | `{}` | 其他品牌提前量，键为 CameraBrand |
| `osmoExtraWait` | Double | 1.0 | 有外接相机时每组额外等待（秒） |

派生规则（两端一致）：

- `lockExposure = manualShutter && manualISO`
- 长曝光：`manualShutter && shutterSeconds > 1/15 × 1.01`
- 实际提前量 `scheduleLead`：长曝光时 `max(triggerLead, shutterSeconds × 2 + 0.3)`，否则 `triggerLead`
- 某设备的镜头：`lensOverrides[index] ?? lens`
- iOS 主机没有专业版时，下发前把 `manualShutter`、`manualISO` 置为 false（“实际下发的设置”）；安卓主机原样下发

## 9. 消息一览

| 消息 | 方向 | 通道 | JSON 示例 |
|---|---|---|---|
| `hello` | 从 → 主 | TCP | `{"hello":{"_0":{"name":"S24","model":"SM-S9210","systemVersion":"16","lenses":["uw","w","t","f"],"dualCombos":[]}}}` |
| `welcome` | 主 → 从 | TCP | `{"welcome":{"index":2,"code":{"group":"A","number":2}}}` |
| `lensRequest` | 从 → 主 | TCP | `{"lensRequest":{"_0":{"primary":"uw"}}}` |
| `rejected` | 主 → 从 | TCP | `{"rejected":{"reason":"会话已满"}}` |
| `settings` | 主 → 从 | TCP | `{"settings":{"_0":{…全部字段…}}}` |
| `ping` | 从 → 主 | UDP / TCP | `{"ping":{"seq":7,"t0":1234.567891}}` |
| `pong` | 主 → 从 | 同 ping | `{"pong":{"seq":7,"t0":1234.567891,"t1":88.12,"t2":88.1201}}` |
| `status` | 从 → 主 | UDP / TCP | `{"status":{"_0":{…DeviceStatus…}}}` |
| `syncQuality` | 从 → 主 | TCP | `{"syncQuality":{"rttMs":3.1,"errorMs":1.55}}` |
| `start` | 主 → 从 | TCP | `{"start":{"session":"场景01_20261014_153000"}}` |
| `renameFolder` | 主 → 从 | TCP | `{"renameFolder":{"from":"旧名_…","to":"新名_…"}}` |
| `stop` | 主 → 从 | TCP | `{"stop":{}}` |
| `schedule` | 主 → 从 | TCP | `{"schedule":{"round":12,"hostTime":98.75}}`（主机时钟） |
| `report` | 从 → 主 | TCP | `{"report":{"_0":{"round":12,"deviceIndex":2,"missed":false,"photos":[…]}}}` |
| `resolve` | 主 → 从 | TCP | `{"resolve":{"round":12,"keep":true}}`（仅强制同步） |
| `udpPort` | 主 → 从 | TCP | `{"udpPort":{"_0":54321}}` |

## 10. 拍摄流程

1. 主机每 50 ms 跑一次调度：
   - 有进行中的一组：所有参与设备都回报了，或超过 `hostTime + 6` 秒 → 结算（强制同步时发 `resolve`）。
   - 没有进行中的组、正在拍摄、距上一组 ≥ `minInterval`（有外接相机时再加 `osmoExtraWait`）：
     - 刚改过会重配相机的设置（镜头、格式、48MP、前置画幅）：最多 5 秒内等各设备在改动后报 `cameraReady`。
     - 参与设备 = 已连接、已对时、状态新鲜、`cameraReady` 的设备；`ready` 为假的设备 5 秒内等它，超时跳过。
     - 静止判定：`off` 不判定；`hostStillnessOnly` 只看主机；否则所有参与设备都 `still`。
     - 满足后 `round += 1`，`hostTime = 现在 + scheduleLead`，向参与的从机发 `schedule`。
2. 从机收到 `schedule`：换算成本机时刻，在该时刻拍摄（Android：取帧时间戳最接近的一帧），拍完发 `report`。没对时、相机未就绪、指令到达太晚（离约定时刻 < 3 ms）时回报 `missed: true` 并写 `error`。
3. 强制同步（`forceSync`）时照片先放待定目录，等 `resolve` 决定保留或删除；迟到的 `report` 主机直接回 `resolve(keep: false)`。

## 11. 文件命名（两端一致，便于混合重建）

- 场景文件夹：`<场景名>_<yyyyMMdd>_<HHmmss>`（`start.session` 即文件夹名）
- 照片：`r%04d_<设备编号>_<镜头>.<jpg|heic>`，如 `r0012_A02_w.jpg`；RAW 同名 `.dng`（只存 RAW 时只有 `.dng`）
- 同名 JSON、外接相机记录 `r%04d_<设备编号>_<外接相机编号>.json`、`session.json`：字段同 iOS

## 12. 兼容性约定

- 新增字段一律做成**可选**，并让接收方忽略未知字段；不改动已有字段的含义与类型。
- Android 发出的 `LinkHello` 可以附加 `"v": 1`（协议版本），iOS 会忽略；以后 iOS 也加上后，双方可据此判断能力。
- 任何一端改协议，都要同步更新本文，并用对方的实际报文做回归测试。
