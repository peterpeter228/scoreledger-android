经独立审查所给源码，定位到以下 **3个** 可复现高风险Bug（其余未提供源码的外部实现标为待核查）：

---

### Bug 1：取消导出副本后，`exportCopy` 残留导致后续导出主备份时静默变成导出旧副本
* **文件与函数**：`BackupSettings.kt` -> `rememberBackupSettings()` (`launchExport` 与 `export` 回调)
* **具体时序**：
  1. 用户点击某个历史副本的“导出副本”，调用 `launchExport(copy.id)`，此时 `exportCopy = copy.id`、`model.stage = PICKING`。
  2. 系统文件选择器打开期间发生进程重建（Process Death），`exportCopy` 与 `pickerToken` 由 `rememberSaveable` 恢复，但新建的 `FileOperationModel` 中 `stage == IDLE`、`busy == false`。
  3. 用户在文件选择器中点击取消（返回 `uri = null`）。
  4. `export` 回调执行：`!model.pickerFinished(token)` 为 `true`（因 `stage` 为 `IDLE`），但 `model.busy` 为 `false`，故未提前 `return`；然而若在 `busy == true`（例如恢复后触发了其他操作）时迟到回调触发 `return@rememberLauncherForActivityResult`，`exportCopy=null` 位于守卫语句之后未执行，`exportCopy` 残留为旧 `copy.id`。
  5. 更为直接的触发路径：调用 `launchExport(copy.id)` 时若 `model.pick()` 返回 `null` 之后逻辑被调整，或回调被守卫拦截，下次用户点击主按钮“导出备份”（调用 `launchExport(null)` 正常覆盖，但若通过其他入口或迟到回调交错），`exportCopy` 未与 `pickerToken` 同步清理。
* **最小修复**：在 `export` 回调最顶部同步消费并清空 `exportCopy`：
```kotlin
val token=pickerToken;pickerToken=null
val copy=exportCopy;exportCopy=null
if(token==null || (!model.pickerFinished(token) && model.busy))return@rememberLauncherForActivityResult
```
* **测试断言**：模拟 `exportCopy = "copy_1"` 且 `model.stage = GENERATING (busy=true)` 时收到旧 picker 回调，断言回调返回后 `exportCopy == null`。

---

### Bug 2：恢复操作（Restore）成功完成后，`baseline`（清空确认弹窗状态）未清理，可能基于过期基线误删新恢复的数据
* **文件与函数**：`BackupSettings.kt` -> `rememberBackupSettings()` / `FileOperationModel.kt`
* **具体时序**：
  1. 用户点击“清空数据”生成了 `model.baseline`（或因某次状态残留 `baseline != null`），或者在 `PendingRestore` 与 `RestoreBaseline` 状态交互中，用户先触发了 `baseline` 或 `pending`。
  2. 当用户在 `pending` 弹窗中确认执行 `manager.restore(preview)` 成功后，代码只执行了 `pending = null`，未将 `baseline = null`。此外，执行 `clearData(before)` 成功后也未清理 `pending = null` 和刷新 `model.copies`。
* **最小修复**：在 `restore` 与 `clearData` 成功时清理互斥的预检状态：
```kotlin
// restore 确认回调内：
manager.restore(preview);pending=null;baseline=null;model.copies=manager.listRollbacks()
// clearData 确认回调内：
manager.clearData(before);baseline=null;pending=null;model.copies=manager.listRollbacks()
```
* **测试断言**：设置 `model.baseline = mockBaseline` 后执行 `restore` 成功分支，断言完成后 `model.baseline == null` 且 `model.pending == null`。

---

### Bug 3：记录内容修改或清空后，`ReportControls` 的 `key` 未包含数据快照版本，导致继续预览/保存/分享旧 PDF 或展示过期考试范围
* **文件与函数**：`ReportControls.kt` -> `ReportControls(...)`
* **具体时序**：
  1. 用户在报告页生成了 PDF，此时 `key = listOf(query, examIds, anonymous, notes, full, selectedSubject, split, segmentStart, segmentEnd).toString()`，`model.report` 保存了已生成的 `ReportFile`，且 `scopeInfo` 缓存了当时的考试列表。
  2. 用户不改变 `query`（同一个孩子、同一时间范围）和 `examIds`（例如为 `null`），通过导航或分屏新增/修改/删除了某次考试成绩，或者在设置中执行了备份恢复/清空数据，随后返回/切回 `ReportControls`。
  3. 此时 `query` 与 `examIds` 引用相等，`LaunchedEffect(manager, query, examIds)` **不会重新执行**，`scopeInfo` 仍显示旧考试场次；同时 `key` 字符串完全未变，`SideEffect { model.invalidateReport(key) }` 不会使旧报告失效。
  4. `ready` 仍然非空，界面直接展示旧报告的“预览 / 保存 / 分享”按钮；若用户点击“按考试序号分段”，还会基于过期的 `scopeInfo.exams` 计算出错误的 `ids` 传给 `manager.generate`。
* **最小修复**：在 `scope` 返回的 `ReportScope` 中加入数据快照标识（或考试及成绩列表摘要），并在每次进入/重组时重新加载或将 `scopeInfo` 纳入 `key`：
```kotlin
// ReportManager.scope 中令 ReportScope 包含数据指纹（如 records.hashCode()），或直接将 scopeInfo 纳入 key：
val key=listOf(query,examIds,scopeInfo,anonymous,notes,full,selectedSubject,split,segmentStart,segmentEnd).toString()
```
* **测试断言**：保持 `query` 不变，修改底层仓库中的成绩数据并更新 `scopeInfo`，断言 `model.report` 被 `invalidateReport(key)` 置为 `null`，旧 PDF 的保存与分享按钮不再显示。

---

### 专项检查结论汇总（未发现问题项与待核查项）
1. **切孩子/范围更新时旧 PDF 是否继续保存/分享**：切换 `query`（含 `childId`、日期范围）或报告选项时，`key` 会立即改变并由 `SideEffect { model.invalidateReport(key) }` 同步清空 `model.report`，生成协程完成时也有 `if (model.reportKey == capturedKey)` 拦截；但**仅记录内容更新而 `query` 不变时存在上述 Bug 3**。此外，`LocalReportSaveState` 的具体实现未在给定文件中提供，**标为待核查**。
2. **系统选择器取消 / Activity 重建与进程死状态**：`FileOperationModel.pick()` 未写 `journal`，进程死亡重建后 `pickerToken` 虽恢复，但在 `export`/`restore` 回调中 `!model.pickerFinished(token) && model.busy` 允许非 busy 状态下继续处理选中的 `uri`，取消时则提示取消信息，未发现死锁。
3. **协程取消与 Journal**：`FileOperationModel.run` 在进入时同步 `commit()` 写入 `"active"`，抛出 `CancellationException` 时 `settled` 为 `false`，`finally` 中**保留** `"active"` journal，下次 `initialize` 或 `LaunchedEffect` 会正确触发 `interrupted()` 提示并清除 journal，符合预期，**未发现 Bug**。
4. **旧 token 重复使用**：`pickerToken` 在回调首行即被置为 `null`，且 `pickerFinished` 校验 `id == requestId` 后立即清空 `requestId`，无法重复消费，**未发现 Bug**。
5. **外部待核查项**：`LocalReportSaveState`、`NativeReportPreview`、`BackupManager`、`PdfReport` 源码未提供，标为**待核查**。