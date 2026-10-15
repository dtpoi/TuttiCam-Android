// 用“模糊后看重心”的办法检查图标是否视觉居中，并量出内容离画布中心的最远距离（检查是否在安全区内）。
// 输入：按 108dp 画布渲染的前景图（白底 PNG，边长任意）。输出以 dp 为单位。
// 用法：swift optical_center.swift <前景.png> [模糊半径 dp，默认 6]
import AppKit
import CoreImage

let path = CommandLine.arguments[1]
let blurDp = CommandLine.arguments.count > 2 ? Double(CommandLine.arguments[2])! : 6
guard let image = NSImage(contentsOfFile: path),
      let cg = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else { fatalError("读不了 \(path)") }
let size = cg.width
let dp = Double(size) / 108

// 把 alpha 合成到白底上，按“墨量 = 1 − 亮度”计算视觉重量。
func gray(_ img: CGImage) -> [Double] {
    var pixels = [UInt8](repeating: 0, count: size * size * 4)
    let ctx = CGContext(data: &pixels, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                        space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.setFillColor(.white)
    ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
    ctx.draw(img, in: CGRect(x: 0, y: 0, width: size, height: size))
    return stride(from: 0, to: pixels.count, by: 4).map { i in
        1 - (0.2126 * Double(pixels[i]) + 0.7152 * Double(pixels[i + 1]) + 0.0722 * Double(pixels[i + 2])) / 255
    }
}

let ink = gray(cg)
let blurred: CGImage = {
    let ci = CIImage(cgImage: cg).composited(over: CIImage(color: .white).cropped(to: CGRect(x: 0, y: 0, width: size, height: size)))
    let filter = CIFilter(name: "CIGaussianBlur")!
    filter.setValue(ci.clampedToExtent(), forKey: kCIInputImageKey)
    filter.setValue(blurDp * dp, forKey: kCIInputRadiusKey)
    let out = filter.outputImage!.cropped(to: ci.extent)
    return CIContext().createCGImage(out, from: ci.extent)!
}()
let mass = gray(blurred)

func centroid(_ w: [Double]) -> (Double, Double) {
    var sx = 0.0, sy = 0.0, s = 0.0
    for y in 0..<size { for x in 0..<size { let v = w[y * size + x]; sx += v * Double(x); sy += v * Double(y); s += v } }
    return (sx / s / dp, sy / s / dp)
}
let (gx, gy) = centroid(ink)
let (bx, by) = centroid(mass)
var minX = Double.infinity, minY = Double.infinity, maxX = 0.0, maxY = 0.0, maxR = 0.0
for y in 0..<size { for x in 0..<size where ink[y * size + x] > 0.04 {
    let px = Double(x) / dp, py = Double(y) / dp
    minX = min(minX, px); maxX = max(maxX, px); minY = min(minY, py); maxY = max(maxY, py)
    maxR = max(maxR, hypot(px - 54, py - 54))
} }
print(String(format: "几何中心（外接框）: (%.2f, %.2f) dp", (minX + maxX) / 2, (minY + maxY) / 2))
print(String(format: "墨量重心: (%.2f, %.2f) dp", gx, gy))
print(String(format: "模糊后重心: (%.2f, %.2f) dp   偏离画布中心 dx=%+.2f dy=%+.2f dp", bx, by, bx - 54, by - 54))
print(String(format: "内容外接框: %.1f × %.1f dp；最远点离中心 %.1f dp（安全区半径 33 dp，可见区半径 36 dp）", maxX - minX, maxY - minY, maxR))
