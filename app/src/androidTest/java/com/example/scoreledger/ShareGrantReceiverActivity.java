package com.example.scoreledger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.ParcelFileDescriptor;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.MessageDigest;

/**
 * Runs under the test APK's distinct UID. Android/JDK-only Java is intentional:
 * standalone test-APK activities cannot borrow the target application's Kotlin runtime.
 */
public final class ShareGrantReceiverActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView label = new TextView(this);
        label.setText("Synthetic PDF permission boundary check");
        setContentView(label);
        final ResultReceiver receiver = getIntent().getParcelableExtra("resultReceiver");
        final Uri report = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        final Uri sibling = getIntent().getParcelableExtra("ungrantedSibling");
        final Uri outside = getIntent().getParcelableExtra("unmappedCache");
        if (receiver == null) throw new IllegalArgumentException("Missing controlled result receiver");
        new Thread(new Runnable() {
            @Override public void run() {
                final Bundle result = new Bundle();
                result.putInt("receiverUid", Process.myUid());
                try {
                    if (report == null || sibling == null || outside == null) {
                        throw new IllegalArgumentException("Missing synthetic probe URI");
                    }
                    byte[] bytes;
                    try (InputStream input = getContentResolver().openInputStream(report);
                         ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        if (input == null) throw new IllegalStateException("No report stream");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        bytes = output.toByteArray();
                    }
                    result.putInt("byteCount", bytes.length);
                    StringBuilder hex = new StringBuilder();
                    for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                        hex.append(Character.forDigit((value >>> 4) & 15, 16));
                        hex.append(Character.forDigit(value & 15, 16));
                    }
                    result.putString("sha256", hex.toString());
                    boolean writeDenied = false;
                    try (ParcelFileDescriptor ignored = getContentResolver().openFileDescriptor(report, "rw")) {
                        // Any successful open (even a null result) is not a permission denial.
                    } catch (SecurityException denied) { writeDenied = true; }
                    result.putBoolean("writeDenied", writeDenied);
                    result.putBoolean("siblingDenied", readDenied(sibling));
                    result.putBoolean("unmappedCacheDenied", readDenied(outside));
                } catch (Throwable failure) {
                    StringWriter trace = new StringWriter();
                    failure.printStackTrace(new PrintWriter(trace));
                    result.putString("failure", trace.toString());
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        receiver.send(RESULT_OK, result);
                        // Ending the receiving task releases its temporary activity URI grant.
                        finishAndRemoveTask();
                    }
                });
            }
        }, "synthetic-share-boundary").start();
    }

    private boolean readDenied(Uri uri) throws java.io.IOException {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) input.read();
            return false;
        } catch (SecurityException denied) { return true; }
    }
}
