import CryptoKit
import Foundation
import ImageIO
import UIKit

/// Decode only the immutable host artifact; networking and source interceptors stay in source-host.
enum SourceImageFile {
    struct TileLayout {
        let frame: CGRect
        let decodedSize: CGSize
        let decodedOffset: CGSize
    }

    /// Source-space tiles include a sampling gutter; clip it after scaling to the page's point-space placement.
    static func tileLayout(logical: CGRect, decoded: CGRect, sourceSize: CGSize, displaySize: CGSize) -> TileLayout {
        let scaleX = displaySize.width / max(1, sourceSize.width)
        let scaleY = displaySize.height / max(1, sourceSize.height)
        return TileLayout(
            frame: CGRect(x: logical.minX * scaleX, y: logical.minY * scaleY,
                          width: logical.width * scaleX, height: logical.height * scaleY),
            decodedSize: CGSize(width: decoded.width * scaleX, height: decoded.height * scaleY),
            decodedOffset: CGSize(width: (decoded.minX - logical.minX) * scaleX,
                                  height: (decoded.minY - logical.minY) * scaleY)
        )
    }

    static func decode(directory: URL, relativePath: String, sha256: String, byteSize: Int64,
                       maxPixelSize: Int = 4096) throws -> UIImage {
        try decode(data: validatedData(directory: directory, relativePath: relativePath, sha256: sha256,
                                       byteSize: byteSize), maxPixelSize: maxPixelSize)
    }

    static func createSource(data: Data) throws -> CGImageSource {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else {
            throw failure("无法解码此图片格式")
        }
        return source
    }

    /// Decodes bytes already returned by [validatedData].
    static func decode(data: Data, maxPixelSize: Int = 4096) throws -> UIImage {
        let source = try createSource(data: data)
        return try decode(source: source, maxPixelSize: maxPixelSize)
    }

    /// Decodes a thumbnail directly from an existing [CGImageSource].
    static func decode(source: CGImageSource, maxPixelSize: Int = 4096) throws -> UIImage {
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, maxPixelSize),
        ] as CFDictionary) else { throw failure("无法解码此图片格式") }
        return UIImage(cgImage: image)
    }

    static func dimensions(directory: URL, relativePath: String, sha256: String,
                           byteSize: Int64) throws -> (width: Int, height: Int) {
        try dimensions(data: validatedData(directory: directory, relativePath: relativePath, sha256: sha256,
                                           byteSize: byteSize))
    }

    /// Reads the size of bytes already returned by [validatedData].
    static func dimensions(data: Data) throws -> (width: Int, height: Int) {
        let source = try createSource(data: data)
        return try dimensions(source: source)
    }

    /// Reads dimensions directly from an existing [CGImageSource].
    static func dimensions(source: CGImageSource) throws -> (width: Int, height: Int) {
        if let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
           let width = properties[kCGImagePropertyPixelWidth] as? Int,
           let height = properties[kCGImagePropertyPixelHeight] as? Int,
           width > 0, height > 0 {
            let orientation = (properties[kCGImagePropertyOrientation] as? Int) ?? 1
            return (5...8).contains(orientation) ? (height, width) : (width, height)
        }
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let image = CGImageSourceCreateImageAtIndex(source, 0, sourceOptions),
              image.width > 0, image.height > 0 else {
            throw failure("无法解码此图片格式")
        }
        return (image.width, image.height)
    }

    static func decodeRegion(directory: URL, relativePath: String, sha256: String, byteSize: Int64,
                             rect: CGRect, sampleSize: Int = 1) throws -> UIImage {
        let data = try validatedData(directory: directory, relativePath: relativePath, sha256: sha256, byteSize: byteSize)
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions),
              let full = CGImageSourceCreateImageAtIndex(source, 0, sourceOptions) else {
            throw failure("无法解码此图片格式")
        }
        let region = rect.integral.intersection(CGRect(x: 0, y: 0, width: full.width, height: full.height))
        guard !region.isEmpty, let cropped = full.cropping(to: region) else {
            throw failure("页面区域越界")
        }
        let sample = max(1, sampleSize)
        if sample == 1 { return UIImage(cgImage: cropped) }
        let targetWidth = max(1, Int(ceil(Double(cropped.width) / Double(sample))))
        let targetHeight = max(1, Int(ceil(Double(cropped.height) / Double(sample))))
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        guard let context = CGContext(data: nil, width: targetWidth, height: targetHeight,
                                      bitsPerComponent: 8, bytesPerRow: targetWidth * 4,
                                      space: colorSpace, bitmapInfo: bitmapInfo) else {
            return UIImage(cgImage: cropped)
        }
        context.interpolationQuality = .high
        context.draw(cropped, in: CGRect(x: 0, y: 0, width: targetWidth, height: targetHeight))
        guard let scaled = context.makeImage() else { return UIImage(cgImage: cropped) }
        return UIImage(cgImage: scaled)
    }

    static func splitPage(_ image: UIImage, segment: Int) -> UIImage {
        guard (segment == 1 || segment == 2), let cgImage = image.cgImage, cgImage.width >= 2 else { return image }
        let halfWidth = cgImage.width / 2
        let x = segment == 1 ? 0 : (cgImage.width - halfWidth)
        guard let half = cgImage.cropping(to: CGRect(x: x, y: 0, width: halfWidth, height: cgImage.height)) else {
            return image
        }
        return UIImage(cgImage: half, scale: image.scale, orientation: image.imageOrientation)
    }

    static func samplePixels(_ image: UIImage, sampleSize: Int) -> (width: Int, height: Int, pixels: [Int32])? {
        guard let cgImage = image.cgImage, cgImage.width > 0, cgImage.height > 0 else { return nil }
        let sample = max(1, sampleSize)
        let width = max(1, cgImage.width / sample)
        let height = max(1, cgImage.height / sample)
        var pixels = [Int32](repeating: 0, count: width * height)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        let bitmapInfo = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let base = buffer.baseAddress,
                  let context = CGContext(data: base, width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: colorSpace, bitmapInfo: bitmapInfo) else { return false }
            context.draw(cgImage, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? (width, height, pixels) : nil
    }

    static func crop(_ image: UIImage, rect: CGRect) -> UIImage {
        guard let cgImage = image.cgImage else { return image }
        let bounds = rect.integral.intersection(CGRect(x: 0, y: 0, width: cgImage.width, height: cgImage.height))
        guard !bounds.isEmpty, let cropped = cgImage.cropping(to: bounds) else { return image }
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    }

    private static func isHex64(_ string: String) -> Bool {
        guard string.utf8.count == 64 else { return false }
        return string.utf8.allSatisfy { byte in
            (byte >= 0x30 && byte <= 0x39) || // '0'-'9'
            (byte >= 0x61 && byte <= 0x66)    // 'a'-'f'
        }
    }

    private static func hexDigest(data: Data) -> String {
        let digest = SHA256.hash(data: data)
        let hexDigits: [UInt8] = Array("0123456789abcdef".utf8)
        return String(unsafeUninitializedCapacity: 64) { buffer in
            var offset = 0
            for byte in digest {
                buffer[offset] = hexDigits[Int(byte >> 4)]
                buffer[offset + 1] = hexDigits[Int(byte & 0x0F)]
                offset += 2
            }
            return 64
        }
    }

    static func validatedData(directory: URL, relativePath: String, sha256: String, byteSize: Int64) throws -> Data {
        guard sha256.utf8.count == 64, isHex64(sha256),
              relativePath == "\(sha256).img", byteSize > 0, byteSize <= 64 * 1024 * 1024 else {
            throw failure("图片记录无效")
        }
        let file = directory.appendingPathComponent(relativePath)
        let attributes = try file.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard attributes.isRegularFile == true, attributes.isSymbolicLink != true,
              attributes.fileSize.map(Int64.init) == byteSize else { throw failure("图片文件缺失或长度不符") }
        let data = try Data(contentsOf: file, options: .mappedIfSafe)
        guard Int64(data.count) == byteSize,
              hexDigest(data: data) == sha256 else {
            throw failure("图片文件校验失败")
        }
        return data
    }

    private static func failure(_ message: String) -> NSError {
        NSError(domain: "org.skepsun.kototoro.image", code: 1,
                userInfo: [NSLocalizedDescriptionKey: message])
    }
}
