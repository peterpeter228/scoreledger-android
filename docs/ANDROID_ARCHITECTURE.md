# 技术方案与决策

## 主选
原生 Kotlin + Compose Material 3 + Room + Coroutines/Flow。官方推荐的状态/事件单向流和Room数据层适合本项目 [S04–S05]。
只做 `app` 与 `core` 两个模块，手动依赖注入；不加Hilt、KMP、微服务或完整“企业清洁架构”目录树。
`core`不依赖Android，包含分数校验、统计、可比性和权益规则，JVM即可测试。
`app`负责Room、ViewModel、Compose、系统文档/分享、PDF、渠道适配。

## 备选与弃选
Flutter：只有原生路线出现真实阻断、或用户明确要求短期iOS共用时重新评估；当前不引入另一套插件兼容面。
WebView/PWA：弃选，不以网页测试替代安卓安装、文件操作和支付。
Firebase/Supabase：首版没有账号/同步需求；增加它们反而扩大数据和维护面。
图表：先用Compose Canvas绘制简单单线+等价表格，业务计算留在core；复杂交互增加前再评估成熟图表库。
PDF：Android PdfDocument/Canvas原生分页；不引入大体积或复杂商业授权的PDF SDK [S11]。

## 构建基线
AGP 8.13.2 / Gradle 8.13 / Kotlin 2.2.20 / JDK17；compileSdk36、targetSdk36、minSdk26。
AGP官方兼容表支持该SDK/JDK/Gradle组合 [S14]；本包依赖未在安卓环境解析，D00必须实测。
Compose UI1.9.4、Material3 1.3.2、Activity1.11.0、Lifecycle2.9.4、Room2.8.4，固定版本，不用动态“+”。
起步阶段使用KAPT编译Room，少引入一个KSP版本组合；若迁移KSP，单独提交并重跑构建/数据库测试。
不是追求最新版本；只有安全/兼容/渠道要求才升级，记录版本与实测结果。

## 状态与数据路径
Compose事件 → ViewModel校验/保存状态 → Repository → Room单事务。
Room Flow → ViewModel StateFlow → collectAsStateWithLifecycle → UI。
PDF/备份从一致的数据快照生成，不读取正在被异步修改的UI列表。
主线程不做文件读写、JSON大对象验证、PDF生成或数据库操作。

## 原子性
保存 Exam+Score 同事务；编辑时验证旧examId所属孩子，不能靠UI筛选代替校验。
删除和导入用事务；保留快照用于恢复但不复制正在运行的SQLite文件/WAL作为唯一备份。
不用 `INSERT OR REPLACE` 删除父行连带触发子行；插入用ABORT、更新显式update/upsert并有测试。
Room schema入库；首次无旧版本可以创建v1，以后版本必须显式迁移，禁止破坏性回退。

## 渠道边界
`localBeta`：不含网络/支付SDK，内测完整功能，applicationId后缀.beta，不可商业发布。
`play`：未接通时为UnavailableBilling，不解锁、不伪装成功；release被发布门禁阻止。
`domestic`：暂不创建多个厂商flavor；拿到一个真实渠道要求再实现一个适配器。
业务只认识 Entitlement，不直接依赖Google类；购买令牌不得进入成绩数据或导出文件。
正式一次性权益没有纯离线无代价的防盗保证。优先低复杂度平台实现，安全增强另见支付文档。

## 安全默认
Manifest没有INTERNET/相机/位置/存储权限；以后正式支付只在相应flavor添加必需项并审计最终合并Manifest。
Android系统备份采用显式排除，FileProvider仅开放cache/reports；exported=false。
不包含下载字体；使用系统字体并真机确认PDF中文。任何新素材需来源、许可、商业使用和再分发范围记录。
