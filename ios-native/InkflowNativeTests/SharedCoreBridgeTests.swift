import XCTest
@testable import InkflowNative

// Source-only and macOS Foundation checks intentionally have no substitute core.
#if canImport(InkflowCore)
import InkflowCore

final class SharedCoreBridgeTests: XCTestCase {
    private func prepare(authorized: Bool = true, skill: String = "reading.general",
                         workload: String = "reading.chat", source: String = "Read. SECRET",
                         offset: Int32 = 6, selectedEnd: Int32 = 5) -> InkflowPreparedRequest {
        InkflowCoreApi().prepareReadingRequest(
            requestId: UUID().uuidString, bookTitle: "Book", bookAuthor: "Author", chapterTitle: "Chapter",
            sourceText: source, chapterIndex: 1, chapterCount: 3, readOffset: offset,
            selectedStart: 0, selectedEnd: selectedEnd, prompt: "Explain", skillId: skill,
            workload: workload, preferChinese: false, userAuthorized: authorized)
    }

    func testLinkedBridgeProjectsTheActualRegistry() {
        let bridge = SharedCoreBridge()
        XCTAssertTrue(bridge.isLinked)
        let projected = bridge.skills()
        let actual = InkflowCoreApi().readingSkills()
        XCTAssertFalse(projected.isEmpty)
        XCTAssertEqual(projected.map(\.id), actual.map(\.id))
        XCTAssertEqual(projected.map(\.title), actual.map(\.title))
        XCTAssertEqual(projected.map(\.summary), actual.map(\.summary))
        XCTAssertEqual(Set(projected.map(\.id)).count, projected.count)
        XCTAssertTrue(projected.contains { $0.id == "reading.general" })
    }

    func testLocalPreparationUsesCapturedReadingBoundary() {
        let prepared = prepare()
        XCTAssertTrue(prepared.isValid, prepared.error)
        XCTAssertEqual(prepared.readingProgress, 0.5, accuracy: 0.00001)
        XCTAssertEqual(prepared.chapterIndex, 1)
        XCTAssertEqual(prepared.readOffset, 6)
        XCTAssertTrue(prepared.systemPrompt.contains("Read."))
        XCTAssertFalse(prepared.systemPrompt.contains("SECRET"))

        let context = CapturedReadingContext(title: "原書の題名", chapter: "Chapter",
            source: "Read. SECRET", chapterIndex: 1, chapterCount: 3,
            readOffset: 6, selection: NSRange(location: 0, length: 5))
        let display = SharedCoreBridge().prepare(context, prompt: "この文章を説明して", skillID: "reading.general")
        XCTAssertTrue(display.contains("Captured progress: 50%"))
        XCTAssertTrue(display.contains("この文章を説明して"))
        XCTAssertTrue(display.contains("Not sent."))
        XCTAssertFalse(display.contains("SECRET"))
    }

    func testFacadeRejectsImplicitRequestsAndUnknownIdentifiers() {
        let implicit = prepare(authorized: false)
        XCTAssertFalse(implicit.isValid)
        XCTAssertTrue(implicit.systemPrompt.isEmpty)
        XCTAssertFalse(prepare(skill: "future.skill").isValid)
        XCTAssertFalse(prepare(workload: "future.workload").isValid)
    }

    func testFacadeRejectsUnseenAndSplitUTF16Selections() {
        XCTAssertFalse(prepare(selectedEnd: 12).isValid)
        XCTAssertFalse(prepare(source: "A😀B", offset: 2, selectedEnd: 1).isValid)
        XCTAssertFalse(prepare(source: "A😀B", offset: 4, selectedEnd: 2).isValid)
        XCTAssertTrue(prepare(source: "A😀B", offset: 4, selectedEnd: 3).isValid)
    }
}
#endif
