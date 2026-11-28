import XCTest
import PDFKit
import CoreGraphics
@testable import InkflowNative

@MainActor
final class LibraryRecoveryTests: XCTestCase {
    private var directory: URL!
    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: directory); super.tearDown() }
    private var libraryURL: URL { directory.appendingPathComponent("library") }
    private func source(_ name: String, _ text: String) throws -> URL {
        let url = directory.appendingPathComponent(name)
        try text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }
    private func rewriteIndex(_ change: (inout [[String: Any]]) -> Void) throws -> Data {
        let url = libraryURL.appendingPathComponent("library.json")
        var entries = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [[String: Any]])
        change(&entries)
        let data = try JSONSerialization.data(withJSONObject: entries)
        try data.write(to: url)
        return data
    }

    func testBatchImportContinuesAfterFailuresAndNeverInventsReadingHistory() throws {
        let first = try source("First 原文.txt", "Original 日本語 한국어")
        let empty = try source("Empty.txt", "")
        let second = try source("Second.txt", "Another book")
        let unsupported = try source("Unsupported.epub", "Not supported")
        let store = LibraryStore(directory: libraryURL)
        let result = store.importFiles([first, empty, second, unsupported, first])
        XCTAssertEqual(result.map(\.status), [.imported, .importFailed, .imported, .importFailed, .alreadyImported])
        XCTAssertEqual(result.first?.book?.id, result.last?.book?.id)
        XCTAssertEqual(result[1].fileName, "Empty.txt")
        XCTAssertEqual(result[1].failure?.message(.zhHans), InterfaceText.invalidText.text(.zhHans))
        XCTAssertEqual(result[3].failure?.message(.ko), InterfaceText.unsupported.text(.ko))
        try FileManager.default.removeItem(at: first)
        let restored = LibraryStore(directory: libraryURL)
        XCTAssertEqual(restored.books.count, 2)
        XCTAssertTrue(restored.home.recent.isEmpty)
        XCTAssertEqual(try restored.loadText(for: XCTUnwrap(result[0].book)), "Original 日本語 한국어")
    }

    func testNormalizedAndLegacyDuplicatesRetainIdentityPositionAndFont() throws {
        let first = try source("Original.txt", "Same 中文 👩‍💻")
        let utf16 = directory.appendingPathComponent("Renamed.txt")
        try "Same 中文 👩‍💻".data(using: .utf16)!.write(to: utf16)
        let bom = directory.appendingPathComponent("WithBOM.txt")
        try (Data([0xEF, 0xBB, 0xBF]) + Data("Same 中文 👩‍💻".utf8)).write(to: bom)
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(first)
        store.updatePosition(id: book.id, progress: 0.6, textOffset: 5)
        store.updateFontSize(id: book.id, value: 28)
        _ = try rewriteIndex { $0[0].removeValue(forKey: "contentDigest") }
        let restored = LibraryStore(directory: libraryURL)
        XCTAssertEqual(restored.importFiles([utf16, bom]).map(\.status), [.alreadyImported, .alreadyImported])
        let final = LibraryStore(directory: libraryURL)
        XCTAssertEqual(final.books.count, 1)
        XCTAssertEqual(final.books[0].id, book.id)
        XCTAssertEqual(final.books[0].title, "Original")
        XCTAssertEqual(final.books[0].textOffset, 5)
        XCTAssertEqual(final.books[0].progress, 0.6)
        XCTAssertEqual(final.books[0].readerFontSize, 28)
        XCTAssertNotNil(final.books[0].contentDigest)
    }

    func testExplicitReimportRepairsMissingCopyWithoutLosingPosition() throws {
        let url = try source("Repair.txt", "A saved book")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(url)
        store.updatePosition(id: book.id, progress: 0.7, textOffset: 4)
        store.updateFontSize(id: book.id, value: 30)
        try FileManager.default.removeItem(at: store.fileURL(for: book))
        XCTAssertThrowsError(try store.loadText(for: book)) { XCTAssertTrue($0 is LibraryError) }
        let reloaded = LibraryStore(directory: libraryURL)
        let result = reloaded.importFiles([url])
        XCTAssertEqual(result[0].status, .restoredFile)
        XCTAssertEqual(result[0].book?.id, book.id)
        XCTAssertEqual(try reloaded.loadText(for: book), "A saved book")
        XCTAssertEqual(reloaded.books[0].textOffset, 4)
        XCTAssertEqual(reloaded.books[0].progress, 0.7)
        XCTAssertEqual(reloaded.books[0].readerFontSize, 30)
    }

    func testDuplicateIDsAndUnsafePathsPreserveIndexAndRejectMutations() throws {
        let url = try source("Safe.txt", "Keep this book")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(url)
        let valid = try Data(contentsOf: libraryURL.appendingPathComponent("library.json"))
        for duplicateID in [true, false] {
            try valid.write(to: libraryURL.appendingPathComponent("library.json"))
            let original = try rewriteIndex { entries in
                if duplicateID { entries.append(entries[0]) }
                else { entries[0]["fileName"] = "../outside.txt" }
            }
            let broken = LibraryStore(directory: libraryURL)
            XCTAssertTrue(broken.books.isEmpty)
            XCTAssertEqual(broken.error?.message(.ja), InterfaceText.damagedIndex.text(.ja))
            XCTAssertEqual(broken.importFiles([url])[0].status, .importFailed)
            broken.flush()
            XCTAssertEqual(try Data(contentsOf: libraryURL.appendingPathComponent("library.json")), original)
            XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: book).path))
        }
    }

    func testFailedIndexWriteRollsBackCopiedFiles() throws {
        let url = try source("Rollback.txt", "Do not lose the source")
        let store = LibraryStore(directory: libraryURL)
        try FileManager.default.createDirectory(at: libraryURL.appendingPathComponent("library.json"), withIntermediateDirectories: true)
        XCTAssertThrowsError(try store.importFile(url))
        XCTAssertTrue(store.books.isEmpty)
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath: libraryURL.path), ["library.json"])
        XCTAssertTrue(FileManager.default.fileExists(atPath: url.path))
    }

    func testPersistedBoundsAndRemovalUseOwnedIdentity() throws {
        let url = try source("Bounds.txt", "Never leave the sandbox")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(url)
        _ = try rewriteIndex { entries in
            entries[0]["progress"] = 7.0; entries[0]["textOffset"] = -20
            entries[0]["pdfPage"] = -1; entries[0]["readerFontSize"] = 100.0
        }
        let restored = LibraryStore(directory: libraryURL)
        XCTAssertEqual(restored.books[0].progress, 1)
        XCTAssertEqual(restored.books[0].textOffset, 0)
        XCTAssertEqual(restored.books[0].pdfPage, 0)
        XCTAssertEqual(restored.books[0].readerFontSize, 36)
        restored.updatePosition(id: book.id, progress: .nan, textOffset: 10)
        restored.updateFontSize(id: book.id, value: .infinity)
        restored.flush()
        XCTAssertEqual(LibraryStore(directory: libraryURL).books[0].textOffset, 0)
        XCTAssertEqual(LibraryStore(directory: libraryURL).books[0].readerFontSize, 36)
        let forged = NativeBook(id: book.id, title: "Foreign", fileName: "../outside.txt", format: .pdf, importedAt: Date())
        restored.remove(forged)
        XCTAssertTrue(restored.books.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: restored.fileURL(for: book).path))
    }

    func testPDFImportLoadAndStoredPageUseRealDocument() throws {
        let url = directory.appendingPathComponent("Pages.pdf")
        var rect = CGRect(x: 0, y: 0, width: 120, height: 200)
        let context = try XCTUnwrap(CGContext(url as CFURL, mediaBox: &rect, nil))
        for _ in 0..<3 { context.beginPDFPage(nil); context.fill(rect); context.endPDFPage() }
        context.closePDF()
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(url)
        store.updatePosition(id: book.id, progress: 2.0 / 3, pdfPage: 1)
        store.flush()
        try FileManager.default.removeItem(at: url)
        let restored = LibraryStore(directory: libraryURL)
        let document = try restored.loadPDF(for: book)
        XCTAssertEqual(document.pageCount, 3)
        XCTAssertEqual(restored.books[0].pdfPage, 1)
        XCTAssertEqual(ReadingLocation.pageIndex(restored.books[0].pdfPage, count: document.pageCount), 1)
        XCTAssertThrowsError(try restored.loadText(for: book))
    }
}

final class ReadingLocationTests: XCTestCase {
    func testPageNumbersAcceptDecimalScriptsAndRejectInvalidOrOutOfRangeInput() {
        for input in ["12", " 12\n", "１２", "١٢", "۱۲"] { XCTAssertEqual(ReadingLocation.pageNumber(input, count: 20), 12) }
        for input in ["", "0", "21", "-1", "+1", "1.2", "1e1", "一", "⑫", String(repeating: "9", count: 100)] {
            XCTAssertNil(ReadingLocation.pageNumber(input, count: 20), input)
        }
        XCTAssertNil(ReadingLocation.pageNumber("1", count: 0))
        XCTAssertEqual(ReadingLocation.pageIndex(Int.min, count: 4), 0)
        XCTAssertEqual(ReadingLocation.pageIndex(Int.max, count: 4), 3)
        XCTAssertEqual(ReadingLocation.pageIndex(10, count: 0), 0)
        XCTAssertEqual(ReadingLocation.fontSize(nil), 20)
        XCTAssertEqual(ReadingLocation.fontSize(.nan), 20)
        XCTAssertEqual(ReadingLocation.fontSize(2), 14)
        XCTAssertEqual(ReadingLocation.fontSize(80), 36)
    }

    func testTXTResumeOffsetsClampWithoutSplittingComposedCharacters() {
        let source = "A👩‍💻e\u{301}\r\n🇨🇳Z" as NSString
        let expected = [0, 1, 6, 8, 10, 14, 15]
        for offset in 0...source.length {
            XCTAssertEqual(ReadingLocation.textOffset(offset, in: source), expected.last(where: { $0 <= offset }))
            if offset < source.length {
                XCTAssertEqual(ReadingLocation.nextTextOffset(after: offset, in: source), expected.first(where: { $0 > offset }))
            }
        }
        XCTAssertEqual(ReadingLocation.textOffset(Int.min, in: source), 0)
        XCTAssertEqual(ReadingLocation.textOffset(Int.max, in: source), 15)
        XCTAssertEqual(ReadingLocation.textOffset(100, in: ""), 0)
    }

    func testAppOwnedFailuresResolveInCurrentLanguage() {
        let failure = LibraryFailure(operation: .loadLibraryError, cause: LibraryError.damagedIndex)
        for language in InterfaceLanguage.allCases {
            XCTAssertEqual(failure.message(language), InterfaceText.damagedIndex.text(language))
        }
        XCTAssertNotEqual(failure.message(.en), failure.message(.ko))
    }
}
