import Foundation
#if INKFLOW_SHARED_CORE
import InkflowCore
#endif

struct NativeSkill: Identifiable {
    let id: String
    let title: String
    let summary: String
}

struct CapturedReadingContext {
    let title: String
    let chapter: String
    let source: String
    let chapterIndex: Int
    let chapterCount: Int
    let readOffset: Int
    let selection: NSRange
}

/// Source-only builds intentionally have no substitute skill list or prompt assembler.
struct SharedCoreBridge {
    var isLinked: Bool {
        #if INKFLOW_SHARED_CORE
        true
        #else
        false
        #endif
    }
    func skills() -> [NativeSkill] {
        #if INKFLOW_SHARED_CORE
        InkflowCoreApi().readingSkills().map { NativeSkill(id: $0.id, title: $0.title, summary: $0.summary) }
        #else
        []
        #endif
    }
    func prepare(_ context: CapturedReadingContext, prompt: String, skillID: String) -> String {
        #if INKFLOW_SHARED_CORE
        let result = InkflowCoreApi().prepareReadingRequest(
            requestId: UUID().uuidString, bookTitle: context.title, bookAuthor: "", chapterTitle: context.chapter,
            sourceText: context.source, chapterIndex: Int32(context.chapterIndex), chapterCount: Int32(context.chapterCount),
            readOffset: Int32(context.readOffset), selectedStart: Int32(context.selection.location),
            selectedEnd: Int32(context.selection.location + context.selection.length), prompt: prompt,
            skillId: skillID, workload: "reading.chat", preferChinese: false, userAuthorized: true)
        guard result.isValid else { return result.error }
        return "Shared core prepared \(result.workload)\nSkill: \(result.skillId)\nCaptured progress: \(Int(result.readingProgress * 100))%\n\n\(result.systemPrompt)\n\nUser request:\n\(result.prompt)\n\nNot sent. This starter has no configured provider transport. Future sends must use InkflowCoreApi.execute → AiRequestGateway."
        #else
        return "Shared core is not linked in this source-only build. See ios-native/README.md for the SharedCore scheme."
        #endif
    }
}
