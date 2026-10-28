package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import java.io.File;

/** Debug-only device test for the exact verifier used by the in-app updater. */
public final class UpdateVerificationInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("External test directory unavailable");
            File trusted = new File(dir, "trusted-update.apk");
            File untrusted = new File(dir, "untrusted-update.apk");
            int nextVersion = BuildConfig.VERSION_CODE + 1;
            boolean acceptsSameSigner = UpdateManager.validApk(context, trusted, nextVersion);
            boolean rejectsDifferentSigner = !UpdateManager.validApk(context, untrusted, nextVersion);
            boolean rejectsWrongVersion = !UpdateManager.validApk(context, trusted, BuildConfig.VERSION_CODE);
            boolean ok = acceptsSameSigner && rejectsDifferentSigner && rejectsWrongVersion;
            result.putString("signature_check", ok ? "PASS" : "FAIL");
            result.putString("details", "same=" + acceptsSameSigner + ", different=" + rejectsDifferentSigner
                    + ", version=" + rejectsWrongVersion);
            finish(ok ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
        } catch (Exception e) {
            result.putString("signature_check", "FAIL: " + e);
            finish(Activity.RESULT_CANCELED, result);
        }
    }
}
