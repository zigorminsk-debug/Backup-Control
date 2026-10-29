package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * The dialog shown when the reminder is swiped away: it asks how long to
 * postpone. Android may refuse to open a window from a background receiver, in
 * which case the notification with the same options stays visible instead.
 */
public final class SnoozeActivity extends Activity {
    static void open(Context context) {
        try {
            context.startActivity(new Intent(context, SnoozeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (RuntimeException ignored) {
            // The prompt notification remains as the fallback.
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true);
        String[] options = new String[Notifications.SNOOZE_MINUTES.length + 1];
        for (int i = 0; i < Notifications.SNOOZE_MINUTES.length; i++) {
            options[i] = "Отложить на " + Notifications.label(Notifications.SNOOZE_MINUTES[i]);
        }
        options[options.length - 1] = "Не напоминать на этой неделе";
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Отложить напоминание?")
                .setItems(options, (ignored, which) -> {
                    if (which == options.length - 1) {
                        ReminderScheduler.cancelSnooze(this);
                        toast("Больше не напомним на этой неделе");
                    } else {
                        int minutes = Notifications.SNOOZE_MINUTES[which];
                        ReminderScheduler.snooze(this, minutes);
                        toast("Напомним через " + Notifications.label(minutes));
                    }
                    finish();
                })
                .setOnCancelListener(ignored -> finish())
                .create();
        dialog.setOnDismissListener(ignored -> Notifications.cancelSnoozePrompt(this));
        dialog.show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
