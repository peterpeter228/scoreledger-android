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
