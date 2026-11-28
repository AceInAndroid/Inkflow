import XCTest
@testable import InkflowNative

/// Runs against the real library index on iOS and on the macOS host; no document/UI stubs.
@MainActor
final class HomeLibraryTests: XCTestCase {
    private var directory: URL!
    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: directory); super.tearDown() }

    func testHomeUsesPersistedRecencyAndRemovesDeletedBooks() throws {
        let source = directory.appendingPathComponent("Original 原文.txt")
        try "Untranslated contents 日本語 한국어".write(to: source, atomically: true, encoding: .utf8)
        let libraryURL = directory.appendingPathComponent("library")
        let store = LibraryStore(directory: libraryURL)
        let first = try store.importFile(source)
        let other = directory.appendingPathComponent("Second.txt")
        try "A different book".write(to: other, atomically: true, encoding: .utf8)
        let second = try store.importFile(other)
        XCTAssertTrue(store.home.recent.isEmpty)
        XCTAssertEqual(store.home.featured?.id, second.id)
        XCTAssertEqual(store.home.openedCount, 0)
        store.markOpened(id: second.id, at: Date(timeIntervalSince1970: 100))
        store.updatePosition(id: first.id, progress: 1, textOffset: 8)
        store.markOpened(id: first.id, at: Date(timeIntervalSince1970: 200))
        let restored = LibraryStore(directory: libraryURL)
        XCTAssertEqual(restored.home.recent.map(\.id), [first.id, second.id])
        XCTAssertEqual(restored.home.featured?.title, "Original 原文")
        XCTAssertEqual(restored.home.openedCount, 2)
        XCTAssertEqual(restored.home.completedCount, 1)
        XCTAssertEqual(try String(contentsOf: restored.fileURL(for: first), encoding: .utf8), "Untranslated contents 日本語 한국어")
        restored.remove(first)
        XCTAssertEqual(restored.home.featured?.id, second.id)
        restored.remove(second)
        XCTAssertNil(restored.home.featured)
    }

    func testOldIndexWithoutRecencyRetainsAnchorsAndUsesOnlyExistingProgress() throws {
        let libraryURL = directory.appendingPathComponent("library")
        let store = LibraryStore(directory: libraryURL)
        let source = directory.appendingPathComponent("Legacy.txt")
        try "Local text".write(to: source, atomically: true, encoding: .utf8)
        let book = try store.importFile(source)
        store.updatePosition(id: book.id, progress: 0.4, textOffset: 3)
        store.flush()
        let index = libraryURL.appendingPathComponent("library.json")
        var entries = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: index)) as? [[String: Any]])
        entries[0].removeValue(forKey: "lastReadAt")
        try JSONSerialization.data(withJSONObject: entries).write(to: index)
        let restored = LibraryStore(directory: libraryURL)
        XCTAssertNil(restored.books.first?.lastReadAt)
        XCTAssertEqual(restored.books.first?.textOffset, 3)
        XCTAssertEqual(restored.books.first?.progress, 0.4)
        XCTAssertEqual(restored.home.recent.first?.id, book.id)
        restored.markOpened(id: UUID())
        XCTAssertEqual(restored.books.count, 1)
    }

}
