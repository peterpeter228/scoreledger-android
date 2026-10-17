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
