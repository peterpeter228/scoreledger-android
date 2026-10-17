package com.example.scoreledger.ui

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.R
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlin.math.roundToInt

@Composable fun RecordFilters(query: RecordQuery, change: (RecordQuery)->Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick={expanded=!expanded}) { Text(stringResource(R.string.filter_toggle)) }
    Text("${query.from.ifBlank { "不限开始" }} 至 ${query.through.ifBlank { "不限结束" }} · ${query.term.ifBlank { "全部学期" }} · ${query.kind?.label() ?: "全部类型"}")
    if(expanded) {
        OutlinedTextField(query.from,{change(query.copy(from=it))},label={Text(stringResource(R.string.ui_55))},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(query.through,{change(query.copy(through=it))},label={Text(stringResource(R.string.ui_56))},singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(query.term,{change(query.copy(term=it))},label={Text(stringResource(R.string.ui_57))},singleLine=true,modifier=Modifier.fillMaxWidth())
        KindPicker(query.kind,all=true){change(query.copy(kind=it))}
        TextButton(onClick={change(RecordQuery(query.childId))}) { Text(stringResource(R.string.ui_58)) }
    }
}
fun ExamWithScores.trendRecord() = TrendRecord(exam.id,exam.examDate,exam.createdAt,exam.title,
    ExamContext(exam.childId,exam.term,exam.gradeSnapshot,ExamKind.valueOf(exam.kind)),scores.map { it.toValue() })

@Composable fun TrendScreen(model: ScoreViewModel, selected: String?, records: List<ExamWithScores>, subjects: List<SubjectEntity>) {
    val sharedQuery by model.recordQuery.collectAsStateWithLifecycle()
    val query=sharedQuery.copy(childId=selected.orEmpty())
    var subjectId by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var raw by rememberSaveable { mutableStateOf(false) }
    var picked by remember(selected,subjectId,query) { mutableStateOf<Int?>(null) }
    val result=runCatching { query.apply(records) }
    val visible=result.getOrDefault(emptyList())
    val available=subjects.filter { s -> visible.any { r -> r.scores.any { it.subjectId==s.id } } }
    val subject=available.firstOrNull { it.id==subjectId } ?: available.firstOrNull()
    val points=subject?.let { TrendRules.points(visible.map { it.trendRecord() },selected.orEmpty(),it.id) }.orEmpty()
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.trend_title),style=MaterialTheme.typography.headlineMedium);RecordFilters(query,model::setQuery) }
        if(result.isFailure) item { Text(stringResource(R.string.ui_59),color=MaterialTheme.colorScheme.error) }
        if(points.isEmpty()) item { Text(stringResource(R.string.trend_empty)) }
        else {
            item {
                Box { OutlinedButton(onClick={menu=true}) { Text(subject?.name.orEmpty()) }
                    DropdownMenu(expanded=menu,onDismissRequest={menu=false}) { available.forEach { s -> DropdownMenuItem(text={Text(s.name)},onClick={subjectId=s.id;menu=false}) } } }
                Row { FilterChip(selected=!raw,onClick={raw=false},label={Text(stringResource(R.string.trend_rate))});Spacer(Modifier.width(8.dp));FilterChip(selected=raw,onClick={raw=true},label={Text(stringResource(R.string.trend_raw))}) }
                Text(if(raw) "原始分轴：0–${ScoreRules.formatPoints(points.mapNotNull { it.value?.maxMinor }.maxOrNull() ?: 10000)}，满分可能不同" else stringResource(R.string.trend_axis))
                TrendCanvas(points,raw) { picked=it }
                Text(stringResource(R.string.trend_scope_note))
                picked?.let { points.getOrNull(it) }?.let { Text(pointLabel(it,points.indexOf(it))) }
                val last=points.lastOrNull();val previous=points.dropLast(1).lastOrNull()
                if(last != null && previous != null) {
                    val change=TrendRules.change(previous,last)
                    Text(change?.let { "较所选上次记录：得分率变化 ${it.toPlainString()} 个百分点；原始分变化 ${java.math.BigDecimal.valueOf(requireNotNull(last.value?.scoreMinor)-requireNotNull(previous.value?.scoreMinor),2).stripTrailingZeros().toPlainString()} 分" } ?: "相邻记录缺失或考试范围不同，不显示变化。")
                    val compare=ScoreRules.canCompareTotals(previous.record.context,previous.record.scores,last.record.context,last.record.scores)
                    Text(if(compare.comparable) "两次总分口径一致，但不代表试卷难度相同。" else "两次总分不比较：${compare.reason}")
                }
                ReportControls(query,subject?.id)
                Text(stringResource(R.string.trend_values),style=MaterialTheme.typography.titleLarge)
            }
            itemsIndexed(points,key={_,p->p.record.id}) { index,point ->
                Card(Modifier.fillMaxWidth().semantics(mergeDescendants=true){}) { Column(Modifier.padding(16.dp)) { Text(pointLabel(point,index)) } }
            }
        }
    }
}
private fun pointLabel(point: TrendPoint,index: Int): String {
    val value=point.value
    val status=when(value?.status) { ScoreStatus.SCORED -> "${ScoreRules.formatPoints(requireNotNull(value.scoreMinor))}/${ScoreRules.formatPoints(value.maxMinor)} · ${point.rate?.toPlainString()}%";ScoreStatus.ABSENT -> "缺考 · 满分${ScoreRules.formatPoints(value.maxMinor)}";ScoreStatus.MISSING -> "未录入 · 满分${ScoreRules.formatPoints(value.maxMinor)}";null -> "本次未选此科" }
    return "${index+1}. ${point.record.date} · ${point.record.title}\n$status"
}
@Composable private fun TrendCanvas(points: List<TrendPoint>,raw: Boolean,pick: (Int)->Unit) {
    val color=MaterialTheme.colorScheme.primary;val grid=MaterialTheme.colorScheme.outlineVariant
    val description=stringResource(R.string.trend_chart_description)
    Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription=description }
        .pointerInput(points,raw) { detectTapGestures { offset -> val width=(size.width-32.dp.toPx()).coerceAtLeast(1f)
            pick(if(points.size==1) 0 else (((offset.x-16.dp.toPx())/width)*(points.size-1)).roundToInt().coerceIn(points.indices)) } }) {
        val margin=16.dp.toPx();val width=size.width-2*margin;val height=size.height-2*margin
        val maximum=if(raw) (points.mapNotNull { it.value?.maxMinor }.maxOrNull() ?: 10000).toFloat() else 100f
        fun position(index: Int,p: TrendPoint): Offset? {
            val value=if(raw) p.value?.scoreMinor?.toFloat() else p.rate?.toFloat()
            return value?.let { Offset(if(points.size==1) size.width/2 else margin+index*width/(points.size-1),margin+height*(1-it/maximum)) }
        }
        (0..4).forEach { val y=margin+height*it/4;drawLine(grid,Offset(margin,y),Offset(size.width-margin,y)) }
        points.forEachIndexed { i,p -> val at=position(i,p)
            if(at!=null) { if(i>0 && TrendRules.connects(points[i-1],p)) position(i-1,points[i-1])?.let { drawLine(color,it,at,2.dp.toPx()) };drawCircle(color,4.dp.toPx(),at) }
            else { val x=if(points.size==1) size.width/2 else margin+i*width/(points.size-1);drawLine(grid,Offset(x,margin),Offset(x,margin+height),1.dp.toPx()) }
        }
    }
}
