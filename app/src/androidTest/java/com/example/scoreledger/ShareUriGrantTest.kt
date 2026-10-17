package com.example.scoreledger

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.ResultReceiver
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ShareUriGrantTest {
    @Test fun separateUidReceivesOnlyExactReadGrantAndLosesItAfterTaskFinishes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val app=instrumentation.targetContext
        val isolated=IsolatedUiContext(app)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        // FileProvider maps the real app cache/reports. Only newly generated UUID synthetic
        // files enter that root; the repository is isolated and no existing file is removed.
        val manager=ReportManager(app,repository)
        val owned=mutableListOf<File>()
        val authority=app.packageName+".files"
        val evidence=File(app.filesDir,"ui-evidence/share-uri-grant-result.json")
        try {
            val data=instrumentation.context.assets.open("backup-valid.json").use(BackupCodec::read)
            runBlocking { repository.replace(PendingRestore(data,repository.restorePreview())){} }
            val baseline=runBlocking{repository.restorePreview()}
            val report=runBlocking{manager.generate(RecordQuery(data.children.single().id),ReportOptions(full=false))}
            owned+=report.file
            val expected=report.file.readBytes()
            val id=UUID.randomUUID().toString()
            val sibling=File(report.file.parentFile,"synthetic-ungranted-$id.pdf").apply{writeBytes("synthetic sibling must remain private".toByteArray())}
            val outside=File(app.cacheDir,"synthetic-unmapped-$id.txt").apply{writeText("synthetic outside-cache sentinel")}
            owned+=sibling;owned+=outside
            val siblingBytes=sibling.readBytes();val outsideBytes=outside.readBytes()
            try { FileProvider.getUriForFile(app,authority,outside);fail("Whole cache must not be mapped") }
            catch(_:IllegalArgumentException){}
            val siblingUri=FileProvider.getUriForFile(app,authority,sibling)
            val outsideUri=Uri.Builder().scheme("content").authority(authority).appendPath("reports").appendPath("..").appendPath(outside.name).build()
            val chooser=runBlocking{manager.shareIntent(report)}
            assertEquals(Intent.ACTION_CHOOSER,chooser.action)
            val send=requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            assertEquals(Intent.ACTION_SEND,send.action);assertEquals("application/pdf",send.type)
            val uri=requireNotNull(send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals("content",uri.scheme);assertEquals(authority,uri.authority)
            assertEquals(1,send.clipData!!.itemCount);assertEquals(uri,send.clipData!!.getItemAt(0).uri)
            assertEquals(uri,chooser.clipData!!.getItemAt(0).uri)
            val forbidden=Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            assertEquals(0,send.flags and forbidden);assertEquals(0,chooser.flags and forbidden)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            val receiverPackage=instrumentation.context.packageName
            val receiverUid=app.packageManager.getApplicationInfo(receiverPackage,0).uid
            assertNotEquals("The receiver MUST be another installed UID",Process.myUid(),receiverUid)
            assertEquals(PackageManager.PERMISSION_DENIED,app.checkUriPermission(uri,-1,receiverUid,Intent.FLAG_GRANT_READ_URI_PERMISSION))
            val response=AtomicReference<Bundle>()
            val latch=CountDownLatch(1)
            val callback=object:ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {
                    response.set(Bundle(resultData ?: Bundle()).apply { putInt("resultCode",resultCode) });latch.countDown()
                }
            }
            // Keep the application's actual SEND URI/ClipData/grants unchanged. Explicitly
            // route only the receiver selection; this is not a test of chooser UI/OEM apps.
            send.component=ComponentName(receiverPackage,ShareGrantReceiverActivity::class.java.name)
            send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            send.putExtra("resultReceiver",callback)
            send.putExtra("ungrantedSibling",siblingUri);send.putExtra("unmappedCache",outsideUri)
            app.startActivity(send)
            assertTrue("Separate-UID receiver must report actual read/access results",latch.await(15000,TimeUnit.MILLISECONDS))
            val result=requireNotNull(response.get())
            assertEquals(android.app.Activity.RESULT_OK,result.getInt("resultCode"))
            assertEquals(receiverUid,result.getInt("receiverUid"))
            assertNull(result.getString("failure"))
            assertEquals(expected.size,result.getInt("byteCount"))
            val digest=MessageDigest.getInstance("SHA-256").digest(expected).joinToString(""){"%02x".format(it)}
            assertEquals(digest,result.getString("sha256"))
            assertTrue("Read-only grant must deny rw access",result.getBoolean("writeDenied"))
            assertTrue("Sibling report without its own grant must be private",result.getBoolean("siblingDenied"))
            assertTrue("Unmapped cache path must be private",result.getBoolean("unmappedCacheDenied"))
            val deadline=SystemClock.uptimeMillis()+10000
            while(app.checkUriPermission(uri,-1,receiverUid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_DENIED && SystemClock.uptimeMillis()<deadline)SystemClock.sleep(100)
            assertEquals("Finished receiving task must lose temporary read grant",PackageManager.PERMISSION_DENIED,app.checkUriPermission(uri,-1,receiverUid,Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertArrayEquals(expected,report.file.readBytes());assertArrayEquals(siblingBytes,sibling.readBytes());assertArrayEquals(outsideBytes,outside.readBytes())
            assertEquals(baseline,runBlocking{repository.restorePreview()})
            evidence.parentFile!!.mkdirs()
            evidence.writeText(JSONObject().put("scope","actual cross-UID grant; explicit controlled test-APK receiver, not chooser UI")
                .put("senderUid",Process.myUid()).put("receiverUid",receiverUid).put("sha256",digest).put("byteCount",expected.size)
                .put("readExactBytes",true).put("writeDenied",true).put("siblingDenied",true).put("wholeCacheUnmapped",true)
                .put("temporaryGrantRevokedOnTaskFinish",true).put("databaseUnchanged",true).toString(2))
        } catch(failure:Throwable) {
            runCatching { File(app.filesDir,"ui-evidence/share-uri-grant-failure.txt").apply{parentFile!!.mkdirs();writeText(failure.stackTraceToString())} }
            throw failure
        } finally { owned.forEach{it.delete()};db.close();isolated.cleanup() }
    }
}
