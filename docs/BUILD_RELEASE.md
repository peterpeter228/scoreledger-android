# 构建、测试与发行交付

## 当前包
Android起步工程尚未在SDK环境编译。本包附有可实跑的纯业务/契约测试，不能替代Android构建。
D00 已用经过官方 SHA-256 校验的 Gradle 8.13 生成标准 wrapper，见 evidence/d00-wrapper.log。
`gradlew`/`gradlew.bat` 和 gradle/wrapper/ 为真实生成文件；分发校验和固定在 gradle-wrapper.properties。
旧 tools/gradle_bootstrap.py 保留为可审阅的备用引导器。wrapper 生成成功不等于 Android 编译成功。
本地 .toolchains 与 .gradle-local 为忽略的开发缓存，不打入源码交付。

## 命令
```
python3 tools/preflight.py
python3 tools/verify_package.py
python3 -m unittest discover -s reference -p 'test_*.py' -v
bash tools/test_core.sh
./gradlew --no-daemon :core:test :app:testLocalBetaDebugUnitTest :app:lintLocalBetaDebug :app:assembleLocalBetaDebug
```
Windows使用`gradlew.bat`及`python`；Kotlin纯规则脚本可使用tools/test_core.py。
JDK17、SDK platform36、BuildTools35.0.0为工程基线；sdkmanager许可证由实际环境按权限处理。
没有这些工具先按官方方式安装或用已授权CI，不使用破解SDK/不明镜像。

## 产物
APK：`app/build/outputs/apk/localBeta/debug/app-localBeta-debug.apk`（以真实输出为准）。
测试报告：core/build/reports/tests、app/build/reports；lint路径随版本核实。
运行 `python3 tools/collect_artifacts.py <apk路径>` 收集到out并生成SHA和元数据草稿。
尚无APK就必须失败，不能以README或空文件占位。
同一受控测试设备可adb install -r；不要自动卸载用户现有版本清数据。

## GitHub Actions
包内`.github/workflows/android-ci.yml`只在被用户指定的本项目仓库启用后生效；本轮没有实际推送/触发。
CI默认只读permissions，不使用Secrets，上传测试APK和报告，不自动发布商店。
构建可以有计算费用，需遵守账户配额。正式使用建议将官方actions标签锁定到审核过的完整SHA。
模拟器UI测试单独流程手动触发；当前只给执行说明，不能把常规CI通过称为真机通过。

## release gate
`python3 tools/release_guard.py`默认失败；release/channel_config.json只是占位，不包含真实身份。
生产APK/AAB任务必须依赖门禁；D08实现真实支付并完成证据后再逐项解锁，禁止简单删除门禁。
门禁检查只是技术核对，不代表已获得用户发布授权；正式上架仍由用户批准。
签名：用户控制的keystore + Secret注入；绝不自动生成后仅留临时机、绝不提交密码。

## 最终交付清单
源代码commit/ZIP、APK、sha256、versionCode/name、包名、min/targetSdk、构建日志、JVM/Android/UI测试、
实际安装设备与OS、PDF/恢复录屏或截图、数据安全说明、未完成列表、签名和支付状态、恢复步骤。
“编译成功”“可安装”“完整验收”“可收费”“已上架”单独列示。
