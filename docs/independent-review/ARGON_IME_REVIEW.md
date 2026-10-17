# Argon IME hypotheses review

基于审查，测试序列与宿主配置在 API 36 下引发了输入连接与窗口 Insets 动画的竞争。以下是 3 个可证伪根因及最小诊断建议：

**1. 语义滚动与语义文本替换导致输入会话竞争，且已聚焦节点忽略后续点击**
* **机制**：`item(...).performTextReplacement("８５．５")` 内部先执行 `performScrollToNode` 和 `performScrollTo`（纯语义动作，不隐藏旧键盘），紧接着通过语义 `RequestFocus` + `SetText` 切换焦点并把全角字符直接注入并归一化为 `85.5`。在 API 36 中，语义焦点切换与高频文本状态重组的组合会导致 `InputMethodManager` 在 `ATTACH_NEW_INPUT` 阶段超时，并取消正在进行的 `PHASE_CLIENT_APPLY_ANIMATION`。而第二轮追加的 `item(...).performTouchInput { click() }` 不仅再次触发了两次语义滚动，且因为节点已处于 Focused 状态，Compose 不会重新发起 `startInput` 或 `showSoftInput` 请求。
* **最小诊断**：对得分框移除 `performTextReplacement`，改为先直接真实点击 `performTouchInput { click() }` 获取焦点，验证此时 IME 是否正常弹出；或对比 `RecreationHostActivity` 通过测试中是否使用了相同的滚动+语义替换序列。

**2. `MainActivity` 独有的 `enableEdgeToEdge()` 改变 API 36 的 `WindowInsetsAnimation` 调度**
* **机制**：`MainActivity` 调用了 `enableEdgeToEdge()`，将窗口设为 `decorFitsSystemWindows = false`，使 IME 显示完全接管于客户端 `BY_INSETS_API` 动画流水线；若隔离的 `RecreationHostActivity` 未调用此方法，则走传统窗口调整路径。在 API 36 下，当焦点从文本键盘（年级）瞬间切向数字键盘（得分）且伴随列表滚动布局变化时，Edge-to-Edge 的 Insets 动画控制权更容易在 `PHASE_CLIENT_APPLY_ANIMATION` 被系统判定冲突而取消。
* **最小诊断**：在测试诊断分支中临时注释 `MainActivity` 的 `enableEdgeToEdge()`（或在 `RecreationHostActivity` 中加上它），观察两者的 `PHASE_CLIENT_APPLY_ANIMATION` 取消现象是否随之反转。

**3. 跨页面前置旅程残留的 IME 状态与键盘类型切换冲突**
* **机制**：不同于隔离测试，`MainActivity` 测试在进入记录页前，先在“创建孩子档案”页打开过软键盘，随后连点“保存”、“返回”等语义按钮直接切页，未等待前一页面的 IME 窗口彻底销毁或隐藏动画结束，导致后续从“本次年级”切到“得分”时 IME 服务端状态机仍处于过渡态。
* **最小诊断**：在点击“修改考试信息”展开元数据前，记录 `uiAutomation.windows` 中 `TYPE_INPUT_METHOD` 窗口是否仍残留可见，以及进入“得分”框前 `InputMethodManager.isActive` 的目标 View 状态。

Root correction: The prompt reports Synthetic tests passed, but subsequent screenshot review showed no actual keyboard. Those previous inset-only assertions were insufficient. Treat suggestions as hypotheses, not runtime evidence.

Independent primary-source correction: PHASE_CLIENT_APPLY_ANIMATION cancellation alone is not a failure: AOSP InsetsController.show intentionally cancels/no-ops when alreadyVisible or alreadyAnimatingShow. Therefore Argon's asserted race mechanism is unproven, and logging such cancellation does not establish root cause. See https://android.googlesource.com/platform/frameworks/base/+/15f961b1ef43582224983c4b0a4733f29b5d403d/core/java/android/view/InsetsController.java . Keep actual surface/window/positive-height evidence decisive.
