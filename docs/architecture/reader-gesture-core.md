# Reader gesture arbitration and optional edge selection

Status (2026-11-27): both core state machines and their targeted tests are implemented
and frozen for parent integration. Platform pointer/handle/lifecycle wiring remains
pending; the complete reader interaction is partially implemented and awaits fresh
acceptance. Implementation status does not imply M4–M7 package approval.

These pure Kotlin controllers are integration APIs, not proof that platform readers
have already adopted the interaction specification. They introduce no Android,
network, AI, storage, timer or coroutine dependency. Use both on the same serialized
reader event thread as `SelectionRangeController`. Do not mutate the range concurrently
with completion checks.

## Gesture owner

`ReaderGestureArbiter(touchSlop, longPressMs = 500)` consumes one pointer sequence
once. Coordinates and slop must use the same units. A scene is identified by
`ReaderInteractionScope(bookId, chapterIndex, layoutRevision)`; increment the revision
on pagination, source text, scale, viewport, or hit geometry changes. IDs alone cannot
validate chapter text: the platform must construct scopes from its current source.

1. On DOWN, hit-test the scene once and call `down(pointer, scope, point, nowMs, candidates)`.
   Preserve its `gesture` token and dispatch **all** returned deliveries, including
   cancellation of a previous sequence. Candidate input order breaks same-group ties;
   the arbiter copies the list. Do not register targets absent from the DOWN hit test.
2. Feed MOVE and UP through `event(gesture, pointer, currentScope, phase, point, nowMs)`.
   Optionally supply `eligibleIds` to recheck unclaimed hit targets at that point.
   This can remove eligibility for this event but cannot add new candidates or dislodge
   an owner. The highest acceptable candidate wins, using the fixed order:
   overlay/handle, TTS, link/footnote/image, annotation, selection/page, chrome.
3. Schedule a HOLD event at `downTime + longPressMs` if selection is available, using
   the last observed pointer position and a monotonic clock. UP can also resolve a
   long press when timer dispatch is delayed. A tap must release before the long press
   threshold. Movement beyond slop permanently disqualifies both TAP and unclaimed
   LONG_PRESS; DRAG becomes eligible on MOVE beyond slop.
4. Process loser CANCEL deliveries **before** the CLAIM action. Execute a tap/page/
   overlay/TTS action only on CLAIM, once. Route UPDATE and END to the same owner for
   handle dragging; do not execute the original action again on END. There is no
   mid-sequence handoff, even when the owner leaves its original rectangle. UP ends
   ownership; duplicate or old token/pointer events are ignored.
5. For a pointer CANCEL callback use `cancel(gesture)`; it ignores old tokens. Use
   unqualified `cancel()` for scene-wide interruptions: additional pointers, overlay
   changes, lifecycle/visibility changes, and before swapping source/layout. Dispatch
   the resulting cancellations to release handles and cancel their edge selection.

Register TTS/link/annotation/chrome as TAP only; the API rejects configurations that
would execute them on DOWN. Selection/page supports TAP, LONG_PRESS and DRAG. Only
an overlay/handle may claim DOWN. Examples: selection-dismiss surfaces are overlay
TAP candidates; selection handles are overlay DOWN or DRAG candidates; paragraph
selection is a selection/page LONG_PRESS candidate. TTS does not pause on DOWN, so
an overlapping long press can select; its short tap wins over a link. Once a handle
claims, it owns the remainder even if moved onto a TTS or page target.

Do not leave a second platform click detector or ordinary page-turn path consuming
the same sequence. The host still owns hit testing, accessibility activation and
physical-to-logical direction mapping (including RTL).

## Edge extension

Create `ReaderEdgeSelectionController(selection, sourceLength)` for the **same chapter
text** as the supplied `SelectionRangeController`. It is disabled by default.
`setPolicy(enabled = userOptIn, eInk = actualDisplayMode)` must receive the effective
display mode, not just a preference. E-INK always suppresses this enhancement;
explicit extension controls remain a platform responsibility.

The host sequence is:

```kotlin
// Only after the arbiter has given this handle CLAIM:
val started = edge.startDrag(scope, endpoint, loadedEndpointPage,
    selection.snapshot!!.revision, monotonicNow)

// While this owner is dragging; null means pointer left the edge band:
edge.hover(scope, selection.snapshot!!.revision, logicalEdgeOrNull, monotonicNow)

// Schedule/reschedule at edge.nextWakeUpAtMs; a null deadline cancels the timer.
edge.poll(currentScope, monotonicNow)?.let { request ->
    // Resolve request.targetPage in request.scope, keeping this exact request object.
    // In the loader callback (serialized on the reader thread), with a fresh clock:
    val commit = edge.complete(request, loadedPage, currentScope, freshMonotonicNow)
    // Only a non-null commit authorizes rendering/navigation to commit.page.
}
```

After each hover/rebase/poll/complete/policy/cancel operation refresh the scheduled
wake-up from `nextWakeUpAtMs`. HOLD/edge timers must use a monotonic millisecond clock,
not wall time. A backwards clock cancels the active state.

`LoadedPage(scope, slice, isLoaded)` binds a slice to the caller-verified chapter and
layout. Never relabel an old page with the current scope. Reject a missing/unloaded
page in the loader or return `isLoaded = false`; completion then cancels without
writing the range. The controller verifies the scope, adjacent page index, source
bounds and non-overlapping offsets. Whitespace gaps between adjacent measured pages
are accepted. The host must supply actual slices from this chapter paginator, with
valid text boundaries; an index and offset alone do not establish source identity.

The first edge dwell is exactly 600 ms. Continuations wait 900 ms **from successful
completion**, so a slow page load cannot cause catch-up turns. Repeated hover in the
same edge band keeps the deadline; leaving and re-entering starts a fresh dwell.
Only outward expansion is supported: END/NEXT or START/PREVIOUS. Each drag preserves
its origin page and bounds the dragged endpoint to ±4 page indices from that origin,
including across edge re-entry and ordinary handle adjustments. It never requests a negative page and
cannot cross chapter scope. At the chapter end, report the missing page and cancel;
never fall through to next-chapter navigation.

There is at most one pending request. Repeated poll/complete cannot double-extend.
The exact request object is the completion capability; copied/deserialized requests
are not accepted. Successful completion advances the selection revision and returns
both the range and target page. The host must display this commit before accepting
more ordinary handle movement. If navigation fails or layout changes, cancel the
drag; do not invoke a normal page-turn fallback with the same gesture.

Range writes reuse `SelectionRangeController`: END uses `extendToPage` (preserving
existing retract history), START uses `replace` because the existing controller's
explicit extension API is forward-only. Its `visiblePage` continues to identify the
range **end**; the edge controller separately tracks the page of the dragged START.
Backward expansion does not add a new `retract` entry. If the platform wants undo of
the whole gesture, preserve its original range and restore via a validated explicit
range command after canceling this drag; do not claim backward `retract` support.

For normal handle adjustment, save the old revision, adjust through the range
controller, and call `rebase(scope, oldRevision, updatedSnapshot, loadedEndpointPage,
nowMs)` before hover/poll. For an END adjustment, confirm its actual page through
`SelectionRangeController.confirmVisiblePage` first, passing the final snapshot to
rebase. Rebase invalidates the old load/timer but retains the origin/budget and the
900 ms continuation rule. Do not use rebase to adopt explicit extension commands or
unrelated selection edits: cancel first. Any unadopted revision/session change is
rejected by the next poll/hover/complete and cannot be overwritten by a late response.

Call `edge.cancel()` immediately on the owner's END/CANCEL, pointer-up, leaving the
reader, hidden/background, overlays that interrupt selection, explicit range commands,
chapter/book switch, source replacement or pagination revision. Leaving an edge uses
`hover(..., edge = null, ...)` to retain the drag while invalidating its pending load.
A late completion after cancellation/re-entry/new drag is ignored and cannot mutate
the current selection. Core cannot observe platform lifecycle changes by itself;
these hooks are mandatory parent integration work.

## Validation and ownership

New source files: `ReaderGestureArbiter.kt`, `ReaderEdgeSelectionController.kt` in
core/commonMain; corresponding commonTest files exercise the real existing range
controller. Tests cover all pairwise priorities, one claim, loser cancellation,
tap slop, delayed long press, stale gesture cancellation, source/layout invalidation,
policy defaults/E-INK, exact dwell boundaries, in-flight request uniqueness,
load/cancel/revision races, invalid/unloaded/cross-chapter pages, forward/backward
four-page limits and rebase preservation.

A standalone cached Kotlin compiler + JUnit runner can validate this slice without
Gradle. Full KMP compilation, lint and actual touch/display integration remain the
parent build/device checks; fresh results belong in the independent validation
report rather than this API specification. Track core implementation, platform
integration and acceptance as separate states.
