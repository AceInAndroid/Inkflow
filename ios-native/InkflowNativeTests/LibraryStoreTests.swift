import XCTest
import PDFKit
@testable import InkflowNative

@MainActor
final class LibraryStoreTests: XCTestCase {
    private var directory: URL!
    override func setUp() {
        super.setUp()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try! FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }
    override func tearDown() { try? FileManager.default.removeItem(at: directory); super.tearDown() }

    func testTXTImportCopiesFileAndRestoresPosition() throws {
        let source = directory.appendingPathComponent("Book.txt")
        try "Hello 👩‍💻\nReading locally.".write(to: source, atomically: true, encoding: .utf8)
        let libraryURL = directory.appendingPathComponent("library")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(source)
        try FileManager.default.removeItem(at: source)
        XCTAssertTrue(FileManager.default.fileExists(atPath: store.fileURL(for: book).path))
        store.updatePosition(id: book.id, progress: 0.4, textOffset: 5)
        store.flush()
        let reloaded = LibraryStore(directory: libraryURL)
        XCTAssertEqual(reloaded.books.first?.textOffset, 5)
        XCTAssertEqual(reloaded.books.first?.progress, 0.4)
        reloaded.remove(book)
        XCTAssertTrue(reloaded.books.isEmpty)
        XCTAssertFalse(FileManager.default.fileExists(atPath: store.fileURL(for: book).path))
    }
    func testUTF16ImportAndUnsupportedFileDoNotCorruptLibrary() throws {
        let store = LibraryStore(directory: directory.appendingPathComponent("library"))
        let utf16 = directory.appendingPathComponent("日本語.txt")
        try "日本語の本".data(using: .utf16)!.write(to: utf16)
        let book = try store.importFile(utf16)
        XCTAssertEqual(try String(contentsOf: store.fileURL(for: book), encoding: .utf8), "日本語の本")
        let unsupported = directory.appendingPathComponent("book.epub")
        try Data([0, 1]).write(to: unsupported)
        XCTAssertThrowsError(try store.importFile(unsupported))
        let invalid = directory.appendingPathComponent("broken.pdf")
        try Data("Not a PDF".utf8).write(to: invalid)
        XCTAssertThrowsError(try store.importFile(invalid))
        XCTAssertEqual(store.books.count, 1)
    }
    func testDamagedIndexIsPreserved() throws {
        let libraryURL = directory.appendingPathComponent("library")
        try FileManager.default.createDirectory(at: libraryURL, withIntermediateDirectories: true)
        let index = libraryURL.appendingPathComponent("library.json")
        let original = Data("damaged index".utf8)
        try original.write(to: index)
        let store = LibraryStore(directory: libraryURL)
        XCTAssertNotNil(store.error)
        store.flush()
        XCTAssertEqual(try Data(contentsOf: index), original)
    }
    func testPDFImportPersistsPage() throws {
        let document = PDFDocument()
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 200))
        let image = renderer.image { $0.cgContext.setFillColor(UIColor.white.cgColor); $0.fill(CGRect(x: 0, y: 0, width: 100, height: 200)) }
        document.insert(PDFPage(image: image)!, at: 0)
        document.insert(PDFPage(image: image)!, at: 1)
        let source = directory.appendingPathComponent("Pages.pdf")
        XCTAssertTrue(document.write(to: source))
        let libraryURL = directory.appendingPathComponent("library")
        let store = LibraryStore(directory: libraryURL)
        let book = try store.importFile(source)
        store.updatePosition(id: book.id, progress: 1, pdfPage: 1)
        store.flush()
        XCTAssertEqual(LibraryStore(directory: libraryURL).books.first?.pdfPage, 1)
    }
}
