import SwiftUI
import UIKit
import PDFKit

struct TextKitReader: UIViewRepresentable {
    let text: String
    let fontSize: Double
    let initialOffset: Int
    /// An explicit page turn counter keeps resize callbacks separate from navigation intent.
    let pageTurn: Int
    var onPage: (NSRange, Int, Int, Int) -> Void
    var onSelection: (NSRange) -> Void
    var requestedPage: ReaderPageRequest? = nil

    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> PagedTextView {
        let view = PagedTextView(usingTextLayoutManager: true)
        view.isEditable = false
        view.isSelectable = true
        view.isScrollEnabled = false
        view.contentInsetAdjustmentBehavior = .never
        view.backgroundColor = .systemBackground
        view.textContainerInset = UIEdgeInsets(top: 24, left: 20, bottom: 36, right: 20)
        view.textContainer.lineFragmentPadding = 0
        view.delegate = context.coordinator
        view.onContainerChange = { [weak coordinator = context.coordinator, weak view] in
            if let view { coordinator?.update(view) }
        }
        view.registerForTraitChanges([UITraitPreferredContentSizeCategory.self]) { (view: PagedTextView, _: UITraitCollection) in
            view.onContainerChange?()
        }
        return view
    }
    func updateUIView(_ view: PagedTextView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.update(view)
    }
    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: TextKitReader
        private var source = ""
        private var size = CGSize.zero
        private var font: UIFont?
        private var layout = TextPageLayout(ranges: [])
        private var index = 0
        private var anchor: Int
        private var lastRequest: UUID?
        private var lastTurn: Int
        private var updating = false
        private var revision = 0
        init(_ parent: TextKitReader) {
            self.parent = parent; anchor = parent.initialOffset; lastTurn = parent.pageTurn
        }
        func update(_ view: PagedTextView) {
            guard !updating else { return }
            let inset = view.textContainerInset
            let available = CGSize(width: view.bounds.width - inset.left - inset.right,
                                   height: view.bounds.height - inset.top - inset.bottom)
            guard available.width > 0, available.height > 0 else { return }
            updating = true
            defer { updating = false }
            let nextFont = UIFontMetrics(forTextStyle: .body).scaledFont(
                for: .systemFont(ofSize: parent.fontSize), compatibleWith: view.traitCollection)
            let changed = source != parent.text || size != available || font != nextFont
            if changed {
                if source != parent.text { anchor = parent.initialOffset }
                source = parent.text; size = available; font = nextFont
                anchor = TextPageLayout.clusterBoundary(atOrBefore: anchor, in: source as NSString)
                layout = TextPageLayout.paginate(source, size: size, font: nextFont)
                index = layout.pageIndex(containing: anchor)
            }
            let turn = parent.pageTurn - lastTurn
            lastTurn = parent.pageTurn
            if turn != 0 {
                let target = min(max(0, index + turn), layout.ranges.count - 1)
                if target != index { index = target; anchor = layout.ranges[index].location }
            }
            let requestChanged = parent.requestedPage != nil && parent.requestedPage?.id != lastRequest
            if requestChanged, let request = parent.requestedPage, !layout.ranges.isEmpty {
                lastRequest = request.id
                index = ReadingLocation.pageIndex(request.index, count: layout.ranges.count)
                anchor = layout.ranges[index].location
            }
            guard changed || turn != 0 || requestChanged, !layout.ranges.isEmpty else { return }
            let range = layout.ranges[index]
            view.font = nextFont
            view.text = (source as NSString).substring(with: range)
            view.selectedRange = NSRange(location: 0, length: 0)
            view.setContentOffset(.zero, animated: false)
            revision += 1
            let currentRevision = revision
            let savedAnchor = anchor
            let pageIndex = index
            let count = layout.ranges.count
            // UIKit may lay out during a SwiftUI update. Publish only the latest completed layout.
            DispatchQueue.main.async {
                guard self.revision == currentRevision else { return }
                self.parent.onSelection(NSRange(location: range.location, length: 0))
                self.parent.onPage(range, pageIndex, count, savedAnchor)
            }
        }
        func textViewDidChangeSelection(_ textView: UITextView) {
            guard !updating, layout.ranges.indices.contains(index) else { return }
            let pageRange = layout.ranges[index]
            let selected = textView.selectedRange
            guard selected.location != NSNotFound, NSMaxRange(selected) <= pageRange.length else { return }
            let global = NSRange(location: pageRange.location + selected.location, length: selected.length)
            let currentRevision = revision
            DispatchQueue.main.async {
                guard self.revision == currentRevision else { return }
                self.parent.onSelection(global)
            }
        }
    }
}

final class PagedTextView: UITextView {
    var onContainerChange: (() -> Void)?
    override func layoutSubviews() { super.layoutSubviews(); onContainerChange?() }
}

struct PDFKitReader: UIViewRepresentable {
    let document: PDFDocument
    let initialPage: Int
    var requestedPage: ReaderPageRequest? = nil
    var onPage: (Int) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> PDFView {
        let view = PDFView()
        view.autoScales = true
        view.displayMode = .singlePage
        view.displayDirection = .horizontal
        view.usePageViewController(true)
        view.document = document
        context.coordinator.observe(view)
        if let page = document.page(at: ReadingLocation.pageIndex(initialPage, count: document.pageCount)) { view.go(to: page) }
        DispatchQueue.main.async { context.coordinator.report(view) }
        return view
    }
    func updateUIView(_ view: PDFView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.applyRequest(view)
    }
    final class Coordinator: NSObject {
        var parent: PDFKitReader
        private var observer: NSObjectProtocol?
        private var lastRequest: UUID?
        private var revision = 0
        init(_ parent: PDFKitReader) { self.parent = parent }
        func observe(_ view: PDFView) {
            observer = NotificationCenter.default.addObserver(forName: .PDFViewPageChanged, object: view, queue: .main) { [weak self, weak view] _ in
                guard let self, let view else { return }
                self.report(view)
            }
        }
        func applyRequest(_ view: PDFView) {
            guard let request = parent.requestedPage, request.id != lastRequest else { return }
            lastRequest = request.id
            if let page = parent.document.page(at: ReadingLocation.pageIndex(request.index, count: parent.document.pageCount)) {
                view.go(to: page)
                report(view)
            }
        }
        func report(_ view: PDFView) {
            guard let page = view.currentPage else { return }
            let index = parent.document.index(for: page)
            guard index != NSNotFound else { return }
            revision += 1
            let currentRevision = revision
            DispatchQueue.main.async { [weak self] in
                guard let self, self.revision == currentRevision else { return }
                self.parent.onPage(index)
            }
        }
        deinit { if let observer { NotificationCenter.default.removeObserver(observer) } }
    }
}
