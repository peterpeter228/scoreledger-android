分数记录 Android 内测版 0.1.0-beta

先安装同目录 ScoreLedger-0.1.0-localBetaDebug.apk。适用 Android8.0及以上，不需要Google服务，不需要账号或联网。请先使用虚构数据试用。

已实现：孩子与科目管理、成绩录入/编辑、持久草稿、历史筛选、趋势与原值、PDF生成/应用内预览/保存/分享、JSON备份预览与完整替换恢复。
0分、缺考、未录入独立处理；修改科目默认满分不会重写旧考试快照。

实际验证：
- 同一个固定签名APK在AOSP Android8/API26、Android11/API30、Android16/API36均安装并运行。
- 每个平台34项常规设备测试通过；另分别执行合成数据写入、同APK覆盖重装后保留验证，两项通过。
- 8个JVM测试方法、64个core内部检查、46个Python契约测试通过。
- 应用无INTERNET权限，测试系统无GooglePlayServices。
- 已检查实际3张界面截图及2页原生PDF。业务APK和补充诊断测试的来源区别见test-evidence/production-source-equivalence.json。

重要边界：
这是第一版内部测试包，不是“完整PRD70项全部通过”或正式商用品。广义验收为4PASS、52PARTIAL、14NOT_RUN，详见source/tasks/ACCEPTANCE.json及test-evidence/final-acceptance-mapping.json。
真机/OEM输入法、TalkBack、实际DocumentsUI及第三方文件提供方、最大10万条成绩性能、不同版本升级、真实支付和公开发布仍未完整验证。
PDF单份上限200个实际排版页，超过会整体拒绝并提示缩小范围，不会截断或漏出一部分。JSON完整备份不受这个PDF页数限制。
已测的是同一APK覆盖重装，不等于未来所有版本升级都通过。

使用：
1. 创建孩子档案，添加/选择科目模板。
2. 点“记一次成绩”，选择日期和科目，填分数或缺考/未录入状态；草稿会保存在本机。
3. 在记录/趋势查看；PDF报告可展开生成并在应用内预览。
4. 设置中定期导出JSON备份。PDF不能用于恢复。恢复是完整替换，不是合并，先备份当前数据。
5. 卸载通常删除本机数据；不要为更新先卸载。安装来源提示只针对你确认的本次文件，不需要关闭安全软件或系统保护。

文件：
- APK：直接安装的内测程序。
- source/：Kotlin+Compose+Room源码、Gradle Wrapper、项目约定与最新交接。
- test-evidence/：真实构建日志、设备运行、签名/哈希、覆盖重装、截图与PDF样本。
- research/：Monid调研结论；仅用于开发研究，不接入App。本轮费用USD0.00135，未充值/订阅。
- SHA256SUMS.txt：本交付包文件完整性校验。

固定内测签名私钥和密码不在本包、源码或APK中，恢复备份另存在已确认仅你本人可见的GoogleDrive。
仓库：https://github.com/peterpeter228/scoreledger-android
业务源码：58a91061b33e853f66e475bdc2273ff6af5c63c8
补充诊断：e0c971eaf125c133bc110f5d714f82854085c27c（生产源码与构建配置未改变）
APK SHA256：d2bb25d9b192bc12853148fbf3b0ec208c4842b874f77a2e3b08e356844f1a92
