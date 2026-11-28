import SwiftUI
import PDFKit

struct ReaderView: View {
    let book: NativeBook
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.interfaceLanguage) private var language
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var speech = SpeechController()
    @State private var text = ""
    @State private var pdf: PDFDocument?
    @State private var loadError: LibraryFailure?
    @State private var fontSize: Double
    @State private var initialTextOffset = 0
    @State private var requestedPage: ReaderPageRequest?
    @State private var choosingPage = false
    @State private var pageInput = ""
    @State private var visibleStart = 0
    @State private var visibleEnd = 0
    @State private var selection = NSRange(location: 0, length: 0)
    @State private var textPage = 0
    @State private var textPageCount = 0
    @State private var pageTurn = 0
    @State private var page = 0
    @State private var captured: CapturedReadingContext?

    init(book: NativeBook) {
        self.book = book
        _fontSize = State(initialValue: ReadingLocation.fontSize(book.readerFontSize))
    }

    private var pageCount: Int { book.format == .txt ? textPageCount : pdf?.pageCount ?? 0 }
    private var currentPageIndex: Int { book.format == .txt ? textPage : page }
    private var currentPageText: String { pdf?.page(at: page)?.string ?? "" }
    private var speakingText: String {
        if book.format == .pdf { return currentPageText }
        let source = text as NSString
        if selection.length > 0, NSMaxRange(selection) <= source.length { return source.substring(with: selection) }
        return source.substring(from: min(visibleStart, source.length))
    }
    var body: some View {
        Group {
            if let loadError {
                ContentUnavailableView {
                    Label(InterfaceText.openError.text(language), systemImage: "exclamationmark.triangle")
                } description: { Text(loadError.message(language)) }
                actions: { Button(InterfaceText.retry.text(language)) { loadBook() }.buttonStyle(.bordered) }
            }
            else if book.format == .txt, !text.isEmpty {
                TextKitReader(text: text, fontSize: fontSize, initialOffset: initialTextOffset, pageTurn: pageTurn,
                    onPage: { range, index, count, anchor in
                        visibleStart = range.location; visibleEnd = NSMaxRange(range)
                        textPage = index; textPageCount = count
                        speech.stop()
                        library.updatePosition(id: book.id,
                            progress: Double(visibleEnd) / Double(max(1, text.utf16.count)), textOffset: anchor)
                    }, onSelection: { selection = $0 }, requestedPage: requestedPage)
            } else if let pdf {
                PDFKitReader(document: pdf, initialPage: page, requestedPage: requestedPage) { index in
                    page = index
                    speech.stop()
                    library.updatePosition(id: book.id, progress: Double(index + 1) / Double(pdf.pageCount), pdfPage: index)
                }
            } else { ProgressView(InterfaceText.opening.text(language)) }
        }
        .navigationTitle(book.title).navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if book.format == .txt {
                    HStack {
                        Button(InterfaceText.previous.text(language), systemImage: "chevron.left") { pageTurn -= 1 }
                            .disabled(textPage == 0 || textPageCount == 0)
                        Spacer()
                        Text("\(textPageCount == 0 ? 0 : textPage + 1) / \(textPageCount)")
                            .font(.caption.monospacedDigit()).accessibilityLabel(InterfaceText.textPage.text(language))
                        Spacer()
                        Button(InterfaceText.next.text(language), systemImage: "chevron.right") { pageTurn += 1 }
                            .disabled(textPageCount == 0 || textPage + 1 >= textPageCount)
                    }
                }
                HStack {
                    if book.format == .txt {
                        Button(InterfaceText.smallerType.text(language), systemImage: "textformat.size.smaller") { fontSize = max(14, fontSize - 2) }.labelStyle(.iconOnly)
                        Button(InterfaceText.largerType.text(language), systemImage: "textformat.size.larger") { fontSize = min(36, fontSize + 2) }.labelStyle(.iconOnly)
                    } else if let pdf { Text("\(page + 1) / \(pdf.pageCount)").font(.caption.monospacedDigit()) }
                    Spacer()
                    Button((speech.isSpeaking ? InterfaceText.stop : .listen).text(language), systemImage: speech.isSpeaking ? "stop.fill" : "speaker.wave.2") {
                        speech.toggle(speakingText)
                    }.disabled(speakingText.isEmpty)
                    Button(InterfaceText.context.text(language), systemImage: "text.quote") {
                        captured = CapturedReadingContext(title: book.title,
                            chapter: book.format == .pdf ? "\(InterfaceText.page.text(language)) \(page + 1)" : book.title,
                            source: book.format == .pdf ? currentPageText : text,
                            chapterIndex: book.format == .pdf ? page : 0, chapterCount: pdf?.pageCount ?? 1,
                            readOffset: book.format == .pdf ? currentPageText.utf16.count : visibleEnd,
                            selection: book.format == .pdf ? NSRange(location: 0, length: 0) : selection)
                    }.disabled(book.format == .txt ? text.isEmpty : currentPageText.isEmpty)
                }
            }.buttonStyle(.bordered).padding(.horizontal).padding(.vertical, 8).background(.bar)
        }
        .sheet(isPresented: Binding(get: { captured != nil }, set: { if !$0 { captured = nil } })) {
            if let captured { ContextPreparationView(context: captured, initialPrompt: InterfaceText.defaultQuestion.text(language)) }
        }
        .toolbar { ToolbarItem(placement: .primaryAction) {
            Button(InterfaceText.jumpToPage.text(language), systemImage: "number.square") {
                pageInput = String(currentPageIndex + 1)
                choosingPage = true
            }.disabled(pageCount == 0)
        } }
        .alert(InterfaceText.jumpToPage.text(language), isPresented: $choosingPage) {
            TextField(InterfaceText.pageNumber.text(language), text: $pageInput).keyboardType(.numberPad)
            Button(InterfaceText.jumpToPage.text(language)) {
                if let number = ReadingLocation.pageNumber(pageInput, count: pageCount) {
                    speech.stop(); selection = NSRange(location: 0, length: 0)
                    requestedPage = ReaderPageRequest(number: number)
                }
            }.disabled(ReadingLocation.pageNumber(pageInput, count: pageCount) == nil)
            Button(InterfaceText.cancel.text(language), role: .cancel) { }
        } message: { Text("1 – \(pageCount)") }
        .task { loadBook() }
        .onChange(of: fontSize) { _, value in library.updateFontSize(id: book.id, value: value) }
        .onDisappear { speech.stop(); library.flush() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { speech.stop(); library.flush() } }
    }

    private func loadBook() {
        speech.stop()
        loadError = nil
        do {
            let saved = library.books.first(where: { $0.id == book.id }) ?? book
            if book.format == .txt {
                let loaded = try library.loadText(for: book)
                initialTextOffset = ReadingLocation.textOffset(saved.textOffset, in: loaded as NSString)
                text = loaded
            } else {
                let document = try library.loadPDF(for: book)
                page = ReadingLocation.pageIndex(saved.pdfPage, count: document.pageCount)
                pdf = document
            }
            library.markOpened(id: book.id)
        } catch { loadError = LibraryFailure(operation: .openError, cause: error) }
    }

}

private struct ContextPreparationView: View {
    let context: CapturedReadingContext
    private let bridge = SharedCoreBridge()
    @Environment(\.interfaceLanguage) private var language
    @Environment(\.dismiss) private var dismiss
    @State private var skill = "reading.general"
    @State private var prompt: String
    @State private var result = ""
    init(context: CapturedReadingContext, initialPrompt: String) {
        self.context = context
        _prompt = State(initialValue: initialPrompt)
    }
    var body: some View {
        NavigationStack {
            Form {
                Section(InterfaceText.capturedPosition.text(language)) {
                    Text(context.chapter)
                    Text("\(InterfaceText.visibleOffset.text(language)): \(context.readOffset)").font(.caption)
                    Text(InterfaceText.localPreparation.text(language))
                        .font(.caption).foregroundStyle(.secondary)
                }
                if bridge.isLinked {
                    Section(InterfaceText.readingMethod.text(language)) {
                        Picker(InterfaceText.skill.text(language), selection: $skill) { ForEach(bridge.skills()) { Text($0.title).tag($0.id) } }
                        TextField(InterfaceText.question.text(language), text: $prompt, axis: .vertical)
                        Button(InterfaceText.prepare.text(language)) { result = bridge.prepare(context, prompt: prompt, skillID: skill) }
                            .disabled(prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                } else {
                    Section(InterfaceText.sourceOnly.text(language)) {
                        Text(InterfaceText.bridgeUnavailable.text(language))
                    }
                }
                if !result.isEmpty { Section(InterfaceText.prepared.text(language)) { Text(result).textSelection(.enabled).font(.callout) } }
            }.navigationTitle(InterfaceText.readingContext.text(language))
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button(InterfaceText.done.text(language)) { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}
