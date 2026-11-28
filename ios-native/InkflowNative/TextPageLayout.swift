import UIKit

/// Page ranges and resume anchors use the same UTF-16 coordinates as the library and KMP bridge.
struct TextPageLayout {
    let ranges: [NSRange]

    func pageIndex(containing offset: Int) -> Int {
        guard !ranges.isEmpty else { return 0 }
        var low = 0
        var high = ranges.count
        while low < high {
            let middle = (low + high) / 2
            if NSMaxRange(ranges[middle]) <= offset { low = middle + 1 } else { high = middle }
        }
        return min(low, ranges.count - 1)
    }

    static func clusterBoundary(atOrBefore offset: Int, in source: NSString) -> Int {
        ReadingLocation.textOffset(offset, in: source)
    }

    /// Measures each page with TextKit 2 using the rendering view's font and usable container size.
    /// Bounded lookahead avoids laying out the entire remaining book for every page.
    static func paginate(_ text: String, size: CGSize, font: UIFont) -> TextPageLayout {
        let source = text as NSString
        guard source.length > 0 else { return TextPageLayout(ranges: [NSRange(location: 0, length: 0)]) }
        var ranges: [NSRange] = []
        var start = 0
        while start < source.length {
            var lookahead = min(2048, source.length - start)
            var length = 0
            while true {
                let proposedEnd = start + lookahead
                let end = proposedEnd < source.length
                    ? ReadingLocation.nextTextOffset(after: proposedEnd - 1, in: source) : source.length
                let candidate = source.substring(with: NSRange(location: start, length: end - start))
                let measurement = fittingLength(candidate, size: size, font: font)
                length = measurement.length
                if measurement.overflow || end == source.length { break }
                lookahead = min(source.length - start, max(end - start + 1, lookahead * 2))
            }
            var end = clusterBoundary(atOrBefore: start + length, in: source)
            // Even an undersized container must advance by a whole cluster, never by a UTF-16 unit.
            if end <= start { end = ReadingLocation.nextTextOffset(after: start, in: source) }
            ranges.append(NSRange(location: start, length: end - start))
            start = end
        }
        return TextPageLayout(ranges: ranges)
    }

    private static func fittingLength(_ text: String, size: CGSize, font: UIFont) -> (length: Int, overflow: Bool) {
        let storage = NSTextContentStorage()
        let manager = NSTextLayoutManager()
        let container = NSTextContainer(size: CGSize(width: max(1, size.width), height: .greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        manager.textContainer = container
        storage.addTextLayoutManager(manager)
        storage.textStorage?.setAttributedString(NSAttributedString(string: text, attributes: [.font: font]))
        var length = 0
        var overflow = false
        manager.enumerateTextLayoutFragments(from: storage.documentRange.location, options: [.ensuresLayout]) { fragment in
            let base = storage.offset(from: storage.documentRange.location, to: fragment.rangeInElement.location)
            for line in fragment.textLineFragments where line.characterRange.length > 0 {
                let bottom = fragment.layoutFragmentFrame.minY + line.typographicBounds.maxY
                if bottom > size.height + 0.01 {
                    overflow = true
                    return false
                }
                length = max(length, base + NSMaxRange(line.characterRange))
            }
            return true
        }
        return (min(length, text.utf16.count), overflow)
    }
}
