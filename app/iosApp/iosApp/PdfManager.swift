import Foundation
import PDFKit
import UIKit
import SharedUI

/// PDFKit-backed renderer behind `PdfPageRenderer`: the document screen shows
/// each page as an image, decoded back in Kotlin like any other PNG.
final class PdfManager: NSObject, PdfPageRenderer {
    func renderPages(pdf: KotlinByteArray) -> [KotlinByteArray] {
        guard let document = PDFDocument(data: Data(from: pdf)) else { return [] }
        var pages: [KotlinByteArray] = []
        for index in 0..<document.pageCount {
            guard let page = document.page(at: index) else { continue }
            let box = page.bounds(for: .mediaBox)
            // Page units are points; 3x lands near print density, so a
            // statement's small print stays legible.
            let scale: CGFloat = 3
            let size = CGSize(width: box.width * scale, height: box.height * scale)
            let image = UIGraphicsImageRenderer(size: size).image { context in
                UIColor.white.setFill()
                context.fill(CGRect(origin: .zero, size: size))
                context.cgContext.translateBy(x: 0, y: size.height)
                context.cgContext.scaleBy(x: scale, y: -scale)
                page.draw(with: .mediaBox, to: context.cgContext)
            }
            if let png = image.pngData() {
                pages.append(KotlinByteArray.from(png))
            }
        }
        return pages
    }
}
