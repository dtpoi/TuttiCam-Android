import Foundation

// 复制 iOS 的 CameraProtocols.swift、AccessoryHub.swift 里与编解码相关的定义。
nonisolated enum CameraBrand: String, Codable, CaseIterable, Sendable {
    case dji, gopro, sony, canon, fujifilm, nikon, ricoh
}

nonisolated struct AccessoryBrief: Codable, Equatable, Sendable {
    var name: String
    var number: Int
    var code: String = ""
    var brand: String = CameraBrand.dji.rawValue
    var connected: Bool
    var battery: Int?
    var remainPhotos: Int?
    var capturing: Bool
    var tooHot: Bool
    var sleeping: Bool = false
}
