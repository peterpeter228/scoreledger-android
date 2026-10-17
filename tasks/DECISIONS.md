# 决策日志
ADR-001：原生Kotlin/Compose/Room；Android单平台、低维护和系统文件能力优先。
ADR-002：先localBeta，再正式渠道收款；不把Google Play当中国大陆通用支付。
ADR-003：三个现存考试的轻量免费模式；删除释放名额，数据导出永不锁定。
ADR-004：备份v1完整替换，不做自动合并；明文透明告知，不假称加密。
ADR-005：标准分/百分位/能力评级排除；只有客观原分、满分、得分率。
ADR-006：Android工程骨架与完整PRD明确区分；当前未SDK编译，不以“文件齐全”冒充成品。
ADR-007：当前网络无法取得Gradle wrapper二进制，不附未知JAR；提供官方分发校验引导器，D00生成标准wrapper。
ADR-008：本项目远程仓库未指定。不得使用历史客服/交易项目仓库。
变更格式：ID、日期、动机、备选、依据、影响、回退、验证证据；不得回填虚构历史。

ADR-009（2026-10-16）：官方 Gradle 8.13 分发实际下载并验证后生成标准 wrapper，替代 shell/Python 引导入口；固定分发 SHA-256，不改变版本。证据 evidence/d00-wrapper.log。回退可调用保留的 gradle_bootstrap.py。

## ADR-2026-10-17-Backup strict streaming and capacity
Decision: pin com.google.code.gson:gson:2.14.0 from official Maven registry, use only JsonReader/JsonWriter with Strictness.STRICT, nesting limit3, exact token types/field sets/decoded duplicate-key sets and END_DOCUMENT. No reflection/fromJson/DOM. Source reference: https://raw.githubusercontent.com/google/gson/gson-parent-2.14.0/gson/src/main/java/com/google/gson/stream/JsonReader.java . Android native JsonReader lenient=false alone is insufficient for the required strict lexical contract.
The previous20MiB cap contradicted PRD100,000scores/10,000exams. Compact valid UTF8 JSON including six-byte U+2028/U+2029 escapes has upper bound76,624,416bytes. Set input/output cap96MiB=100,663,296. No compression/merge. Decoder rejects malformed UTF8, initial BOM allowed. Reader resets a16KiB character budget for each bounded parser operation (including peek/hasNext/nextName/nextString); Gson may already buffer up to1024 lookahead characters. Maximum valid escaped500-codepoint note needs at most6000 raw characters. Intentionally reject pathological inter-token padding as well as huge scalar/name allocations; this is a resource bound, not an alternate JSON parser. Negative zero is rejected along with fraction/exponent/coerced bool/string/overflow integer encodings.
Restore validates calendar dates without comparison to device today; this is an explicit exception to new/edit future-date rejection. Restore preserves exact strings/IDs/timestamps/archive flags/name duplicates without normalizing or entitlement add limits. Reject unpaired surrogates for local and imported text; required blank semantics include Unicode spaces/NEL. Python oracle uses the same semantic Unicode/negative-zero/cap decisions.
Pending parsed records are immutable and never reread from the external URI after preview. Current exact snapshot+drafts+generation must still match inside the replacement Room transaction. A completed fsync+rename internal JSON rollback file must exist before first DELETE. It excludes drafts and entitlements: transaction failure preserves original drafts, successful replacement explicitly discards old drafts after count/warning. Single-use pending tokens prevent UI retries deleting newly added records. Generation invalidates stale editors/queued mutations. Keep at most3 completed rollback files and remove older than7days; partial files do not count. Internal rollback is not uninstall/disaster recovery.

Backup audit hardening (D07 checkpoint): protect the just-completed rollback from future-mtime/clock-correction eviction, retain it plus at most two other completed files, check existence and fsync directory before clearing Room. Bulk forwarding avoids byte-at-a-time disk writes. Validate each scalar/row locally before accumulating collections; cross-reference validation remains after parse. If SAF provider can reopen an exported backup, require streaming SHA256 byte equality with the completed temp file (not merely another valid JSON). Do not claim power-loss durability or provider behavior has passed until device tests run. The database instance owns the generation; VM editor/visible generations bind to the opening/observed snapshot, never recapture a post-restore epoch for stale input.
