# 数据契约与数据库

## 稳定规则
ID采用标准UUID小写字符串；名称不是主键。数据库版本与备份schemaVersion互相独立。
时间：考试日期为ISO LocalDate，不用UTC午夜替代；createdAt/updatedAt为UTC epoch milliseconds。
金额不在此模型；分数以百分之一分整数。JSON中整数不能被bool/浮点伪装。

## 表及关系
children(id PK,nickname,gradeLabel,archived,createdAt,updatedAt)
subjects(id PK,name,defaultMaxMinor,sortOrder,archived,createdAt,updatedAt)
exams(id PK,childId FK,title,examDate,kind,term,gradeSnapshot,note,createdAt,updatedAt)
scores(examId FK,subjectId FK,subjectNameSnapshot,status,scoreMinor NULL,maxMinor,PK(examId,subjectId))
草稿表 drafts(id PK,childId,payloadJson,updatedAt)，由D03添加；不会进入备份v1。
child删除由显式业务事务执行相关删除，不把误点父项默认为级联清空。考试删除可级联其scores。
科目归档不删历史；历史subjectNameSnapshot和maxMinor不被新的默认设置覆盖。

## 约束
名称/长度/数值限制见PRD。status三个枚举，kind为UNIT/MONTHLY/MIDTERM/FINAL/OTHER。
每条score有存在的exam/subject；每次考试至少1科、最多30科；同考试同科目禁止重复。
保存/编辑/导入都运行同一规则，不能只在键盘层校验。
数据库索引：exams(childId,examDate)、scores(subjectId)、subject名字业务重复提醒（不使用名字当FK）。

## 备份JSON
顶层严格字段：format="scoreledger-backup"、schemaVersion=1、exportedAt、children、subjects、exams、scores。
示例与机器可读规则：`contracts/backup-v1.schema.json`、`fixtures/backup-valid.json`。
不能带 entitlement、purchaseToken、isPro、apiKey、deviceId、absolutePath 或草稿。
完整JSON schema只验证局部结构；跨引用、主键、每考试科目计数和时间语义由语义验证器额外执行。
所有JSON重复键、非标准NaN/Infinity、超大对象、未知枚举、未知版本拒绝。先不支持宽松未来版本读取。

## 版本与迁移
备份v1与Room v1分别管理。添加字段先制定导入默认值；无法兼容时升备份版本并保留旧版导入器。
导入旧版不等于数据库迁移。Room schema首次构建导出后入库，不能手写伪造已验证schema。
本包尚未生成Room schema；由D00/D01完成并运行仪器化迁移测试。
