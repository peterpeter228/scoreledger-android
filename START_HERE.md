# dot 的唯一启动入口

## 阅读顺序
1. `AGENTS.md`：权限、质量和范围边界。
2. `PROJECT_STATE.json`、`tasks/HANDOFF.md`、`docs/IMPLEMENTATION_STATUS.md`：当前状态，不从记忆猜进度。
3. `docs/PRD.md`、`docs/STATISTICS_RULES.md`、`docs/DATA_CONTRACT.md`：产品和数据规则。
4. `docs/ANDROID_ARCHITECTURE.md`、`docs/UI_UX.md`、`docs/DOT_RUNBOOK.md`。
5. 只加载当前任务需要的其他文档；以 `tasks/BACKLOG.json` 驱动实施。

## 第一次行动
运行 `python3 tools/preflight.py` 和 `python3 tools/verify_package.py`。
运行 `python3 -m unittest discover -s reference -p 'test_*.py' -v`。
有 Kotlin 编译器时运行 `bash tools/test_core.sh`，否则用 Gradle `:core:test`。
检查 Java、SDK、网络、磁盘、模拟器/真机、连接器权限；记录可用、不可用、未验证，不伪造。
进入 D00：先让起步工程真实构建并能启动，再推进 D01。

## 工作方式
每完成一个垂直切片就更新状态、测试和产物。一次只认领一个写入任务。
子代理可并行审查不同范围，但不能同时写同一文件；没有子代理则串行执行。
本包已有业务规则与起步代码：优先延续，不清空重做。
没有安卓构建环境时先完成可执行的纯业务测试与代码，再走已授权的 CI；不能把任务降成网页。
依赖下载失败时记录具体 URL、错误类型和有限重试，不停留在“再运行一遍”。

## 不可跳过的门禁
- 已提交数据不能因升级、支付状态变化、导入失败而消失。
- 只有 PURCHASED 且按真实渠道完成校验/确认的交易才能开启商业权益。
- 未接通支付时可交付内测，但不能说“已经可收款”。
- 没有实际 APK 文件和安装证据，不说“手机能用”。
- 公共发布、收费、创建云资源、传输真实孩子信息、改账户权限，均不在本包默认授权内。
