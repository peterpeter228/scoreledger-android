# 变更日志
## 1.0 — 2026-10-17
创建面向dot的Android开发包；需求从iOS机会分析中保留本地记录闭环，但支付、存储、构建、权限全部重新按Android设计。
附源码起点、业务测试和证据状态；不宣称完成App或支付。

## 2026-10-16 D00 intake
实际 ZIP 校验、42 项 Python 测试通过；构建环境补齐中；修复跨模块 nullable 访问候选编译问题。

## 2026-10-17 D02–D04 implementation, verification pending
- Added native child/subject management, explicit templates and original launcher icon.
- Added Room v2 durable child-bound drafts with explicit v1 migration, FIFO writes and atomic submit/cleanup.
- Added multi-subject entry/edit, history scopes/detail/filter, three-destination native navigation and centralized static strings.
- Added 8 draft/migration/query instrumentation cases and 1 Compose isolation/resume flow; execution pending.
- Local static checks, Python42 and core59 passed. Android compilation and runtime acceptance for this slice are not yet verified.
- Review fix: transaction-bound expected-payload compare-and-swap for draft autosave/close/discard/submit; two independent ViewModel stale-write regression added. Conflicts preserve local fields and require explicit confirmation before reloading.
- Filters now enforce exact four-digit ISO dates while allowing a future upper bound.

### 2026-10-17 D05 native trends (source, device verification pending)
- Added shared scoped filters, stable core trend records, missing/scope discontinuities, fixed percentage axis and original-value mode.
- Added accessible ordered values and point selection, exact core changes and total-comparison explanation; no inferred ability.
- Fixed draft conflict persistence wording. Local core62/Python42/static passed; Android/device not run for D05.

### 2026-10-17 D06 backup and restore (source, CI verification pending)
- Implemented strict bounded streaming Gson v1 JSON, native SAF and explicit replacement preview, precise import semantics and immutable pending data.
- Added pre-delete completed internal rollback, atomic replacement, stale baseline/replay/editor protection, explicit draft discard, settings/cleanup/privacy and backup success timestamp.
- Recorded capacity/Unicode/future-date/parser ADR. Added actual importer JVM and Room fault tests (not run). Local core63/Python46/static passed.

### 2026-10-17 D07 native report and D06 hardening (unverified Android)
- Added paginated native A4 report, scoped/core summaries and details, optional beta trend, source-row coverage/bounds checks, privacy controls, native preview/save/share and24h cache cleanup.
- Added PdfRenderer/synthetic screenshot/layout tests and backup audit regressions; Android execution NOT_RUN.
- Closed source review findings for rollback retention/bulk I/O, per-row parse validation, exact external backup bytes, directory sync and stale editor generation.
- Actual local checks: core64, Python46, package static pass. Commercial purchase remains unavailable, no added account/cloud/OCR/AI/network integrations.

D07审查修复：有效多换行备注按测量行边界跨页、原文无损；新增250行备注测试（NOT_RUN）。旧epoch写入返回后的UI状态也再次失效，避免恢复后旧编辑页重现。

### 2026-10-17 D09 offline PDF preview (source only)
- Added bounded single-page in-app PdfRenderer preview with readable previous/next/page count/close controls; external reader optional.
- Moved file metadata and FileProvider setup to IO; kept activity launches on main.
- Added isolated debug-host ActivityScenario recreation and preview bounds/navigation tests (NOT_RUN). Local core64/Python46/static pass; no release/signing changes.

D09 lint修复：移除StaticLayout可选高质量breakStrategy常量调用，保留默认换行，避免API29常量与minAPI26冲突；不抑制lint。新lint待CI。

### 2026-10-17 D09 lifecycle/accessibility safety repairs
- Persist opaque generated PDF ID across SAF/Activity recreation; validate private cache file before output writes.
- Dismiss stale restore/clear confirmation on failure and announce actionable retry guidance.
- Make report checkbox labels clickable with combined role/checked semantics.
- Explicitly inject isolated DB/file/settings managers into test hosts; add recreation/error/semantics and synthetic mixed-score journey tests (NOT_RUN).
- Local core64/Python46/static passed. Previous2c9 actual CI build/JVM/lint succeeded; new source/device results remain separate.

Device milestone: exact2c9 AOSP30 emulator executed26tests,25pass/1failure in migration fixture parsing. Corrected legitimate absent indices handling and strengthened row/index preservation assertions; rerun pending. Added opt-in guarded synthetic signed reinstall seed/verify test (NOT_RUN, skips normal discovery, never clears data).

- PDF beta safety: explicit 200-page per-report limit (no silent truncation), cross-manager serialized generation, cooperative cancellation and failed/cancelled publication cleanup. Added boundary/cancellation instrumentation; runtime verification pending.

- Fixed SAF ownership lifetime: report save and backup picker registrations/state are now above lazy UI items, so recreation/scroll disposal cannot orphan callbacks. Report save regression retains exact-byte and visible-success assertions and captures failure evidence; added isolated backup export/preview recreation coverage. Device rerun pending.

- Capture-only: synthetic PDF/UI diagnostics now use internal filesDir/pdf-evidence and filesDir/ui-evidence for package-scoped run-as collection. Existing filenames retained; no application business changes. Prior exact58 API30 ordinary tests and opt-in reinstall retention passed; visual evidence collection rerun pending.
