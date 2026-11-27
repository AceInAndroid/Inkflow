# Inkflow native AI migration contract

Updated 2026-11-27. The Flutter gateway cannot be called from the standalone
KMP/Android application. Native P0 first introduces the same reusable boundaries;
features must extend these rather than instantiate another provider pipeline.

- `core.ai.AiRequest` identifies a registered workload, provider role, immutable
  reading snapshot, skill, history and explicit user authorization.
- `AiContextAssembler` alone constructs provider context. It filters local book
  context at the captured progress boundary and applies a character budget.
- `AiRequestGateway` owns routing, bounded key retries within the selected provider, and usage. Its
  transport is the existing Android `NativeAiClient`; pages never call it directly.
- Reading events, selection changes, opening panels and restoring sessions have
  no model side effects. Only explicit sends and confirmed tasks enter the gateway.
- Persistent sessions use existing v22 `tb_ai_sessions`; provider credentials stay
  in private device preferences and are excluded from portable backups.
- Long-running book generation must extend one task scheduler with safe points,
  durable paused restoration and validated Evidence. AI mutations must go through
  an authorized action service with conflict-safe, 30-day undo, never page SQL.
- Unknown workload/skill/artifact/task identifiers must fail closed or preserve
  opaque stored data. A provider failure cannot silently switch roles or add
  another provider's book context.

Native sync uses WebDAV, self-hosted HTTPS progress and local ZIP backups.
These are the supported synchronization and recovery boundaries.

Success is demonstrated by request/role/fallback/authorization and spoiler tests,
SQLite session compatibility/backup checks, targeted reader/dictionary tests,
then core tests, Android unit tests and both APK builds. Device acceptance and
credentialed provider tests are reported separately from local validation.

## Generation scope extension contract (2026-11-27)

Status: implemented with scoped regression evidence in the
[2026-11-27 implementation record](../../android/IMPLEMENTATION_2026-11-27.md).
Integration15 passed all 13 generation methods on both API31/34, covering the
three scopes, persisted authorization, source changes, legacy compatibility,
one same-provider correction and checkpoints. Integration16 passed all 10
preflight methods on both devices, including the production constructor and
local HTTP/SSE transport path. Production integration24 (native commit `dcc3606d`) built both flavors and
passed core/JVM checks; its API31 standard suite reports 262 passed, 21
assumption-skipped, 0 failed; API34 fullOcr reports 266 passed, 17
assumption-skipped, 0 failed. These are bounded regressions, not full live-model scope/UI acceptance
or M0–M7 milestone delivery. Whole-book live generation and physical-device
acceptance remain open. This section defines required behavior; the 2026-11-26
completed/read-chapter-only result remains a historical baseline.

### GenerationAuthorization and payload v2

Extend the existing scheduler and request boundary with a reusable
`GenerationAuthorization`, with an explicitly confirmed scope:

- `READ_BOUNDARY`: chapters permitted by the captured exact reading boundary.
- `SELECTED_CHAPTERS`: the immutable chapter list shown in the confirmation;
  unread chapters require an explicit spoiler and source-transmission disclosure.
- `WHOLE_BOOK`: all eligible chapters listed for this book, including unread
  content, after one explicit confirmation for this generation task. “Whole book”
  is an authorization scope, not a promise to send one unbounded provider request.

Authorization fixes `bookId`, `taskId`, scope, the ordered chapter list, source
hashes (book identity and chapter-content hashes), and explicit user consent.
Store the confirmed scope and consent provenance with the durable task; a Boolean
`userAuthorized` alone cannot authorize unread content. Allocate the task identity
before binding authorization and persist both atomically. Freeze or invalidate a
plan when its sources change; never silently append new chapters to a task.
A resume can reuse the same authorization only for the same task, sources and
chapter list. New scope, new task, expanded list or changed content requires a new
confirmation. Pause/cancel and recovery remain available at safe points.

Generation task payload **v2** carries a version discriminator and the complete
`GenerationAuthorization` alongside the existing kind, pipeline version, captured
reading snapshot, source manifest and checkpoint information. Existing payload
**v1** (including legacy payloads without the new version discriminator) means
`READ_BOUNDARY` only: keep its original captured boundary and chapter list, never
infer `WHOLE_BOOK` from a high progress value or migrate consent by default.
Unverifiable legacy payloads remain paused or fail closed with an actionable
message; unknown future payload versions are preserved and cannot execute.
Reuse v22 task/payload storage rather than introducing a parallel task table.

### Request routing and repeated validation

`AiRequest` gains an optional generation-scope authorization reference/envelope.
Only registered **generation workloads** may consume it: the current registry
identifies `reading.wiki`, `reading.atlas` and chapter-generation `reading.notes`;
any future generation workload must explicitly declare that capability. A shared
workload ID does not automatically authorize every caller: note organization
under `reading.notes` remains limited to its own frozen selected-note sources and
cannot acquire unread book context through a generation authorization.
Chat, selection explanation, translation, provider diagnostics, local reading
events and unknown workloads cannot use this envelope to expand their boundary.

The gateway and `AiContextAssembler` validate workload capability and the bound
book/task/sources; UI code cannot directly bypass the assembler or alter the
reading snapshot to look fully read. Generation permission widens only this
confirmed task's source input, never ordinary chat history, local projections,
reading progress or provider fallback roles. Exact quotations, chapter identity,
source offsets and `visibleFromProgress` remain required Evidence.

Revalidate **before reading sources**, **before every provider call** (including
retries/corrections), and **before committing results**. Check book/task identity,
explicit consent, scope, chapter membership, content hashes, task state and
applicable captured boundary. Reject revoked/cancelled, stale, mismatched or
expanded inputs; do not consume the model or overwrite usable data on failure.
The write gate remains the authorized action/mutation service with atomic
checkpoint/result commits and conflict-safe undo. Scheduler restoration stays
paused; an explicit resume does not broaden the original permission.

### Generation consent and viewing consent are independent

The confirmation shows selected range, unread coverage/spoiler warning, provider,
estimated input scale/batches (not invented billing), output kinds and controls.
One confirmation authorizes the frozen whole-book generation task; do not request
consent per chapter. Never fake reading progress to obtain full-book inputs.

Default Book Hub, Wiki, Atlas, search, suggestions and context projections remain
filtered to **already read** content, even after whole-book generation. “View all
results” is a separate explicit **local authorization for this book and this
viewing session**. It may expose already generated unread material and its sources
only within that view. It does not run a model, change reading position/progress,
change default spoiler visibility, grant another book/device access, or expand
ordinary chat context. Leaving the viewing session, switching books or restarting
clears that viewing grant; returning to the safe view restores read-boundary
filtering. Source navigation revalidates the applicable viewing grant.

### Schema correction, checkpoints and failures

Malformed Wiki/Atlas structured output may trigger **at most one schema correction
with the same provider** (same selected provider identity, no cross-provider
correction). Reuse the same authorization, validated source slice, role, budget
and schema; record correction usage and failure separately. Do not loop on bad
JSON, invent evidence, widen scope or silently switch providers to correct it.
This limit complements the existing gateway's bounded transport/key retries;
a partial stream must not be duplicated by a new fallback attempt.

Validate corrected output through the same schema, unknown-kind, exact-Evidence,
spoiler and mutation gates. If still invalid, retain all valid checkpoints and
previous usable results, mark the failed unit with a retryable/actionable reason,
and expose pause/resume/cancel. Resume skips committed source hashes; explicit
retry cannot erase completed checkpoints or regenerate unrelated chapters.

Implementation acceptance must cover v1/v2 compatibility, all three scopes,
workload/caller isolation, forged and stale authorization, each validation gate,
restart/resume/cancel, one-correction exhaustion, atomic checkpoint writes,
unknown versions/kinds, local view-all revocation and ordinary-reading zero model
calls. Fresh evidence belongs in the implementation's acceptance record.


## Approved specification references

The [master roadmap](../todo/inkflow-master-roadmap.md) owns milestone states.
The [AI/Skill specification](../todo/inkflow-ai-interaction-and-skills-spec.md)
defines the product flows, the [UI specification](../todo/inkflow-ui-structure-and-specs.md)
defines phone/wide-screen presentation, and the
[reader interaction specification](../todo/inkflow-reader-interaction-spec.md)
defines selection and source-return behavior. These specifications extend this
contract; their approval is not implementation or acceptance evidence.
