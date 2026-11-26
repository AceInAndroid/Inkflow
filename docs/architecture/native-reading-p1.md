# Native reading P1 integration notes

Updated 2026-11-26. This slice extends the native AI contract and existing SQLite
v22 tables. It introduces no provider transport, schema, or reading-event model call.

## Existing note organization

`NativeNoteOrganizationService(context, store).create(bookId, noteIds,
userAuthorized)` freezes the explicitly selected workspace notes. Each source
contains its ID, `updated_at`, SHA-256 aggregate version, title, editable text,
tags, and attached reference text. The aggregate version also covers opaque
blocks, original sources, revisions and linked legacy annotations. Limits are
100 selected notes and 32,000 source characters per note.

The `native.notes.organize.v1` task payload fixes the selected IDs/versions and
source hash; `tb_reading_note_ai_batches.source_snapshot` holds the frozen inputs.
Changes to the batch cannot expand the original authorization on restart.
`NativeBookGenerationService.run` dispatches this type through the same mutex and
durable safe points as book generation. Restored tasks remain paused. Explicit
runs send one frozen note at a time through registered `reading.notes` and the
shared `NativeAiGateway`; no chapter text, conversation history or cached book
context is attached. Gateway provider/model/fallback provenance is retained.

Generation only writes pending `tb_reading_note_ai_suggestions`. The existing
chapter-extraction note suggestions remain a distinct source type.
`NativeReadingActions.reviewNoteOrganization(bookId, suggestionId, accept,
userAuthorized)` validates ownership and the frozen aggregate before acceptance.
It updates the existing note ID with `ReadingNoteWorkspaceStore.save`, preserving
status, favorite, original quote/source and unknown blocks and adding the normal
workspace revision. Reject only changes review metadata, including for stale notes.

Acceptance and its journal are atomic. Thirty-day undo guards the note aggregate,
review/batch metadata and linked annotation. Shared tag definitions are guarded
but never deleted/restored by note undo: only the note's tag memberships change.
Later edits refuse undo. The task checkpoint and generated suggestion commit in
one transaction so resume does not repeat completed calls.

## Atlas and local reading closure

`ReadingStructureParser` classifies publishing matter and recognizes explicit
work/volume/case boundaries from the TOC. Ordinary numbered chapters do not
become cases. Native generation excludes clear non-narrative units and persists
work/volume/arc/scene IDs on generated artifacts. Completed source chapters are
capped by the captured exact chapter cursor and rechecked before provider calls
and commits.

`StoryAtlasProjector` filters evidence/progress first, then projects story,
mystery/clue, character and relationship views, compact/standard/complete density,
explicit participant/work/arc scopes, chapter groups and collapse state. It uses
stored stage and mystery identifiers; it does not invent story chronology or
links. Book Hub exposes the four views, density, participant filtering and collapse.

`ReadingRelationshipGraph` draws only explicit `fiction.relationship`
from/to/relation payloads with visible evidence. Native extraction additionally
requires both endpoint names in the same exact evidence quotation. Book Hub uses
a static radial Canvas with pan/zoom, 12-node compact / 60-node complete display,
node recall/source inspection and per-edge evidence. All records remain in the
local material list even when the graph display is capped.

`ClosurePolicyRegistry` provides fiction.immersion, knowledge.argument and
psychology.reflection. `NextReadingActionResolver` is deterministic and local.
`NativeReadingOutcomeRepository` projects existing goals and source-verifiable
checkpoints, difficulties and cards from v22. User-confirmed goal/checkpoint/
difficulty completion uses `NativeReadingActions.completeOutcome` and the same
conflict-safe undo journal. No model is called by policy selection, recommendations,
opening the hub, searching, filtering, source navigation or ordinary reading.

## Integration hooks

- `BookHubContent(book: StoredBook, store: BookStore, snapshot:
  ReadingContextSnapshot, onOpenSource: ((BookMaterialSource) -> Unit)? = null)`
- `BookWikiRepository.contextPack(bookId: Long, boundary: Double, query: String =
  "", chapterHref: String = "", snapshot: ReadingContextSnapshot? = null)`
- `NativeReadingActions.saveAiNote(bookId: Long, title: String, content: String,
  quote: String?, snapshot: ReadingContextSnapshot, userAuthorized: Boolean): String`
  returns the action ID; `undo(actionId)` returns `NativeReadingUndoResult`.
- Native test classes: `com.aceinandroid.airead.NativeReadingDomainTest` and
  `com.aceinandroid.airead.NativeNoteOrganizationTest` (10 new integration tests).
- Core tests: `com.aceinandroid.airead.core.ai.NativeReadingDomainTest` (21 cases).

## Bounded limits

Source navigation and integrated Android builds/device execution are leader-owned.
The current agent runs targeted compiler/JUnit checks only, never concurrent Gradle.
Old evidence without a verifiable exact endpoint stays hidden on rewind. Old notes
must already have workspace IDs to participate; annotation-only migration remains
in the existing workspace. Large notes require editing before organization.

Closure support uses existing outcomes. It does not create automatic checkpoints,
new goals, coaching memories or graded card-review records. Closure selection and
timeline controls are local Hub state. Scope inference is conservative: absent or
ambiguous metadata stays unscoped. Existing explicit stage/track/mystery links are
projected; extraction does not manufacture missing structure. The graph is radial,
not a physics simulation, and is static without transitions for E-INK compatibility.
