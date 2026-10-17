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
