import PDFKit
import UIKit

/// Android imports PDFs as comics; iOS renders each page with PDFKit into a comic archive and imports that, so the
/// library, reader and re-import identity (the archive's hash, which the same PDF reproduces) stay the CBZ path's.
enum PdfImport {
    /// Longest page side in pixels: sharp on a phone or tablet without huge archives.
    private static let maxSide: CGFloat = 2400

    /// A CBZ of [pdf]'s pages in a temporary folder, named after the PDF.
    static func comicArchive(from pdf: URL) throws -> URL {
        guard let document = PDFDocument(url: pdf), document.pageCount > 0 else {
            throw NSError(domain: "org.skepsun.kototoro.local", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: String(localized: "无法读取此 PDF（可能已加密或损坏）")])
        }
        var entries: [(String, Data)] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let bounds = page.bounds(for: .mediaBox)
            let scale = min(maxSide / max(bounds.width, bounds.height), 4)
            let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
            let format = UIGraphicsImageRendererFormat()
            format.scale = 1
            format.opaque = true
            let image = UIGraphicsImageRenderer(size: size, format: format).image { context in
                UIColor.white.setFill()
                context.fill(CGRect(origin: .zero, size: size))
                context.cgContext.translateBy(x: 0, y: size.height)
                context.cgContext.scaleBy(x: scale, y: -scale)
                page.draw(with: .mediaBox, to: context.cgContext)
            }
            guard let data = image.jpegData(compressionQuality: 0.9) else { continue }
            entries.append((String(format: "%04d.jpg", index + 1), data))
        }
        let directory = FileManager.default.temporaryDirectory.appending(path: "pdf-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let archive = directory.appending(path: pdf.deletingPathExtension().lastPathComponent + ".cbz")
        try StoredZip.write(entries, to: archive)
        return archive
    }
}

/// A minimal ZIP writer with stored (uncompressed) entries: JPEG pages gain nothing from deflate.
enum StoredZip {
    static func write(_ entries: [(String, Data)], to url: URL) throws {
        var output = Data()
        var central = Data()
        for (name, data) in entries {
            let nameData = Data(name.utf8)
            let crc = crc32(data)
            let offset = UInt32(output.count)
            output.append(le32(0x04034b50)); output.append(le16(20)); output.append(le16(0x0800)); output.append(le16(0))
            output.append(le16(0)); output.append(le16(0x21)) // time/date: fixed, so the same input gives the same bytes
            output.append(le32(crc)); output.append(le32(UInt32(data.count))); output.append(le32(UInt32(data.count)))
            output.append(le16(UInt16(nameData.count))); output.append(le16(0))
            output.append(nameData); output.append(data)
            central.append(le32(0x02014b50)); central.append(le16(20)); central.append(le16(20)); central.append(le16(0x0800))
            central.append(le16(0)); central.append(le16(0)); central.append(le16(0x21))
            central.append(le32(crc)); central.append(le32(UInt32(data.count))); central.append(le32(UInt32(data.count)))
            central.append(le16(UInt16(nameData.count))); central.append(le16(0)); central.append(le16(0))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0)); central.append(le32(offset))
            central.append(nameData)
        }
        let centralOffset = UInt32(output.count)
        output.append(central)
        output.append(le32(0x06054b50)); output.append(le16(0)); output.append(le16(0))
        output.append(le16(UInt16(entries.count))); output.append(le16(UInt16(entries.count)))
        output.append(le32(UInt32(central.count))); output.append(le32(centralOffset)); output.append(le16(0))
        try output.write(to: url, options: .atomic)
    }

    private static let table: [UInt32] = (0..<256).map { value in
        var c = UInt32(value)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        for byte in data { crc = table[Int((crc ^ UInt32(byte)) & 0xFF)] ^ (crc >> 8) }
        return crc ^ 0xFFFFFFFF
    }

    private static func le16(_ value: UInt16) -> Data { withUnsafeBytes(of: value.littleEndian) { Data($0) } }
    private static func le32(_ value: UInt32) -> Data { withUnsafeBytes(of: value.littleEndian) { Data($0) } }
}
