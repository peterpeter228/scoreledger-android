基于所提供的3个源码文件，发现以下4个可由代码直接证明的高风险Bug，以及2项因缺少外部文件需核查的边界：

### 1. 进程死亡（Process Death）后正在编辑的草稿状态完全丢失
* **文件/函数**：`ScoreViewModel.kt` / `draft` 属性声明、`openNew()`、`openDraft()`、`edit()`、`startFirstRecord()`、`closeEditor()`、`submit()`
* **具体输入或时序**：
  1. 用户调用 `openDraft` 或 `edit` 进入成绩编辑页，并修改了部分未提交内容（甚至某次自动保存刚失败处于 `Error` 状态）。
  2. 应用退到后台触发系统 Process Death。
  3. 用户切回应用，`ScoreViewModel` 通过 `SavedStateHandle` 重建。此时 `selectedChild`、`queries`、`savedRecordId` 均从 `savedState` 恢复，但 `draft` 仅初始化为 `MutableStateFlow<EntryDraft?>(null)`，未读写 `savedState`，导致编辑页直接关闭/丢失上下文。
* **最小修复**：
  在打开/变更草稿时将当前 `(draftId, childId)` 存入 `savedState["activeDraft"]`（关闭/提交/generation变更时清除）；在 `init` 中若检测到非空键值，向 `operations` 投递恢复任务读取 `repository.draft(id, childId)` 并恢复 `draft`、`persisted` 和 `editorGeneration`。
* **回归断言**：
  ```kotlin
  val handle = SavedStateHandle()
  val vm1 = ScoreViewModel(repo, handle)
  vm1.openNew() // 等待队列执行完毕
  val draftId = vm1.editor.value!!.id
  val vm2 = ScoreViewModel(repo, handle) // 模拟进程重建
  assertEquals(draftId, vm2.editor.value?.id)
  ```

### 2. 删除孩子或数据变更切换 `selectedChild` 时，未校验或清理 `savedRecordId`
* **文件/函数**：`ScoreViewModel.kt` / `init`（`repository.children.collect` 收集器）及 `deleteChild()`
* **具体输入或时序**：
  1. 用户为孩子 A 保存成绩，`submit()` 设置 `savedId.value = d.id` 及 `savedState["savedRecordId"] = d.id`。
  2. 在 UI 消费该导航事件前（或进程重建恢复 `savedRecordId` 时），孩子 A 被删除，或列表更新导致 `selected.value` 自动回退切换到孩子 B。
  3. 此时 `selectedChild` 已变为孩子 B，但 `savedRecordId` 仍保留着孩子 A 的记录 ID，导致结果导航指向错误的孩子上下文或找不到记录。
* **最小修复**：
  在 `selected` 发生变更时若非新创建的孩子上下文，清理未消费的 `savedRecordId`：
  ```kotlin
  viewModelScope.launch { selected.collect { 
      if (savedState.get<String>("selectedChild") != null && savedState.get<String>("selectedChild") != it) consumeSavedRecord()
      savedState["selectedChild"] = it 
  } }
  ```
* **回归断言**：
  ```kotlin
  vm.submit() // 生成孩子A的 savedRecordId
  assertNotNull(vm.savedRecordId.value)
  vm.deleteChild(childA.id) {}
  assertNull(vm.savedRecordId.value)
  ```

### 3. `closeEditor` / `submit` / `reloadDraft` 后未清理残留的 `conflict` 状态与 `editorGeneration`
* **文件/函数**：`ScoreViewModel.kt` / `closeEditor()`、`submit()`、`reloadDraft()`
* **具体输入或时序**：
  1. 用户编辑草稿时触发并发冲突，`operations` 捕获 `DraftConflictException`，将 `conflict.value = true`、`draftState.value = DraftSaveState.Conflict`。
  2. 用户选择调用 `reloadDraft()` 重新加载最新草稿，或调用 `closeEditor(discard = true)` 放弃修改退出。
  3. `reloadDraft()` 仅调用了 `markPersisted(...)`（内部将 `conflict` 置为 `false`），但 `closeEditor()` 和 `submit()` 仅重置了 `draft.value = null; persisted.value = null; draftState.value = DraftSaveState.Saved`，**漏掉了 `conflict.value = false` 和 `editorGeneration = null`**。`draftConflict` 依然保持为 `true`。
* **最小修复**：
  在 `closeEditor()` 和 `submit()` 状态清理处补充：
  ```kotlin
  conflict.value = false; editorGeneration = null
  ```
* **回归断言**：
  ```kotlin
  // 模拟触发 DraftConflictException 使 vm.draftConflict.value == true
  vm.closeEditor(discard = true)
  assertFalse(vm.draftConflict.value)
  ```

### 4. `FileOperationModel` 在系统选择器开启期间（`PICKING`）遭遇进程死亡，留下永久锁死状态或丢失中断状态
* **文件/函数**：`FileOperationModel.kt` / `pick()`、`initialize()`
* **具体输入或时序**：
  1. **时序 A（`run` 协程取消误清 Journal）**：`run()` 启动文件写入并写入 `journal ("active")`；若用户直接关闭 Activity 导致 `viewModelScope` 取消，`catch(CancellationException)` 抛出后进入 `finally`，直接执行了 `journal?.edit()?.remove("active")?.commit()`。如果写入在半途因作用域取消而中断，下次启动 `initialize` 将无法感知中断，不会提示 `interrupted()`。
  2. **时序 B（`invalidateReport` 未清理过期挂起恢复）**：当底层数据变更或切换上下文调用 `invalidateReport(key)` 时，只清空了 `report = null`，未清理可能过期的 `pending`（`PendingRestore`）和 `baseline`。
* **最小修复**：
  1. 在 `run()` 中区分正常完成与取消，若因 `CancellationException` 退出且非正常结束，保留 `journal` 的 `"active"` 标记或直接置位提示：
  ```kotlin
  var completed = false
  try { block(); completed = true } catch(e: CancellationException) { throw e }
  catch(e: Exception) { completed = true; pending=null; baseline=null; message=fileFailureMessage(e) }
  finally { if(requestId==id) { stage=FileStage.IDLE; requestId=null; if(completed) journal?.edit()?.remove("active")?.commit() } }
  ```
* **回归断言**：
  ```kotlin
  // 启动一个挂起的 run(FileStage.WRITING) 任务后立即 cancel viewModelScope
  // 重新创建 FileOperationModel 并调用 initialize(ctx, key)
  assertTrue(newModel.message.contains("上次操作被中断"))
  ```

---

### 需核查项（无法仅凭当前3个文件证明，不认定为Bug）
1. **CAS 在放弃草稿时的行为**：`closeEditor(discard = true)` 将 `persisted.value` 传给 `repository.discardDraft(d.id, d.childId, persisted.value)`。若此前某次 `change()` 自动保存失败或冲突导致 `persisted.value` 与数据库不一致，用户点击“放弃修改”是否会因为 CAS 校验失败而抛异常无法退出编辑器？需核查 `ScoreRepository.discardDraft` 实现。
2. **文件恢复结果与 `report` 缓存失效联动**：`StarterScreen` 如何在 `repository.generation` 变化时调用 `FileOperationModel.invalidateReport` 需核查 `StarterScreen.kt`。

---

### 审查结论
**必须先修（不可直接继续测试）**。
上述 Bug 1（Process Death 丢失草稿上下文）、Bug 2（跨孩子残留 `savedRecordId` 导航状态）、Bug 3（关闭编辑器残留 `draftConflict = true`）以及 Bug 4（协程取消误删中断日志）均属于重点考察的状态恢复、编辑切换与文件取消范畴，且修复改动极小、边界清晰，建议完成上述最小修复并补充对应回归断言后再进入下一阶段测试。