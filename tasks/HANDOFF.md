# 当前交接
阶段：D00，实际取得并安全解压用户 ZIP，完整读取启动规范与 PRD。
目录独立于易绘创项目；没有指定或使用远程仓库。
已验证：原始 95 文件哈希校验、42 项 Python 测试、包静态检查。
当前环境：Java 21 仅运行时，无 javac / kotlinc / Android SDK / adb，无 /dev/kvm。
正在从官方源安装校验后的 JDK 17，Gradle 8.13 官方分发已经校验并运行。
SDK 许可已向用户提出确认，尚未接受或安装。
首次构建日志 evidence/d00-first-build.log，不能标 Android 编译通过。
代码改动：StarterScreen 跨 core 模块 nullable 属性改为局部变量，等待真实编译验证。
审查发现待 D01/D03：成绩总行数上限、重复 UUID 幂等确认、孩子绑定的持久草稿。
下一步：JDK 17 运行 core 测试；获得 SDK 许可确认后安装 SDK 36 / BuildTools 35 并真实编译、生成 Room schema 和标准 wrapper。
设备、支付、公开发布均未验证；仅虚构数据。

## 后续进展 23:25 UTC
JDK17 与 Kotlin2.2.20 官方下载校验完成，58条基线规则通过；修复year0000备份口径不一致后59条通过。
Gradle8.13标准wrapper实际生成并固定分发SHA256，见evidence/d00-wrapper.log。
网络：Gradle需使用当前执行环境提供的代理和系统现有Java信任库；不能复用上一命令的临时代理端口。本地.toolchains/run_gradle.py每次从环境配置。
Android用户缓存显式放工作区，避免默认home只读；不要同时设置冲突的ANDROID_PREFS_ROOT。
D01源码预备：事务化档案/科目更新归档删除、一致快照、编辑/幂等重试、10万成绩限额、注入Clock/ID及校验；5个仪器化测试新增，尚未SDK编译/设备执行。
当前工作主任务仍D00；SDK许可待用户确认。没有SDK不能称安卓通过。

## 23:28 UTC 构建阻断
标准wrapper已生成；真正 Gradle :core:compileKotlin 成功，但 :core:test 的 junit-4.13.2.jar 下载被工具网络策略阻止（repo.maven.apache.org）。
require_escalated 的合规审批入口尝试未执行构建，执行器初始化失败（只读运行时目录）。未尝试绕过。
离线 Gradle 重跑确认缺少 junit JAR；evidence/d00-core-offline.log。CLI 59条规则测试不依赖JUnit，已真实通过。
SDK许可仍待用户确认；不可擅自接受。Android编译、Room schema、安装、UI、支付均没有通过证据。
下一步：用户许可确认与可用构建网络后继续D00，之后验证D01源码。不要用旧项目仓库/CI。

## 2026-10-17 00:08 UTC
用户同意 SDK 许可，并要求建立独立 GitHub 仓库。已创建并确认私有 peterpeter228/scoreledger-android，main 初始 README 提交 1622fe14f37ef73d432402dc5628d421792ed1fc。准备首次 CI，不涉及其他仓库。
SDK 最新 CLI 23 的自动 Android CLI 下载遇连接拒绝，已改用官方校验的固定 cmdline-tools 19.0，匹配 JDK17 基线；正在安装 SDK36 / BuildTools35 / platform-tools。

00:13 UTC：源码提交1ea7e5ba44b165856b7b3a5ee64ceceb7e21db0d、树12c6a55c73bbf9630d81da163c12cfc75ba703d8已验证推送。CI38985168690/job117254833533执行中；不要重复创建仓库或误报APK。下一步读CI终态、修复编译、取真实schema/APK，再启用准备好的AOSP API30仪器化脚本（tools/emulator_smoke.sh和本地workflow改动尚未推送）。

00:18 UTC：CI38985168690成功，真实APK10,997,249字节/SHA256 8a9cba634603d572d9de83c9c1c6d9940a627f9c623d7510824e3573fcec35cb。JVM两个JUnit方法通过，其中core含59子断言；lint无阻断但有升级等warning。Room自动schema1已从artifact复制入app/schemas。设备仪器化仅编译，尚未执行；下一提交启用AOSP API30模拟器安装与6项Room测试。详细evidence/d00-android-build.json。
