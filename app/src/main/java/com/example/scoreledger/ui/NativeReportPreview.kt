package com.example.scoreledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.scoreledger.R
import com.example.scoreledger.export.*
import kotlinx.coroutines.CancellationException

/** One bounded page bitmap at a time; renderer/file/IO never runs on the UI thread. */
@Composable fun NativeReportPreview(manager:ReportManager,report:ReportFile,close:()->Unit) {
    var index by remember(report) { mutableIntStateOf(0) }
    var page by remember(report) { mutableStateOf<RenderedReportPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(report,index) {
        loading=true;error=false;page=null
        try { page=manager.renderPage(report,index) }
        catch(e:CancellationException){throw e}
        catch(_:Exception){error=true}
        finally{loading=false}
    }
    // Previous bitmaps lose their only Compose reference when changing pages and are reclaimed
    // normally. Do not recycle an image that the render thread may still be displaying.
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_preview),style=MaterialTheme.typography.titleLarge)
                Text("第${index+1}/${report.pageCount}页 · ${report.scoreRows}条成绩",Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                TextButton(onClick=close,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_close_preview))}
                Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                    val rendered=page
                    when {
                        loading -> Text(stringResource(R.string.report_preview_loading))
                        error -> Text(stringResource(R.string.report_preview_error))
                        rendered!=null -> Image(rendered.bitmap.asImageBitmap(),"报告第${rendered.index+1}页图像；原值可在记录与趋势明细查看",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    }
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(onClick={index--},enabled=!loading && index>0,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_previous_page))}
                    OutlinedButton(onClick={index++},enabled=!loading && index+1<report.pageCount,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_next_page))}
                }
            }
        }
    }
}
