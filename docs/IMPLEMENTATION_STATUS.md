## 2026-10-17 独立审查后修订（优先于下方历史条款）
用户授权按审查修复并接受云真机不可用时以安卓模拟器实际操作验收。候选版 0.2.0-beta，versionCode 2；沿用既有内测签名与包名，不改变商业发布阻断。
基本录入、上场科目/满分复用、多孩子基本使用、完整历史、搜索/筛选/得分率趋势、标准完整 PDF 和备份恢复均不设商业数量门槛。取消旧 3 场/1 活跃孩子限制，保留20孩子/100科目/10000考试/100000成绩等资源安全上限。¥18仅待验证假设，不接真实付款或订阅。
原始分折线取消，原分/满分保留可读表。空年级/学期为信息不足，不视为已知相同口径。参考新审查样稿统一主题，首页近期记录优先，报告及危险操作不占首页。
本轮不以代码通过替代实际 APK 操作。T18真人家长研究、4–6周留存和未来真实支付不得虚构；模拟器结果不冒充 OEM/真机/中文IME/TalkBack。

# Current status: first internal beta candidate

See PROJECT_STATE.json and evidence/final-acceptance-mapping.json for authoritative current evidence. Exact business58 signed APK passed AOSP API26/30/36,34 ordinary tests and2 separately executed guarded retention checks per API. No claim of full70-case or commercial acceptance. Supplemental visual capture passed and sample UI/PDF were inspected. First internal beta is ready for delivery; full product/commercial acceptance remains incomplete.

## Historical implementation checkpoints

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

D06增量（00:41 UTC）：备份/恢复/设置源码IMPLEMENTED_UNVERIFIED；实际导入器BackupCodecTest与Room替换故障测试已写，尚未运行。Gson依赖首次解析与新安卓编译待CI。core63/Python46/static真实通过，不能替代实际JSON导入器或文件提供方/设备测试。D07仍未实现。

D07增量（00:49 UTC）：原生分页PDF/预览/保存/只读分享/缓存维护已实现，IMPLEMENTED_UNVERIFIED。D06审查修复也已写入。实际本地core64/Python46/static通过。新增实际Gson JVM、Room恢复及PdfRenderer/布局仪器化测试尚未运行；无新Android编译、设备渲染截图、真实分享或支付验收结论。原先表格“PDF/备份NOT_STARTED”被此源码状态替代，不代表验收完成。

D07首次真实编译CI38988358601失败在两处API/命名错误；已源码修复resetBudget及目录O_RDONLY fsync，同时修复多换行备注分页。重构建待跑，绝不据源码改动标PASS；本地修复检查core64/Python46/static通过。

D09增量（01:02 UTC）：应用内离线单页PDF预览、IO线程分享准备已实现；新增隔离ActivityScenario.recreate与预览导航测试尚未运行。core64/Python46/static真实通过；新Android/device验收NOT_RUN，D09整体仍IN_PROGRESS（大字/TalkBack/矩阵未通过）。

01:15 UTC证据更新：2c9b199e CI38989323329成功，8JUnit方法/64core断言、Gson和retention测试通过，lint0error30warning；device38989862766仍执行中，无通过结论。此后新增SAF重建恢复/确认失败提示/复选框语义/显式隔离UI服务修复及合成E2E测试是未编译新源码，保持IMPLEMENTED_UNVERIFIED，不借用旧CI成功。

01:20 UTC：真实2c9 AOSP30/KVM仪器化26项已执行，25PASS/1FAIL/0SKIP，失败为migration测试把真实schema合法省略的indices视为必填。源码测试修复并增强实体/索引保持断言，重跑未执行。新guarded签名重装保留测试仅实现，未运行；当前P1源码也不能借用2c9设备结果。
