package com.example.scoreledger.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable fun RecordFilters(query: RecordQuery, change: (RecordQuery)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var from by rememberSaveable(query) { mutableStateOf(query.from) }
    var through by rememberSaveable(query) { mutableStateOf(query.through) }
    var term by rememberSaveable(query) { mutableStateOf(query.term) }
    var search by rememberSaveable(query) { mutableStateOf(query.titleSearch) }
    var kind by rememberSaveable(query) { mutableStateOf(query.kind) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    TextButton(onClick={expanded=!expanded},modifier=Modifier.testTag("filter-toggle")) { Text(if(expanded) "收起筛选" else "搜索与筛选") }
    Text("已应用：${query.from.ifBlank { "不限开始" }} 至 ${query.through.ifBlank { "不限结束" }} · ${query.term.ifBlank { "全部学期" }} · ${query.kind?.label() ?: "全部类型"}"+(if(query.titleSearch.isNotBlank()) " · 名称含“${query.titleSearch}”" else ""),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
    if(expanded) {
        OutlinedTextField(search,{search=it},label={Text("搜索考试名称")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("filter-search"))
        FilterDateField(from,"开始日期",{from=it})
        FilterDateField(through,"结束日期",{through=it})
        OutlinedTextField(term,{term=it},label={Text("学期（留空为全部）")},singleLine=true,modifier=Modifier.fillMaxWidth())
        KindPicker(kind,all=true){kind=it}
        error?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
        Button(onClick={ val proposed=query.copy(from=from,through=through,term=term,kind=kind,titleSearch=search)
            try { proposed.apply(emptyList());change(proposed);error=null;expanded=false } catch(e:Exception) { error=e.message ?: "请检查日期" }
        },modifier=Modifier.testTag("apply-filters")) { Text("应用筛选") }
        TextButton(onClick={change(RecordQuery(query.childId));error=null;expanded=false}) { Text("清除筛选") }
    }
}
@Composable private fun FilterDateField(value:String,label:String,change:(String)->Unit) {
    val context=LocalContext.current
    OutlinedTextField(value,change,label={Text("$label YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth())
    TextButton(onClick={val date=runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
        DatePickerDialog(context,{_,y,m,d->change(LocalDate.of(y,m+1,d).toString())},date.year,date.monthValue-1,date.dayOfMonth).show()
    }) { Text("选择$label") }
}
fun ExamWithScores.trendRecord() = TrendRecord(exam.id,exam.examDate,exam.createdAt,exam.title,
    ExamContext(exam.childId,exam.term,exam.gradeSnapshot,ExamKind.valueOf(exam.kind)),scores.map { it.toValue() })

@Composable fun TrendScreen(model: ScoreViewModel, selected: String?, records: List<ExamWithScores>, subjects: List<SubjectEntity>,report:(String?)->Unit={}) {
    val sharedQuery by model.recordQuery.collectAsStateWithLifecycle()
    val query=sharedQuery.copy(childId=selected.orEmpty())
    var subjectId by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var all by rememberSaveable(selected) { mutableStateOf(false) }
    var pickedId by rememberSaveable(selected,subjectId,query,all) { mutableStateOf<String?>(null) }
    val visible=runCatching { query.apply(records) }.getOrDefault(emptyList())
    val available=subjects.filter { s -> visible.any { r -> r.scores.any { it.subjectId==s.id } } }
    val subject=available.firstOrNull { it.id==subjectId } ?: available.firstOrNull()
    val allPoints=subject?.let { TrendRules.points(visible.map { it.trendRecord() },selected.orEmpty(),it.id) }.orEmpty()
    val points=if(all) allPoints else allPoints.takeLast(12)
    val picked=points.indexOfFirst { it.record.id==pickedId }.takeIf { it>=0 } ?: points.lastIndex
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.testTag("trend-list")) {
        item { Text("回看变化",style=MaterialTheme.typography.headlineMedium);RecordFilters(query,model::setQuery) }
        if(points.isEmpty()) item { Text("当前范围还没有可展示的科目记录。试试清除筛选，或先记一次成绩。") }
        else {
            item {
                Box { OutlinedButton(onClick={menu=true},modifier=Modifier.testTag("trend-subject")) { Text(subject?.name.orEmpty()) }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) { available.forEach { s -> DropdownMenuItem(text={Text(s.name)},onClick={subjectId=s.id;menu=false}) } } }
                TextButton(onClick={all=!all}) { Text(if(all) "全部 ${points.size} 次 · 改看最近12次" else "最近12次 · 查看全部") }
                val point=points[picked]
                Text("${point.record.date} · ${point.record.title}",style=MaterialTheme.typography.titleMedium)
                Text(valueLabel(point),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("selected-trend-value"))
                Text(point.rate?.let { "得分率 ${it.toPlainString()}%" } ?: "这次没有可计算的得分率",color=MaterialTheme.colorScheme.primary)
                val previous=points.getOrNull(picked-1)
                if(previous!=null) {
                    val difference=TrendRules.change(previous,point)
                    Text(difference?.let { "比 ${previous.record.date}「${previous.record.title}」${if(it.signum()>0) "高" else if(it.signum()<0) "低" else "相同，变化"}${it.abs().toPlainString()} 个百分点" } ?: "与 ${previous.record.date}「${previous.record.title}」之间有缺录或范围变化，不显示变化。")
                }
                Text("满分相同，试卷难度仍可能不同。",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TrendCanvas(points,picked) { pickedId=points[it].record.id }
                Text("圆点为已录分数；缺考、未录入或未选此科处断线。点按图表或下方明细选择考试。",style=MaterialTheme.typography.bodyMedium)
                TextButton(onClick={report(subject?.id)},modifier=Modifier.testTag("open-trend-report")) { Text("导出这组记录") }
                Text("考试明细 · ${points.count { it.rate!=null }}次有分数",style=MaterialTheme.typography.titleLarge)
            }
            itemsIndexed(points.asReversed(),key={_,p->p.record.id}) { _,point ->
                OutlinedCard(onClick={pickedId=point.record.id},modifier=Modifier.fillMaxWidth().semantics(mergeDescendants=true){ this.selected=point.record.id==points[picked].record.id }) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("${point.record.date} · ${point.record.title}",style=MaterialTheme.typography.titleMedium)
                        Text(valueLabel(point)+(point.rate?.let { " · 得分率 ${it.toPlainString()}%" } ?: ""))
                    }
                }
            }
        }
    }
}
private fun valueLabel(point: TrendPoint):String = when(point.value?.status) {
    ScoreStatus.SCORED->"${ScoreRules.formatPoints(requireNotNull(point.value?.scoreMinor))} / ${ScoreRules.formatPoints(requireNotNull(point.value).maxMinor)}"
    ScoreStatus.ABSENT->"缺考"
    ScoreStatus.MISSING->"未录入"
    null->"本次未选此科"
}
@Composable private fun TrendCanvas(points: List<TrendPoint>,picked:Int,pick: (Int)->Unit) {
    val color=MaterialTheme.colorScheme.primary;val grid=MaterialTheme.colorScheme.outlineVariant
    Column {
        Row(Modifier.fillMaxWidth().height(220.dp)) {
            // Center each label on the same grid coordinate used by the Canvas, including endpoints.
            Layout(modifier=Modifier.fillMaxHeight().widthIn(min=36.dp),content={
                listOf("100","75","50","25","0").forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
            }) { measurables,constraints ->
                val labels=measurables.map { it.measure(constraints.copy(minWidth=0,minHeight=0)) }
                val width=maxOf(constraints.minWidth,labels.maxOf { it.width })
                val height=constraints.maxHeight
                val margin=16.dp.roundToPx()
                layout(width,height) { labels.forEachIndexed { index,label ->
                    label.placeRelative(0,margin+(height-2*margin)*index/4-label.height/2)
                } }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().semantics { contentDescription="得分率趋势，纵轴0至100。可通过下方考试明细选择并读取每个点。" }
                .pointerInput(points) { detectTapGestures { offset -> val width=(size.width-32.dp.toPx()).coerceAtLeast(1f)
                    pick(if(points.size==1) 0 else (((offset.x-16.dp.toPx())/width)*(points.size-1)).roundToInt().coerceIn(points.indices)) } }) {
                val margin=16.dp.toPx();val width=size.width-2*margin;val height=size.height-2*margin
                fun position(index:Int,p:TrendPoint):Offset?=p.rate?.toFloat()?.let { Offset(if(points.size==1)size.width/2 else margin+index*width/(points.size-1),margin+height*(1-it/100f)) }
                (0..4).forEach { val y=margin+height*it/4;drawLine(grid,Offset(margin,y),Offset(size.width-margin,y)) }
                points.forEachIndexed { i,p -> val at=position(i,p)
                    if(at!=null) { if(i>0 && TrendRules.connects(points[i-1],p)) position(i-1,points[i-1])?.let { drawLine(color,it,at,2.dp.toPx()) };if(i==picked)drawCircle(color.copy(alpha=0.2f),9.dp.toPx(),at);drawCircle(color,if(i==picked)5.dp.toPx() else 3.dp.toPx(),at) }
                    else { val x=if(points.size==1)size.width/2 else margin+i*width/(points.size-1);drawLine(grid,Offset(x,margin),Offset(x,margin+height),1.dp.toPx()) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(points.first().record.date.drop(5),style=MaterialTheme.typography.bodyMedium)
            if(points.size>2)Text(points[points.size/2].record.date.drop(5),style=MaterialTheme.typography.bodyMedium)
            if(points.size>1)Text(points.last().record.date.drop(5),style=MaterialTheme.typography.bodyMedium)
        }
    }
}
