import Foundation

// 用 iOS 原始模型代码生成真实报文（golden），并能反向解码 Android 生成的报文。
// 用法：
//   golden encode            输出每行 "名称\tJSON"
//   golden decode <文件>     逐行（"名称\tJSON"）按 Message / LinkHello / Datagram 解码，失败时退出码 1

nonisolated struct LinkHello: Codable { var key: String; var name: String }
nonisolated struct Datagram: Codable { var p: String; var m: Message }

let encoder = JSONEncoder()
encoder.outputFormatting = [.sortedKeys]

func line(_ name: String, _ value: some Encodable) {
    let data = try! encoder.encode(value)
    print(name + "\t" + String(decoding: data, as: UTF8.self))
}

var custom = CaptureSettings()
custom.lens = LensConfig(primary: .wide, secondary: .ultraWide)
custom.lensOverrides = [2: LensConfig(primary: .front), 5: LensConfig(primary: .telephoto)]
custom.quality = .quality
custom.format = .rawHeic
custom.manualShutter = true
custom.shutterSeconds = 1.0 / 125
custom.iso = 250
custom.whiteBalanceKelvin = 5600
custom.whiteBalanceTint = 3
custom.exposureBias = -0.7
custom.stability = .off
custom.frontFraming = .wide43
custom.accessoryLeads = ["sony": 0.12]
custom.osmoLead = 0.05

let info = DeviceInfo(name: "iPhone 17 Pro", model: "iPhone18,1", systemVersion: "27.1",
                      lenses: [.ultraWide, .wide, .telephoto, .front],
                      dualCombos: [LensConfig(primary: .wide, secondary: .ultraWide)], squareFront: true)
var status = DeviceStatus()
status.still = true
status.blurPx = 0.42
status.cameraReady = true
status.lens = LensConfig(primary: .wide)
status.battery = 0.82
status.freeBytes = 112_000_000_000
status.thermal = 1
status.accessories = [AccessoryBrief(name: "Osmo360-624C", number: 1, code: "D01", brand: "dji", connected: true,
                                     battery: 87, remainPhotos: nil, capturing: false, tooHot: false)]
var bare = DeviceStatus()
bare.cameraError = "相机未就绪"
let report = RoundReport(round: 12, deviceIndex: 2, missed: false, photos: [
    PhotoReport(lens: .wide, file: "r0012_A02_w.jpg", offsetMs: 0.4, moving: false, blurPx: 0.42),
    PhotoReport(lens: .ultraWide, file: "r0012_A02_uw.jpg", offsetMs: nil, moving: true, blurPx: nil),
], error: nil)
let missed = RoundReport(round: 13, deviceIndex: 3, missed: true, photos: [], error: "尚未对时")

if CommandLine.arguments.count >= 2, CommandLine.arguments[1] == "decode" {
    let text = try! String(contentsOfFile: CommandLine.arguments[2], encoding: .utf8)
    var failures = 0
    for raw in text.split(separator: "\n") where !raw.isEmpty {
        let parts = raw.split(separator: "\t", maxSplits: 1)
        let name = String(parts[0]), json = Data(parts[1].utf8)
        do {
            if name.hasPrefix("linkHello") { _ = try JSONDecoder().decode(LinkHello.self, from: json) }
            else if name.hasPrefix("datagram") { _ = try JSONDecoder().decode(Datagram.self, from: json) }
            else { _ = try JSONDecoder().decode(Message.self, from: json) }
            print("OK   \(name)")
        } catch {
            failures += 1
            print("FAIL \(name): \(error)")
        }
    }
    exit(failures == 0 ? 0 : 1)
}

line("linkHello", LinkHello(key: "6F1C0A7E-2B4D-4C1E-9F00-1234567890AB", name: "iPhone 17 Pro"))
line("hello", Message.hello(info))
line("welcome", Message.welcome(index: 2, code: DeviceCode(group: "A", number: 2)))
line("lensRequest", Message.lensRequest(LensConfig(primary: .ultraWide)))
line("rejected", Message.rejected(reason: "会话已满（最多 8 台设备）"))
line("settingsDefault", Message.settings(CaptureSettings()))
line("settingsCustom", Message.settings(custom))
line("ping", Message.ping(seq: 7, t0: 1234.567891))
line("pong", Message.pong(seq: 7, t0: 1234.567891, t1: 88.125, t2: 88.1251))
line("status", Message.status(status))
line("statusBare", Message.status(bare))
line("syncQuality", Message.syncQuality(rttMs: 3.1, errorMs: 1.55))
line("start", Message.start(session: "场景01_20261014_153000"))
line("renameFolder", Message.renameFolder(from: "场景01_20261014_153000", to: "客厅_20261014_153000"))
line("stop", Message.stop)
line("schedule", Message.schedule(round: 12, hostTime: 98.75))
line("report", Message.report(report))
line("reportMissed", Message.report(missed))
line("resolve", Message.resolve(round: 12, keep: true))
line("udpPort", Message.udpPort(54321))
line("datagramPong", Datagram(p: "host", m: .pong(seq: 1, t0: 1, t1: 2, t2: 3)))
line("datagramStatus", Datagram(p: "6F1C0A7E-2B4D-4C1E-9F00-1234567890AB", m: .status(status)))
