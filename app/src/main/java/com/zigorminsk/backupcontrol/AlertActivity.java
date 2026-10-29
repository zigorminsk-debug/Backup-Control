package com.zigorminsk.backupcontrol;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The reminder itself, shown full screen on a locked phone.
 *
 * When the phone is in use Android turns the same notification into a heads-up
 * banner; when it is locked a banner alone may never be seen, so this screen
 * takes over with the task list and one tap to confirm or postpone.
 */
public final class AlertActivity extends Activity {
    private static final int INK = 0xff10231f;
    private static final int MUTED = 0xff5d6f69;
    private static final int CANVAS = 0xfff2f7f4;
    private static final int GREEN_DARK = 0xff179774;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildContent());
    }

    @Override protected void onResume() {
        super.onResume();
        AlertPlayer.stop();
    }

    private ScrollView buildContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(CANVAS);
        int pad = dp(24);
        root.setPadding(pad, dp(48), pad, pad);

        TextView title = text("Пора проверить бэкапы", 24, INK, true);
        root.addView(title);

        BackupStore store = new BackupStore(this);
        int pending = 0;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (BackupItem item : store.items()) {
            if (item.checked) continue;
            pending++;
            String line = item.ips.isEmpty() ? item.name : item.name + "  ·  " + IpAddresses.join(item.ips);
            TextView row = text("•  " + line, 15, INK, false);
            row.setPadding(0, dp(10), 0, 0);
            list.addView(row);
        }
        TextView summary = text(pending == 0 ? "Все задачи проверены." : "Ожидают проверки: " + pending,
                14, MUTED, false);
        LinearLayout.LayoutParams sp = matchWrap();
        sp.topMargin = dp(8);
        root.addView(summary, sp);
        root.addView(list);

        Button open = new Button(this);
        open.setText("Открыть программу");
        open.setAllCaps(false);
        open.setOnClickListener(v -> {
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            finish();
        });
        LinearLayout.LayoutParams op = matchWrap();
        op.topMargin = dp(24);
        root.addView(open, op);

        TextView postponeLabel = text("ОТЛОЖИТЬ НА", 11, MUTED, true);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(24);
        root.addView(postponeLabel, lp);
        for (int minutes : Notifications.SNOOZE_MINUTES) {
            Button snooze = new Button(this);
            snooze.setText(Notifications.label(minutes));
            snooze.setAllCaps(false);
            snooze.setTextColor(GREEN_DARK);
            snooze.setOnClickListener(v -> {
                ReminderScheduler.snooze(this, minutes);
                finish();
            });
            LinearLayout.LayoutParams bp = matchWrap();
            bp.topMargin = dp(8);
            root.addView(snooze, bp);
        }
        Button never = new Button(this);
        never.setText("Не напоминать на этой неделе");
        never.setAllCaps(false);
        never.setOnClickListener(v -> {
            ReminderScheduler.cancelSnooze(this);
            finish();
        });
        LinearLayout.LayoutParams np = matchWrap();
        np.topMargin = dp(8);
        root.addView(never, np);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CANVAS);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.START);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
