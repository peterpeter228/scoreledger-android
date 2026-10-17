# 分数记录 · ScoreLedger Android

中文家长端、本地优先的原生 Kotlin / Jetpack Compose / Room 应用。
当前为开发中内测工程，不依赖 Google 服务；不做账号、云同步、OCR、AI 诊断或题库。

从 START_HERE.md 阅读，实际进度以 PROJECT_STATE.json 和 tasks/HANDOFF.md 为准。

## 构建
JDK 17、Android SDK 36、BuildTools 35.0.0；使用固定 Gradle 8.13 wrapper。
运行 ./gradlew :core:test :app:assembleLocalBetaDebug。
首次构建和 GitHub Actions 验证进行中；没有设备测试证据前不宣称可用性验收通过。
正式签名、真实支付、商业发布独立阻断。
