# Airead native AI migration contract

Updated 2026-11-26. The Flutter gateway cannot be called from the standalone
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

CloudBase is excluded from the native migration. Native sync uses WebDAV,
self-hosted HTTPS progress and local ZIP backups. Legacy Flutter CloudBase data
and source remain compatibility references; they are not native dependencies.

Success is demonstrated by request/role/fallback/authorization and spoiler tests,
SQLite session compatibility/backup checks, targeted reader/dictionary tests,
then core tests, Android unit tests and both APK builds. Device acceptance and
credentialed provider tests are reported separately from local validation.
