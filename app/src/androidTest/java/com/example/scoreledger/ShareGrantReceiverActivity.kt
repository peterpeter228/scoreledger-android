package com.example.scoreledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.ResultReceiver
import android.widget.TextView
import java.security.MessageDigest

/** Runs in the installed test APK's distinct UID, not the instrumented application UID. */
class ShareGrantReceiverActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text="Synthetic PDF permission boundary check" })
        val receiver=requireNotNull(intent.getParcelableExtra<ResultReceiver>("resultReceiver"))
        val report=requireNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val sibling=requireNotNull(intent.getParcelableExtra<Uri>("ungrantedSibling"))
        val outside=requireNotNull(intent.getParcelableExtra<Uri>("unmappedCache"))
        Thread {
            val result=Bundle().apply { putInt("receiverUid",Process.myUid()) }
            try {
                val bytes=requireNotNull(contentResolver.openInputStream(report)).use { it.readBytes() }
                result.putInt("byteCount",bytes.size)
                result.putString("sha256",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)})
                result.putBoolean("writeDenied",denied { contentResolver.openFileDescriptor(report,"rw")?.use{} })
                result.putBoolean("siblingDenied",denied { contentResolver.openInputStream(sibling)?.use{it.read()} })
                result.putBoolean("unmappedCacheDenied",denied { contentResolver.openInputStream(outside)?.use{it.read()} })
            } catch(failure:Throwable) { result.putString("failure",failure.stackTraceToString()) }
            runOnUiThread {
                receiver.send(RESULT_OK,result)
                // Ending the receiving task releases the activity-scoped temporary URI grant.
                finishAndRemoveTask()
            }
        }.start()
    }
    private fun denied(operation:()->Unit):Boolean = try { operation();false } catch(_:SecurityException) { true }
}
