# PDF 报告规格

## 实现路线
Android `PdfDocument` 与Canvas按页绘制，文本用Paint/StaticLayout测量换行 [S11]。
A4纵向595×842pt、左右36pt、顶部40pt、底部40pt；正文最小10pt，页脚8pt。
系统中文字体，不附送字体文件；设备缺字时明确待修，不宣称所有阅读器一定兼容。
Canvas文本/线条优先；不要大位图截图，不把Canvas生成等同于已验证可搜索文本/可访问标记PDF。

## 内容
第1页：标题、昵称或“孩子A”、日期范围与筛选；摘要（记录数、有效成绩数、缺考/缺失数）；
单科趋势（专业模板）与口径警示；基础版直接进入明细。
明细页：考试日期/名称、科目、分数、满分、得分率、状态；按实际高度分页，跨页重复表头。
最后可选空白家长备注区域；不生成孩子能力评价，不强制营销二维码。
页脚：第x/y页、生成时间、产品名；原始数据不被收费水印遮挡。
空数据：不导出空白成功文件，提示选择记录；只有一条成绩：画点不画假趋势。

## 一页摘要不等于一页全部数据
10条记录能装一页就一页；多数据分页，绝不能截断、无限缩字、漏行。
报告每个数字来自core与一次数据快照，图表和明细必须同口径。
导出前支持隐藏昵称/备注；默认文件名无身份信息；提醒分享给他人后无法控制其转发。

## 存储/分享
先写cache/reports，成功后通过CreateDocument保存或FileProvider content URI分享；
FileProvider只开放reports子目录，不开放files/root-path，设exported=false [S12]。
ACTION_SEND + application/pdf + ClipData + FLAG_GRANT_READ_URI_PERMISSION；使用系统Chooser。
没有目标App时仍可保存。取消或权限撤销返回友好提示，不显示“导出成功”。
清理过期临时文件（24h；仍在使用时合理保留）；不能分享后立即删除导致接收方拿不到文件。

## 验收证据
保存至少：零/一/多页、长中文、20字符昵称、30科、缺考/零分/满分变化、2种阅读器打开、
实际微信等系统分享目标、A4打印预览截图。计算报告总行数与fixture对应，查漏行/分页重复。
当前本包仅给规格，未提供已执行的Android PDF渲染结果，不能标此模块完成。

2026-10-17源码增量：PdfDocument+StaticLayout两遍分页（正文11pt、页脚8pt），原始行source ID唯一覆盖与坐标边界检查；原生系统阅读器预览、SAF保存、FileProvider只读分享/ClipData及24h缓存清理。已写PdfRenderer仪器化测试，输出synthetic-report.pdf和first-page.png到测试App外部私有pdf-evidence子目录。尚未执行真实渲染/中文截图、两种阅读器/微信分享/打印验收，不宣称已通过。
