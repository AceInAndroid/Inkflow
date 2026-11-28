import XCTest
import UIKit
@testable import InkflowNative

@MainActor
final class TextPageLayoutTests: XCTestCase {
    private let portrait = CGSize(width: 280, height: 340)
    private let font = UIFont.systemFont(ofSize: 20)

    private func assertCoverage(_ text: String, layout: TextPageLayout, file: StaticString = #filePath, line: UInt = #line) {
        let source = text as NSString
        var cursor = 0
        var reconstructed = ""
        for range in layout.ranges {
            XCTAssertEqual(range.location, cursor, file: file, line: line)
            if source.length > 0 { XCTAssertGreaterThan(range.length, 0, file: file, line: line) }
            XCTAssertEqual(TextPageLayout.clusterBoundary(atOrBefore: range.location, in: source), range.location, file: file, line: line)
            XCTAssertEqual(TextPageLayout.clusterBoundary(atOrBefore: NSMaxRange(range), in: source), NSMaxRange(range), file: file, line: line)
            reconstructed += source.substring(with: range)
            cursor = NSMaxRange(range)
        }
        XCTAssertEqual(cursor, source.length, file: file, line: line)
        XCTAssertEqual(reconstructed, text, file: file, line: line)
    }

    func testMixedUnicodeAndParagraphFixturesHaveLosslessClusterBoundaries() {
        let fixtures = [
            String(repeating: "中文段落，日本語、한국어。 English words wrap naturally.\n", count: 70),
            String(repeating: "👨‍👩‍👧‍👦 🇨🇳 👍🏽 e\u{301} क्‍ष 👩🏽‍💻\r\n\r\n", count: 100),
            String(repeating: "longUnbrokenWordWithoutSpaces", count: 400),
            "\n\n" + String(repeating: "Paragraph\r\n\r\n", count: 100) + "\n"
        ]
        for text in fixtures {
            let layout = TextPageLayout.paginate(text, size: portrait, font: font)
            XCTAssertGreaterThan(layout.ranges.count, 1)
            assertCoverage(text, layout: layout)
        }
    }

    func testEmptyAndUndersizedContainerStillTerminateWithoutSplittingEmoji() {
        assertCoverage("", layout: TextPageLayout.paginate("", size: portrait, font: font))
        let text = "👨‍👩‍👧‍👦e\u{301}\r\n🇨🇳"
        let layout = TextPageLayout.paginate(text, size: CGSize(width: 1, height: 1), font: font)
        assertCoverage(text, layout: layout)
        XCTAssertEqual(layout.ranges.count, text.count)
    }

    func testContainerAndFontChangesUseMeasuredLayout() {
        let text = String(repeating: "Words 中文 words — a measured line of text.\n", count: 100)
        let normal = TextPageLayout.paginate(text, size: portrait, font: font)
        let taller = TextPageLayout.paginate(text, size: CGSize(width: portrait.width, height: portrait.height * 2), font: font)
        let larger = TextPageLayout.paginate(text, size: portrait, font: .systemFont(ofSize: 32))
        XCTAssertLessThan(taller.ranges.count, normal.ranges.count)
        XCTAssertGreaterThan(larger.ranges.count, normal.ranges.count)
        for layout in [normal, taller, larger] { assertCoverage(text, layout: layout) }
    }

    func testRenderedPageLinesFitActualTextKit2Container() {
        let text = String(repeating: "中文 👩🏽‍💻 e\u{301} words and wrapping.\nSecond paragraph.\n\n", count: 40)
        let layout = TextPageLayout.paginate(text, size: portrait, font: font)
        for range in layout.ranges {
            // Independently render each measured substring through the same UIKit view used by the reader.
            let view = UITextView(usingTextLayoutManager: true)
            view.frame = CGRect(origin: .zero, size: portrait)
            view.textContainerInset = .zero
            view.textContainer.lineFragmentPadding = 0
            view.font = font
            view.text = (text as NSString).substring(with: range)
            view.layoutIfNeeded()
            let manager = view.textLayoutManager!
            let content = manager.textContentManager!
            manager.enumerateTextLayoutFragments(from: content.documentRange.location, options: [.ensuresLayout]) { fragment in
                for line in fragment.textLineFragments where line.characterRange.length > 0 {
                    XCTAssertLessThanOrEqual(fragment.layoutFragmentFrame.minY + line.typographicBounds.maxY, self.portrait.height + 0.01)
                }
                return true
            }
        }
    }

    func testAnchorLookupIncludesBoundaryAndEndOfBook() {
        let layout = TextPageLayout(ranges: [NSRange(location: 0, length: 7), NSRange(location: 7, length: 5)])
        XCTAssertEqual(layout.pageIndex(containing: 0), 0)
        XCTAssertEqual(layout.pageIndex(containing: 6), 0)
        XCTAssertEqual(layout.pageIndex(containing: 7), 1)
        XCTAssertEqual(layout.pageIndex(containing: 12), 1)
        XCTAssertEqual(layout.pageIndex(containing: Int.max), 1)
    }

    func testResizeFontAndNavigationPreserveResumeAnchorAndGlobalSelection() async {
        let text = String(repeating: "A paragraph 中文 👩🏽‍💻 with some words.\n", count: 100)
        let anchor = TextPageLayout.clusterBoundary(atOrBefore: 1873, in: text as NSString)
        var reportedRange = NSRange(location: 0, length: 0)
        var reportedAnchor = -1
        var selected = NSRange(location: 0, length: 0)
        var callback = expectation(description: "initial layout")
        var reader = TextKitReader(text: text, fontSize: 20, initialOffset: anchor, pageTurn: 0,
            onPage: { range, _, _, saved in reportedRange = range; reportedAnchor = saved; callback.fulfill() },
            onSelection: { selected = $0 })
        let coordinator = reader.makeCoordinator()
        let view = PagedTextView(usingTextLayoutManager: true)
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.frame = CGRect(origin: .zero, size: portrait)
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedAnchor, anchor)
        XCTAssertTrue(NSLocationInRange(anchor, reportedRange))
        callback = expectation(description: "rotation and font layout")
        reader = TextKitReader(text: text, fontSize: 30, initialOffset: anchor, pageTurn: 0,
            onPage: reader.onPage, onSelection: reader.onSelection)
        coordinator.parent = reader
        view.frame.size = CGSize(width: 500, height: 220)
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedAnchor, anchor, "Repagination must persist the original reading offset, not the new page start")
        XCTAssertTrue(NSLocationInRange(anchor, reportedRange))
        callback = expectation(description: "explicit next page")
        reader = TextKitReader(text: text, fontSize: 30, initialOffset: anchor, pageTurn: 1,
            onPage: reader.onPage, onSelection: reader.onSelection)
        coordinator.parent = reader
        let previousRange = reportedRange
        let priorEnd = NSMaxRange(reportedRange)
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedRange.location, priorEnd)
        XCTAssertEqual(reportedAnchor, priorEnd)
        XCTAssertEqual(selected.length, 0, "Navigation clears a stale selection")
        view.selectedRange = NSRange(location: 0, length: 1)
        coordinator.textViewDidChangeSelection(view)
        // Delegate callbacks publish asynchronously to avoid mutating SwiftUI state during layout.
        await Task.yield()
        let selectionPublished = expectation(description: "selection published")
        DispatchQueue.main.async { selectionPublished.fulfill() }
        await fulfillment(of: [selectionPublished], timeout: 2)
        XCTAssertEqual(selected, NSRange(location: reportedRange.location, length: 1))
        callback = expectation(description: "explicit previous page")
        reader = TextKitReader(text: text, fontSize: 30, initialOffset: anchor, pageTurn: 0,
            onPage: reader.onPage, onSelection: reader.onSelection)
        coordinator.parent = reader
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedRange, previousRange)
        XCTAssertEqual(reportedAnchor, previousRange.location)
        XCTAssertEqual(selected.length, 0)
    }

    func testExistingLibraryPersistsAnchorAcrossNewContainerAndReload() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let text = String(repeating: "书本 e\u{301} 👩🏽‍💻 words.\n", count: 100)
        let file = directory.appendingPathComponent("fixture.txt")
        try text.write(to: file, atomically: true, encoding: .utf8)
        let libraryURL = directory.appendingPathComponent("library")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(file)
        let anchor = TextPageLayout.clusterBoundary(atOrBefore: 1201, in: text as NSString)
        let layout = TextPageLayout.paginate(text, size: portrait, font: font)
        let range = layout.ranges[layout.pageIndex(containing: anchor)]
        store.updatePosition(id: book.id, progress: Double(NSMaxRange(range)) / Double(text.utf16.count), textOffset: anchor)
        store.flush()
        let restored = LibraryStore(directory: libraryURL).books.first!
        XCTAssertEqual(restored.textOffset, anchor)
        let resized = TextPageLayout.paginate(text, size: CGSize(width: 500, height: 220), font: .systemFont(ofSize: 30))
        XCTAssertTrue(NSLocationInRange(restored.textOffset, resized.ranges[resized.pageIndex(containing: restored.textOffset)]))
    }
    func testExplicitJumpCoalescesQueuedLayoutAndRetainsAnchorOnReflow() async {
        let source = String(repeating: "Pages 中文 👩‍💻 and words.\n", count: 300)
        var reportedIndex = -1
        var reportedAnchor = -1
        var callback = expectation(description: "only latest queued page callback")
        var reader = TextKitReader(text: source, fontSize: 20, initialOffset: 0, pageTurn: 0,
            onPage: { _, index, _, anchor in reportedIndex = index; reportedAnchor = anchor; callback.fulfill() },
            onSelection: { _ in })
        let coordinator = reader.makeCoordinator()
        let view = PagedTextView(usingTextLayoutManager: true)
        view.textContainerInset = .zero
        view.textContainer.lineFragmentPadding = 0
        view.frame = CGRect(origin: .zero, size: portrait)
        coordinator.update(view)
        reader.requestedPage = ReaderPageRequest(number: 3)
        coordinator.parent = reader
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedIndex, 2)
        let anchor = reportedAnchor
        callback = expectation(description: "reflow preserves explicit jump anchor")
        reader = TextKitReader(text: source, fontSize: 30, initialOffset: 0, pageTurn: 0,
            onPage: reader.onPage, onSelection: reader.onSelection, requestedPage: reader.requestedPage)
        coordinator.parent = reader
        view.frame.size = CGSize(width: 500, height: 220)
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedAnchor, anchor)
        callback = expectation(description: "explicit jump back to first page")
        reader.requestedPage = ReaderPageRequest(number: 1)
        coordinator.parent = reader
        coordinator.update(view)
        await fulfillment(of: [callback], timeout: 2)
        XCTAssertEqual(reportedIndex, 0)
        XCTAssertEqual(reportedAnchor, 0)
    }

}
