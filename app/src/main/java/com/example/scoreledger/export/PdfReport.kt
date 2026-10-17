package com.example.scoreledger.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import java.io.OutputStream
import java.time.Instant

/** Native A4 text layout. No web screenshot, bitmap page, interpolation, or guessed conclusions. */
data class ReportOptions(val anonymous:Boolean=true,val includeNotes:Boolean=false,val full:Boolean=false,val subjectId:String?=null,val examIds:Set<String>?=null)
data class ReportBlock(val text:String,val top:Float,val height:Float,val sourceId:String?=null,val trend:List<TrendPoint>?=null,val textGroup:Int=-1,val fontSize:Float=11f,val bold:Boolean=false,val cells:List<String>?=null)
data class ReportPage(val blocks:List<ReportBlock>)
data class ReportPlan(val pages:List<ReportPage>,val generatedAt:Instant,val scoreRows:Int) {
    val sourceIds get()=pages.flatMap{it.blocks}.mapNotNull{it.sourceId}
}
class ReportPageLimitException : IllegalArgumentException("所选范围超过200页，未创建PDF。请按考试分段，或缩小日期、学期、类型范围，也可不包含备注后重试；完整JSON备份和原始数据不受影响。")
object PdfReport {
    const val MAX_PAGES=200
    const val WIDTH=595;const val HEIGHT=842;const val LEFT=36;const val TOP=40;const val BOTTOM=780;const val TEXT_WIDTH=523
    private val columnWidths=listOf(175,140,108,100)
    private val columnTitles=listOf("科目", "分数 / 满分", "得分率", "状态")
    private fun paint(size:Float=11f,bold:Boolean=false)=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=size;color=Color.BLACK;typeface=Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL)
    }
    private fun layout(text:String,size:Float=11f,bold:Boolean=false,width:Int=TEXT_WIDTH)=StaticLayout.Builder.obtain(text,0,text.length,paint(size,bold),width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(1f,1f).build()
    private fun rowHeight(cells:List<String>,bold:Boolean=false)=cells.mapIndexed { index,text ->
        layout(text,bold=bold,width=columnWidths[index]-12).height
    }.maxOrNull()!!.toFloat()+8f
    fun plan(snapshot:DataSnapshot,query:RecordQuery,options:ReportOptions,generatedAt:Instant=Instant.now(),checkCancelled:()->Unit={}):ReportPlan {
        checkCancelled()
        val child=requireNotNull(snapshot.children.firstOrNull{it.id==query.childId}) { "请选择孩子" }
        val scores=snapshot.scores.groupBy{it.examId}
        val records=query.apply(snapshot.exams.map { checkCancelled();ExamWithScores(it,scores[it.id].orEmpty()) }).reversed().filter { options.examIds == null || it.exam.id in options.examIds }
        require(records.isNotEmpty()) { "当前筛选没有考试，请调整范围" }
        val pages=mutableListOf<ReportPage>();var blocks=mutableListOf<ReportBlock>();var y=TOP.toFloat();var nextTextGroup=0
        var activeExam:String?=null
        fun header() {
            val h=rowHeight(columnTitles,true)
            blocks.add(ReportBlock(columnTitles.joinToString(" · "),y,h,bold=true,cells=columnTitles));y+=h
        }
        fun newPage() {
            checkCancelled()
            if(blocks.isNotEmpty())pages.add(ReportPage(blocks.toList()))
            if(pages.size>=MAX_PAGES)throw ReportPageLimitException()
            blocks=mutableListOf();y=TOP.toFloat()
            activeExam?.let { caption ->
                val text="$caption（续）";val h=layout(text,14f,true).height.toFloat()
                blocks.add(ReportBlock(text,y,h,fontSize=14f,bold=true));y+=h+4
                header()
            }
        }
        fun add(text:String,size:Float=11f,bold:Boolean=false,gap:Float=5f) {
            checkCancelled()
            val group=nextTextGroup++
            var offset=0
            while(offset<text.length) {
                checkCancelled()
                val remaining=text.substring(offset);val measured=layout(remaining,size,bold);val available=BOTTOM-y
                if(measured.height<=available) {
                    blocks.add(ReportBlock(remaining,y,measured.height.toFloat(),textGroup=group,fontSize=size,bold=bold));y+=measured.height+gap;break
                }
                var lines=0
                while(lines<measured.lineCount && measured.getLineBottom(lines)<=available)lines++
                var end=0;var height=0
                // Remeasure prefixes: a trailing newline adds an empty line in standalone layout.
                while(lines>0) {
                    end=measured.getLineEnd(lines-1)
                    height=if(end>0)layout(remaining.substring(0,end),size,bold).height else 0
                    if(end>0 && height<=available)break
                    lines--
                }
                if(lines==0) {newPage();continue}
                blocks.add(ReportBlock(remaining.substring(0,end),y,height.toFloat(),textGroup=group,fontSize=size,bold=bold))
                offset+=end;y+=height+gap
                if(offset<text.length)newPage()
            }
        }
        fun row(cells:List<String>,source:String) {
            checkCancelled()
            val h=rowHeight(cells)
            require(h<=BOTTOM-TOP-150) { "单行内容过长，无法排版" }
            if(y+h>BOTTOM)newPage()
            blocks.add(ReportBlock(cells.joinToString(" · "),y,h,source,textGroup=nextTextGroup++,cells=cells));y+=h
        }
        add("分数记录 · ${if(options.full) "完整报告" else "基础明细报告"}",20f,true)
        add("${if(options.anonymous) "孩子A（匿名昵称）" else child.nickname}\n筛选：${query.from.ifBlank{"不限开始"}} 至 ${query.through.ifBlank{"不限结束"}}；学期：${query.term.ifBlank{"全部"}}；类型：${query.kind?.name ?: "全部"}${if(query.titleSearch.isNotBlank()) "；标题：${query.titleSearch}" else ""}${if(options.examIds!=null) "；指定考试${records.size}场" else ""}\n实际记录日期：${records.first().exam.examDate} 至 ${records.last().exam.examDate}\n生成时间：$generatedAt")
        val summary=ScoreRules.summarizeGroups(records.map { it.scores.map { row->row.toValue() } })
        val all=records.flatMap{it.scores};val absent=all.count{it.status=="ABSENT"};val missing=all.count{it.status=="MISSING"}
        add("范围摘要",14f,true)
        add("摘要：${records.size}次考试；${all.size}条科目记录\n有分数${summary.recordedCount}条，缺考${absent}条，未录入${missing}条；覆盖${summary.recordedCount}/${summary.selectedCount}条（${if(summary.isComplete) "完整" else "不完整"}）\n已录科目合计：${ScoreRules.formatPoints(summary.totalScoreMinor)}/${ScoreRules.formatPoints(summary.totalMaxMinor)}；得分率：${summary.ratePercent?.toPlainString()?.plus("%") ?: "暂无可计算成绩"}")
        add("统计口径：仅有分数的科目参与 sum(得分)÷sum(满分)，不是百分率的简单平均。0分有效；缺考和未录入不作0。跨考试汇总仅作本次范围描述，不表示同难度总分或能力。")
        if(options.full) {
            val subject=options.subjectId ?: all.first().subjectId
            val points=TrendRules.points(records.map { r -> TrendRecord(r.exam.id,r.exam.examDate,r.exam.createdAt,r.exam.title,ExamContext(r.exam.childId,r.exam.term,r.exam.gradeSnapshot,ExamKind.valueOf(r.exam.kind)),r.scores.map{it.toValue()}) },query.childId,subject)
            val name=all.firstOrNull{it.subjectId==subject}?.subjectNameSnapshot ?: "所选科目"
            add("单科趋势：$name",14f,true)
            add("得分率纵轴0–100%；横轴为考试顺序，同日不合并。缺失/未选科/范围变化处断线。原值见对应明细。")
            if(y+160>BOTTOM)newPage();blocks.add(ReportBlock("",y,160f,trend=points));y+=168
            add("趋势序号与日期对照",14f,true)
            points.forEachIndexed { index,point -> add("${index+1} · ${point.record.date} · ${point.record.title}",gap=2f) }
            add("试卷难度与范围可能不同，不作能力评级、预测或跨孩子比较。")
        }
        add("考试明细",14f,true)
        records.forEachIndexed { index,record ->
            checkCancelled()
            val e=record.exam;val total=ScoreRules.summarize(record.scores.map{it.toValue()})
            val caption="${index+1} · ${e.examDate} · ${e.title}"
            val kind=when(ExamKind.valueOf(e.kind)){ExamKind.UNIT->"单元";ExamKind.MONTHLY->"月考";ExamKind.MIDTERM->"期中";ExamKind.FINAL->"期末";ExamKind.OTHER->"其他"}
            val metadata="$kind · 学期${e.term.ifBlank{"未填"}} · 年级${e.gradeSnapshot.ifBlank{"未填"}} · 覆盖${total.recordedCount}/${total.selectedCount}科 · 得分率${total.ratePercent?.toPlainString()?.plus("%") ?: "暂无"}"
            val ordered=record.scores.sortedWith(compareBy<ScoreEntity>{it.subjectNameSnapshot}.thenBy{it.subjectId})
            fun cells(score:ScoreEntity):List<String> {
                val status=when(score.status){"SCORED"->"有分数";"ABSENT"->"缺考";else->"未录入"}
                val raw=score.scoreMinor?.let(ScoreRules::formatPoints) ?: "—"
                val rate=ScoreRules.percent(score.toValue())?.toPlainString()?.plus("%") ?: "—"
                return listOf(score.subjectNameSnapshot,"$raw / ${ScoreRules.formatPoints(score.maxMinor)}",rate,status)
            }
            // Keep each exam heading, table header, and first score together.
            activeExam=null
            val opening=layout(caption,14f,true).height+4+layout(metadata).height+4+rowHeight(columnTitles,true)+(ordered.firstOrNull()?.let{rowHeight(cells(it))} ?: 0f)
            if(y+opening>BOTTOM)newPage()
            add(caption,14f,true,4f);add(metadata,gap=4f)
            activeExam=caption;header()
            ordered.forEach { score -> row(cells(score),e.id+":"+score.subjectId) }
            if(options.includeNotes && e.note.isNotEmpty())add("原记录备注：${e.note}")
            activeExam=null;y+=8
        }
        if(options.full)add("家长手写备注（留白）\n\n\n\n")
        if(blocks.isNotEmpty())pages.add(ReportPage(blocks.toList()))
        if(pages.size>MAX_PAGES)throw ReportPageLimitException()
        checkCancelled()
        val result=ReportPlan(pages.toList(),generatedAt,all.size)
        check(result.sourceIds.size==all.size && result.sourceIds.toSet().size==all.size)
        check(result.pages.all{p->p.blocks.all{it.top>=TOP && it.top+it.height<=BOTTOM}})
        return result
    }
    fun write(plan:ReportPlan,output:OutputStream,checkCancelled:()->Unit={}) {
        checkCancelled()
        if(plan.pages.size>MAX_PAGES)throw ReportPageLimitException()
        require(plan.pages.isNotEmpty() && plan.scoreRows>0)
        val document=PdfDocument()
        try {
            plan.pages.forEachIndexed { index,page ->
                checkCancelled()
                val current=document.startPage(PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,index+1).create())
                try {
                page.blocks.forEach { b -> checkCancelled();val trendRows=b.trend
                    if(trendRows!=null)chart(current.canvas,trendRows,b.top,b.height) else if(b.cells!=null) {
                        drawRow(current.canvas,b)
                    } else {
                        current.canvas.save();current.canvas.translate(LEFT.toFloat(),b.top);layout(b.text,b.fontSize,b.bold).draw(current.canvas);current.canvas.restore()
                    }
                }
                val footer=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=8f;color=Color.DKGRAY}
                current.canvas.drawText("第${index+1}/${plan.pages.size}页 · 分数记录 · ${plan.generatedAt}",LEFT.toFloat(),HEIGHT-24f,footer)
                } finally { document.finishPage(current) }
                checkCancelled()
            }
            checkCancelled()
            document.writeTo(output)
            checkCancelled()
        } finally { document.close() }
    }
    private fun drawRow(canvas:Canvas,block:ReportBlock) {
        val cells=requireNotNull(block.cells)
        val rule=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(215,222,218);strokeWidth=0.5f}
        if(block.bold) {
            val fill=Paint().apply{color=Color.rgb(237,243,239)}
            canvas.drawRect(LEFT.toFloat(),block.top,(LEFT+TEXT_WIDTH).toFloat(),block.top+block.height,fill)
        }
        var x=LEFT.toFloat()
        cells.forEachIndexed { index,text ->
            canvas.save();canvas.translate(x+6,block.top+4)
            layout(text,block.fontSize,block.bold,columnWidths[index]-12).draw(canvas);canvas.restore()
            x+=columnWidths[index]
        }
        canvas.drawLine(LEFT.toFloat(),block.top+block.height,(LEFT+TEXT_WIDTH).toFloat(),block.top+block.height,rule)
    }
    private fun chart(canvas:Canvas,points:List<TrendPoint>,top:Float,height:Float) {
        val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(53,106,90);strokeWidth=1.5f}
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=10f}
        val x0=LEFT+30f;val width=TEXT_WIDTH-40f;val graphTop=top+8;val h=height-48
        (0..4).forEach { val y=graphTop+h*it/4;line.color=Color.LTGRAY;canvas.drawLine(x0,y,x0+width,y,line);canvas.drawText("${100-it*25}",LEFT.toFloat(),y+3,label) }
        fun x(i:Int)=if(points.size==1)x0+width/2 else x0+i*width/(points.size-1).coerceAtLeast(1)
        fun y(p:TrendPoint)=graphTop+h*(1-requireNotNull(p.rate).toFloat()/100)
        line.color=Color.rgb(53,106,90)
        points.forEachIndexed { i,p -> if(p.rate!=null) {
            if(i>0 && TrendRules.connects(points[i-1],p))canvas.drawLine(x(i-1),y(points[i-1]),x(i),y(p),line)
            canvas.drawCircle(x(i),y(p),2.5f,line)
        } }
        val tickStep=((points.size-1)/6+1).coerceAtLeast(1)
        points.indices.filter { it%tickStep==0 || it==points.lastIndex }.forEach { i ->
            val value="${i+1}"
            canvas.drawText(value,x(i)-label.measureText(value)/2,graphTop+h+15,label)
        }
        canvas.drawText("考试顺序 1–${points.size}（同日不合并）",x0,top+height-2,label)
    }
}
