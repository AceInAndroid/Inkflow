# Selection Interaction

## Goal

Long-press selection is a reading command surface. It should expose the most
likely action in one tap, preserve the selected context until an action accepts
it, and never silently fail after the menu closes.

## Information Architecture

Primary actions remain in stable semantic positions:

| Selection | Primary actions |
| --- | --- |
| Dictionary candidate | Look up, vocabulary, AI, more |
| Phrase or passage | Translate, AI, copy, more |

The expanded group contains web search, paragraph translation, narration,
note, and sharing. Annotation type and color remain a separate visual group
because they modify the selection rather than consume it.

## AI Contract

- Opening AI captures book, chapter, CFI, selected text, and surrounding text
  before closing the selection overlay.
- Opening AI creates a pending action card and does not request the model.
- The pending selection is cleared only after a selected AI action starts.
- Basic selection actions go directly to the primary assistant. Multi-Agent is
  reserved for explicit research, verification, calculation, risk, or deep
  analysis tasks.
- Model output is displayed as it streams. An idle stream times out instead of
  leaving the workspace permanently loading.

## Failure And Layout States

- Missing reader or workspace state keeps the menu open and shows an error.
- Async actions capture their reader state before closing the menu.
- Expanding or collapsing actions triggers overlay measurement and placement.
- Footnotes do not expose note editing; disabled AI and vocabulary capabilities
  are removed rather than shown as dead controls.
- E-INK uses the existing reduced-motion and high-contrast menu treatment.

## Inkflow native target (2026-11-27)

The approved [master roadmap](../../todo/inkflow-master-roadmap.md),
[reader interaction specification](../../todo/inkflow-reader-interaction-spec.md),
[UI specification](../../todo/inkflow-ui-structure-and-specs.md), and
[AI/Skill specification](../../todo/inkflow-ai-interaction-and-skills-spec.md)
extend these semantics for Compose/KMP and future SwiftUI. The existing behavior
above is a compatibility reference, not evidence that native target UI has passed.

- Explicit previous/next-page extension, retract and show-selection-end commands
  are the default. Edge-dwell extension is optional, off by default, and disabled
  on E-INK. Circular 1:1 live magnification is ordinary-screen-only.
- The dark primary capsule stays open and retains the range when highlighting;
  the secondary capsule contains three styles and five colors. Recoloring and
  annotation changes reuse the authorized action service and conflict-safe undo.
- Three candidate groups preserve the six-level priority order: selection
  controls; TTS/link/media/annotation targets; then selection/page navigation.
  One pointer owner claims the gesture and cancels the other candidates.
- Native source navigation uses validated Evidence and QueryBack, retaining the
  original question position across multiple source inspections. Phone sheets
  collapse for sources; wide-screen AI stays beside the reader.
- Only an explicit send enters the registered native gateway. Selection changes,
  opening AI, changing layout or language, and normal reading never call a model.
- v0.3/v0.4 target acceptance remains pending; document approval does not count as
  screenshots, live magnifier/TTS evidence or a multi-engine device pass.
