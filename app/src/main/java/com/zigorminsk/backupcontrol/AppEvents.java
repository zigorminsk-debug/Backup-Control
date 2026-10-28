package com.zigorminsk.backupcontrol;

import android.content.Context;
import android.content.Intent;

final class AppEvents {
    static final String DATA_CHANGED = "com.zigorminsk.backupcontrol.DATA_CHANGED";
    private AppEvents() { }
    static void changed(Context context) {
        context.sendBroadcast(new Intent(DATA_CHANGED).setPackage(context.getPackageName()));
    }
}
