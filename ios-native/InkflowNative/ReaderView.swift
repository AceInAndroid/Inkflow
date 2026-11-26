import SwiftUI
import PDFKit

struct ReaderView: View {
    let book: NativeBook
    @EnvironmentObject private var library: LibraryStore
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var speech = SpeechController()
    @State private var text = ""
    @State private var pdf: PDFDocument?
    @State private var loadError: String?
    @State private var fontSize = 20.0
    @State private var visibleStart = 0
    @State private var visibleEnd = 0
    @State private var selection = NSRange(location: 0, length: 0)
    @State private var textPage = 0
    @State private var textPageCount = 0
    @State private var pageTurn = 0
    @State private var page = 0
    @State private var captured: CapturedReadingContext?

    private var currentPageText: String { pdf?.page(at: page)?.string ?? "" }
    private var speakingText: String {
        if book.format == .pdf { return currentPageText }
        let source = text as NSString
        if selection.length > 0, NSMaxRange(selection) <= source.length { return source.substring(with: selection) }
        return source.substring(from: min(visibleStart, source.length))
    }
    var body: some View {
        Group {
            if let loadError { ContentUnavailableView("Could not open book", systemImage: "exclamationmark.triangle", description: Text(loadError)) }
            else if book.format == .txt, !text.isEmpty {
                TextKitReader(text: text, fontSize: fontSize, initialOffset: book.textOffset, pageTurn: pageTurn,
                    onPage: { range, index, count, anchor in
                        visibleStart = range.location; visibleEnd = NSMaxRange(range)
                        textPage = index; textPageCount = count
                        speech.stop()
                        library.updatePosition(id: book.id,
                            progress: Double(visibleEnd) / Double(max(1, text.utf16.count)), textOffset: anchor)
                    }, onSelection: { selection = $0 })
            } else if let pdf {
                PDFKitReader(document: pdf, initialPage: book.pdfPage) { index in
                    page = index
                    speech.stop()
                    library.updatePosition(id: book.id, progress: Double(index + 1) / Double(pdf.pageCount), pdfPage: index)
                }
            } else { ProgressView("Opening…") }
        }
        .navigationTitle(book.title).navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if book.format == .txt {
                    HStack {
                        Button("Previous page", systemImage: "chevron.left") { pageTurn -= 1 }
                            .disabled(textPage == 0 || textPageCount == 0)
                        Spacer()
                        Text("\(textPageCount == 0 ? 0 : textPage + 1) / \(textPageCount)")
                            .font(.caption.monospacedDigit()).accessibilityLabel("Text page")
                        Spacer()
                        Button("Next page", systemImage: "chevron.right") { pageTurn += 1 }
                            .disabled(textPageCount == 0 || textPage + 1 >= textPageCount)
                    }
                }
                HStack {
                    if book.format == .txt {
                        Button("Smaller type", systemImage: "textformat.size.smaller") { fontSize = max(14, fontSize - 2) }.labelStyle(.iconOnly)
                        Button("Larger type", systemImage: "textformat.size.larger") { fontSize = min(36, fontSize + 2) }.labelStyle(.iconOnly)
                    } else if let pdf { Text("\(page + 1) / \(pdf.pageCount)").font(.caption.monospacedDigit()) }
                    Spacer()
                    Button(speech.isSpeaking ? "Stop" : "Listen", systemImage: speech.isSpeaking ? "stop.fill" : "speaker.wave.2") {
                        speech.toggle(speakingText)
                    }.disabled(speakingText.isEmpty)
                    Button("Context", systemImage: "text.quote") {
                        captured = CapturedReadingContext(title: book.title,
                            chapter: book.format == .pdf ? "Page \(page + 1)" : book.title,
                            source: book.format == .pdf ? currentPageText : text,
                            chapterIndex: book.format == .pdf ? page : 0, chapterCount: pdf?.pageCount ?? 1,
                            readOffset: book.format == .pdf ? currentPageText.utf16.count : visibleEnd,
                            selection: book.format == .pdf ? NSRange(location: 0, length: 0) : selection)
                    }.disabled(book.format == .txt ? text.isEmpty : currentPageText.isEmpty)
                }
            }.buttonStyle(.bordered).padding(.horizontal).padding(.vertical, 8).background(.bar)
        }
        .sheet(isPresented: Binding(get: { captured != nil }, set: { if !$0 { captured = nil } })) {
            if let captured { ContextPreparationView(context: captured) }
        }
        .task {
            do {
                if book.format == .txt { text = try String(contentsOf: library.fileURL(for: book), encoding: .utf8) }
                else {
                    guard let document = PDFDocument(url: library.fileURL(for: book)), !document.isLocked, document.pageCount > 0 else { throw LibraryError.invalidPDF }
                    pdf = document; page = min(book.pdfPage, document.pageCount - 1)
                }
            } catch { loadError = error.localizedDescription }
        }
        .onDisappear { speech.stop(); library.flush() }
        .onChange(of: scenePhase) { _, phase in if phase != .active { speech.stop(); library.flush() } }
    }
}

private struct ContextPreparationView: View {
    let context: CapturedReadingContext
    private let bridge = SharedCoreBridge()
    @Environment(\.dismiss) private var dismiss
    @State private var skill = "reading.general"
    @State private var prompt = "Explain the passage I have read."
    @State private var result = ""
    var body: some View {
        NavigationStack {
            Form {
                Section("Captured reading position") {
                    Text(context.chapter)
                    Text("Visible UTF-16 offset: \(context.readOffset)").font(.caption)
                    Text("Opening this panel does not contact a model. Preparation stays on this device.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                if bridge.isLinked {
                    Section("Shared reading method") {
                        Picker("Skill", selection: $skill) { ForEach(bridge.skills()) { Text($0.title).tag($0.id) } }
                        TextField("Question", text: $prompt, axis: .vertical)
                        Button("Prepare request locally") { result = bridge.prepare(context, prompt: prompt, skillID: skill) }
                            .disabled(prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                } else {
                    Section("Source-only build") {
                        Text("TXT, PDF, and speech are available. Shared reading skills and request preparation require the InkflowNativeSharedCore scheme and InkflowCore.xcframework. See ios-native/README.md.")
                    }
                }
                if !result.isEmpty { Section("Prepared request · not sent") { Text(result).textSelection(.enabled).font(.callout) } }
            }.navigationTitle("Reading context")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }.presentationDetents([.medium, .large])
    }
}
