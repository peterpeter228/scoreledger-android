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
data class ReportOptions(val anonymous:Boolean=true,val includeNotes:Boolean=false,val full:Boolean=false,val subjectId:String?=null)
data class ReportBlock(val text:String,val top:Float,val height:Float,val sourceId:String?=null,val trend:List<TrendPoint>?=null,val textGroup:Int=-1)
data class ReportPage(val blocks:List<ReportBlock>)
data class ReportPlan(val pages:List<ReportPage>,val generatedAt:Instant,val scoreRows:Int) {
    val sourceIds get()=pages.flatMap{it.blocks}.mapNotNull{it.sourceId}
}
object PdfReport {
    const val WIDTH=595;const val HEIGHT=842;const val LEFT=36;const val TOP=40;const val BOTTOM=780;const val TEXT_WIDTH=523
    private fun paint()=TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=11f;color=Color.BLACK;typeface=Typeface.create("sans-serif",Typeface.NORMAL) }
    private fun layout(text:String)=StaticLayout.Builder.obtain(text,0,text.length,paint(),TEXT_WIDTH)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).setLineSpacing(2f,1f).build()
    fun plan(snapshot:DataSnapshot,query:RecordQuery,options:ReportOptions,generatedAt:Instant=Instant.now()):ReportPlan {
        val child=requireNotNull(snapshot.children.firstOrNull{it.id==query.childId}) { "请选择孩子" }
        val scores=snapshot.scores.groupBy{it.examId}
        val records=query.apply(snapshot.exams.map { ExamWithScores(it,scores[it.id].orEmpty()) }).reversed()
        require(records.isNotEmpty()) { "当前筛选没有考试，请调整范围" }
        val pages=mutableListOf<ReportPage>();var blocks=mutableListOf<ReportBlock>();var y=TOP.toFloat();var details=false;var nextTextGroup=0
        fun header() { val text="明细：日期／考试 · 科目 · 分数／满分 · 得分率 · 状态";val h=layout(text).height.toFloat();blocks.add(ReportBlock(text,y,h));y+=h+8 }
        fun newPage() { if(blocks.isNotEmpty())pages.add(ReportPage(blocks.toList()));blocks=mutableListOf();y=TOP.toFloat();if(details)header() }
        fun add(text:String,source:String?=null) {
            val group=nextTextGroup++
            // A score row is bounded by validated title/name lengths and carries its ID once.
            if(source!=null) {
                val h=layout(text).height.toFloat();require(h<=BOTTOM-TOP-70)
                if(y+h>BOTTOM)newPage()
                blocks.add(ReportBlock(text,y,h,source,textGroup=group));y+=h+8;return
            }
            var offset=0
            while(offset<text.length) {
                val remaining=text.substring(offset);val measured=layout(remaining);val available=BOTTOM-y
                if(measured.height<=available) {
                    blocks.add(ReportBlock(remaining,y,measured.height.toFloat(),textGroup=group));y+=measured.height+8;break
                }
                var lines=0
                while(lines<measured.lineCount && measured.getLineBottom(lines)<=available)lines++
                var end=0;var height=0
                // A trailing newline creates an extra empty line in a standalone layout;
                // remeasure the prefix, rather than assuming lineBottom is its final height.
                while(lines>0) {
                    end=measured.getLineEnd(lines-1)
                    height=if(end>0)layout(remaining.substring(0,end)).height else 0
                    if(end>0 && height<=available)break
                    lines--
                }
                if(lines==0) {newPage();continue}
                blocks.add(ReportBlock(remaining.substring(0,end),y,height.toFloat(),textGroup=group))
                offset+=end;y+=height+8
                if(offset<text.length)newPage()
            }
        }
        add("分数记录 · ${if(options.full) "完整报告（免费内测）" else "基础明细报告"}")
        add("${if(options.anonymous) "孩子A（匿名昵称）" else child.nickname}\n筛选：${query.from.ifBlank{"不限开始"}} 至 ${query.through.ifBlank{"不限结束"}}；学期：${query.term.ifBlank{"全部"}}；类型：${query.kind?.name ?: "全部"}\n实际记录日期：${records.first().exam.examDate} 至 ${records.last().exam.examDate}\n生成时间：$generatedAt")
        val summary=ScoreRules.summarizeGroups(records.map { it.scores.map { row->row.toValue() } })
        val all=records.flatMap{it.scores};val absent=all.count{it.status=="ABSENT"};val missing=all.count{it.status=="MISSING"}
        add("摘要：${records.size}次考试；${all.size}条科目记录\n有分数${summary.recordedCount}条，缺考${absent}条，未录入${missing}条\n覆盖${summary.recordedCount}/${summary.selectedCount}条（${if(summary.isComplete) "完整" else "不完整"}）\n已录科目合计：${ScoreRules.formatPoints(summary.totalScoreMinor)}/${ScoreRules.formatPoints(summary.totalMaxMinor)}；得分率：${summary.ratePercent?.toPlainString()?.plus("%") ?: "暂无可计算成绩"}")
        add("统计口径：仅有分数的科目参与 sum(得分)÷sum(满分)，不是百分率的简单平均。0分有效；缺考和未录入不作0。跨考试汇总仅作本次范围描述，不表示同难度总分或能力。")
        if(options.full) {
            val subject=options.subjectId ?: all.first().subjectId
            val points=TrendRules.points(records.map { r -> TrendRecord(r.exam.id,r.exam.examDate,r.exam.createdAt,r.exam.title,ExamContext(r.exam.childId,r.exam.term,r.exam.gradeSnapshot,ExamKind.valueOf(r.exam.kind)),r.scores.map{it.toValue()}) },query.childId,subject)
            val name=all.firstOrNull{it.subjectId==subject}?.subjectNameSnapshot ?: "所选科目"
            add("单科趋势：$name · 得分率纵轴固定0–100%；横轴为考试顺序，同日分别展示。缺失/未选科/范围变化处断线。原值见对应明细。")
            if(y+160>BOTTOM)newPage();blocks.add(ReportBlock("",y,160f,trend=points));y+=168
            add("试卷难度与范围可能不同，不作能力评级、预测或跨孩子比较。")
        }
        details=true
        if(y+80>BOTTOM)newPage() else header()
        records.forEach { record ->
            val e=record.exam;val total=ScoreRules.summarize(record.scores.map{it.toValue()})
            add("${e.examDate} · ${e.title}\n${ExamKind.valueOf(e.kind).let{when(it){ExamKind.UNIT->"单元";ExamKind.MONTHLY->"月考";ExamKind.MIDTERM->"期中";ExamKind.FINAL->"期末";ExamKind.OTHER->"其他"}}} · 学期${e.term.ifBlank{"未填"}} · 年级快照${e.gradeSnapshot.ifBlank{"未填"}}\n已录科目得分率${total.ratePercent?.toPlainString()?.plus("%") ?: "暂无"}；覆盖${total.recordedCount}/${total.selectedCount}科")
            record.scores.sortedWith(compareBy<ScoreEntity>{it.subjectNameSnapshot}.thenBy{it.subjectId}).forEach { score ->
                val status=when(score.status){"SCORED"->"有分数";"ABSENT"->"缺考";else->"未录入"}
                val raw=score.scoreMinor?.let(ScoreRules::formatPoints) ?: "—"
                val rate=ScoreRules.percent(score.toValue())?.toPlainString()?.plus("%") ?: "—"
                add("${e.examDate} · ${e.title}\n${score.subjectNameSnapshot}：$raw / ${ScoreRules.formatPoints(score.maxMinor)}；得分率$rate；$status",e.id+":"+score.subjectId)
            }
            if(options.includeNotes && e.note.isNotEmpty())add("原记录备注：${e.note}")
        }
        if(options.full)add("家长手写备注（留白）\n\n\n\n")
        if(blocks.isNotEmpty())pages.add(ReportPage(blocks.toList()))
        val result=ReportPlan(pages.toList(),generatedAt,all.size)
        check(result.sourceIds.size==all.size && result.sourceIds.toSet().size==all.size)
        check(result.pages.all{p->p.blocks.all{it.top>=TOP && it.top+it.height<=BOTTOM}})
        return result
    }
    fun write(plan:ReportPlan,output:OutputStream) {
        require(plan.pages.isNotEmpty() && plan.scoreRows>0)
        val document=PdfDocument()
        try {
            plan.pages.forEachIndexed { index,page ->
                val current=document.startPage(PdfDocument.PageInfo.Builder(WIDTH,HEIGHT,index+1).create())
                page.blocks.forEach { b -> val trendRows=b.trend
                    if(trendRows!=null)chart(current.canvas,trendRows,b.top,b.height) else {
                        current.canvas.save();current.canvas.translate(LEFT.toFloat(),b.top);layout(b.text).draw(current.canvas);current.canvas.restore()
                    }
                }
                val footer=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=8f;color=Color.DKGRAY}
                current.canvas.drawText("第${index+1}/${plan.pages.size}页 · 分数记录 · ${plan.generatedAt}",LEFT.toFloat(),HEIGHT-24f,footer)
                document.finishPage(current)
            }
            document.writeTo(output)
        } finally { document.close() }
    }
    private fun chart(canvas:Canvas,points:List<TrendPoint>,top:Float,height:Float) {
        val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(53,106,90);strokeWidth=1.5f}
        val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.DKGRAY;textSize=10f}
        val x0=LEFT+30f;val width=TEXT_WIDTH-40f;val graphTop=top+8;val h=height-30
        (0..4).forEach { val y=graphTop+h*it/4;line.color=Color.LTGRAY;canvas.drawLine(x0,y,x0+width,y,line);canvas.drawText("${100-it*25}",LEFT.toFloat(),y+3,label) }
        fun x(i:Int)=if(points.size==1)x0+width/2 else x0+i*width/(points.size-1).coerceAtLeast(1)
        fun y(p:TrendPoint)=graphTop+h*(1-requireNotNull(p.rate).toFloat()/100)
        line.color=Color.rgb(53,106,90)
        points.forEachIndexed { i,p -> if(p.rate!=null) {
            if(i>0 && TrendRules.connects(points[i-1],p))canvas.drawLine(x(i-1),y(points[i-1]),x(i),y(p),line)
            canvas.drawCircle(x(i),y(p),2.5f,line)
        } }
        canvas.drawText("考试顺序 1–${points.size}（同日不合并）",x0,top+height-2,label)
    }
}
