# 实现状态与交付边界

截至2026-10-17：D00已有真实debug APK，但不能把起步APK说成新功能完整成品。

| 范围 | 当前状态 | 证据/后续动作 |
|---|---|---|
| Kotlin核心计算与额度规则 | 本地59断言、CI核心JUnit通过 | evidence/d00-android-build.json；evidence/d02-d04-core.log |
| Python备份契约参考校验 | 本地42测试通过 | evidence/d02-d04-python.log；安卓导入器尚未实现 |
| 起步Compose+Room Android构建 | VERIFIED | CI38985168690，提交1ea7e5ba44b165856b7b3a5ee64ceceb7e21db0d；真实APK及schema1 |
| D01数据库事务和仓储 | 源码及首版编译通过，仪器化执行待主线程核实 | 不据测试文件存在宣称PASS |
| D02孩子/科目管理 | IMPLEMENTED_UNVERIFIED | 昵称/年级、归档、显式删除、科目排序、主动建议模板 |
| D03草稿/录入/编辑 | IMPLEMENTED_UNVERIFIED | Room2显式迁移、孩子绑定JSON、FIFO保存、原子提交清理、显式1–30科 |
| D04历史/详情/筛选/导航 | IMPLEMENTED_UNVERIFIED | RecordQuery共享查询范围、快照详情、确认删除、记录/趋势/设置 |
| 趋势页面、PDF、备份恢复 | NOT_STARTED | 页面明确告知未接入；无假数据、无伪完成按钮 |
| 真实商店付费 | NOT_IMPLEMENTED | 商业发布保持阻断，localBeta不等于已购买 |
| 新源码Android编译、Room schema2 | NOT_RUN | 由下一CI真实生成并提取，不手写schema2 |
| 新增8项Room草稿测试及1项Compose流程 | NOT_RUN | 待connectedLocalBetaDebugAndroidTest |
| 真机、API26/36、2倍字体、TalkBack、性能 | NOT_RUN | D09/D10实际设备验收 |

静态检查、Python、纯Kotlin通过不等同于新Android源码编译通过，也不等同于设备验收。
未实现项目仍继续开发；当前所有测试仅使用虚构数据。

D05增量（00:35 UTC）：原生Canvas趋势、原值模式、可访问明细、共享筛选与core序列已经实现，IMPLEMENTED_UNVERIFIED。本地core62/Python42/static通过。Android编译/设备可视与交互验收待CI，D06备份和D07 PDF仍未实现。之前表格中“趋势NOT_STARTED”已被此增量替代。
