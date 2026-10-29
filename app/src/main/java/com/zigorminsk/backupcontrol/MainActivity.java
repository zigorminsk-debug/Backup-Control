package com.zigorminsk.backupcontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int NAVY = Color.rgb(20, 36, 58);
    private static final int NAVY_CARD = Color.rgb(35, 56, 78);
    private static final int INK = Color.rgb(34, 48, 58);
    private static final int MUTED = Color.rgb(109, 125, 128);
    private static final int GREEN = Color.rgb(23, 151, 116);
    private static final int GREEN_DARK = Color.rgb(12, 119, 89);
    private static final int MINT = Color.rgb(220, 245, 234);
    private static final int CANVAS = Color.rgb(244, 247, 246);
    private static final int BORDER = Color.rgb(228, 235, 232);
    private static final int AMBER_BG = Color.rgb(255, 247, 230);
    private static final int AMBER_TEXT = Color.rgb(142, 94, 28);
    private static final Locale RU = new Locale("ru", "RU");
    private static final int[] DAYS = {Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
            Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY};
    private static final String[] DAY_LABELS = {"Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота", "Воскресенье"};
    /** A task rarely covers more machines than this; keeps the dialog usable. */
    private static final int MAX_IPS = 8;

    private BackupStore store;
    private ScrollView scroll;
    private LinearLayout content;
    private View statusSpace;
    private View navSpace;
    private boolean pendingInstall;
    private boolean showingArchive;
    private boolean registered;
    private boolean resumed;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable cycleTick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (store.syncCycle()) render();
            scheduleCycleTick();
        }
    };
    private final Map<Integer, View> itemViews = new HashMap<>();
    private final Map<Long, View> archiveViews = new HashMap<>();
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            render();
            if (resumed) scheduleCycleTick();
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            pendingInstall = state.getBoolean("pendingInstall", false);
            showingArchive = state.getBoolean("showingArchive", false);
        }
        store = new BackupStore(this);
        Notifications.createChannels(this);
        buildShell();
        UpdateJobService.ensureScheduled(this);
        UpdateManager.checkAsync(this, false, message -> {
            if (!isFinishing() && !isDestroyed()) render();
        });
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(AppEvents.DATA_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(changes, filter);
        registered = true;
    }

    @Override protected void onStop() {
        if (registered) { unregisterReceiver(changes); registered = false; }
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ReminderScheduler.rescheduleAll(this);
        render();
        scheduleCycleTick();
        handleIntent(getIntent());
        if (pendingInstall) {
            pendingInstall = false;
            if (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) {
                launchInstaller();
            } else {
                toast("Разрешите установку обновлений в настройках Android.");
            }
        }
    }

    @Override protected void onPause() {
        resumed = false;
        uiHandler.removeCallbacks(cycleTick);
        super.onPause();
    }

    private void scheduleCycleTick() {
        uiHandler.removeCallbacks(cycleTick);
        long delay = Math.max(250, store.nextControlDayStart() - System.currentTimeMillis() + 250);
        uiHandler.postDelayed(cycleTick, delay);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("pendingInstall", pendingInstall);
        outState.putBoolean("showingArchive", showingArchive);
        super.onSaveInstanceState(outState);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        ReminderScheduler.rescheduleAll(this);
        render();
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("install_update", false)) {
            intent.removeExtra("install_update");
            scroll.post(this::showUpdateDialog);
        }
        int id = intent.getIntExtra("item_id", 0);
        boolean openError = intent.getBooleanExtra("open_error", false);
        if (id != 0) {
            intent.removeExtra("item_id");
            intent.removeExtra("open_error");
            BackupItem item = store.item(id);
            if (item == null) return;
            showingArchive = item.checked;
            scroll.scrollTo(0, 0);
            render();
            scroll.post(() -> {
                View view = item.checked ? archiveViews.get(item.currentArchiveId) : itemViews.get(id);
                if (view != null) {
                    Rect rect = new Rect();
                    view.getDrawingRect(rect);
                    content.offsetDescendantRectToMyCoords(view, rect);
                    scroll.smoothScrollTo(0, Math.max(0, rect.top - dp(16)));
                }
                // A reminder remains actionable after its task is archived.
                if (item.checked && openError) showReminder(item);
            });
        }
    }

    private void buildShell() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
        int visibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 26) visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        window.getDecorView().setSystemUiVisibility(visibility);

        LinearLayout root = vertical();
        root.setBackgroundColor(CANVAS);
        statusSpace = new View(this);
        statusSpace.setBackgroundColor(NAVY);
        root.addView(statusSpace, new LinearLayout.LayoutParams(-1, 0));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        content = vertical();
        content.setBackgroundColor(CANVAS);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        navSpace = new View(this);
        navSpace.setBackgroundColor(CANVAS);
        root.addView(navSpace, new LinearLayout.LayoutParams(-1, 0));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            LinearLayout.LayoutParams topParams = (LinearLayout.LayoutParams) statusSpace.getLayoutParams();
            LinearLayout.LayoutParams bottomParams = (LinearLayout.LayoutParams) navSpace.getLayoutParams();
            if (topParams.height != top || bottomParams.height != bottom) {
                topParams.height = top;
                bottomParams.height = bottom;
                statusSpace.setLayoutParams(topParams);
                navSpace.setLayoutParams(bottomParams);
            }
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private void render() {
        if (content == null || store == null) return;
        int oldY = scroll.getScrollY();
        content.removeAllViews();
        itemViews.clear();
        archiveViews.clear();
        if (showingArchive) {
            renderArchive(oldY);
            return;
        }
        List<BackupItem> items = store.items();
        int done = 0;
        int openErrors = 0;
        for (BackupItem item : items) {
            if (item.checked) done++;
            if (item.reminderAt > 0) openErrors++;
        }
        content.addView(header());
        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(20), dp(20), dp(32));
        content.addView(body, matchWrap());

        if (!Notifications.canPost(this, Notifications.CHECKS) || !Notifications.canPost(this, Notifications.ERRORS)) {
            body.addView(notice("Включите уведомления", "Чтобы не пропустить проверку и ошибки.",
                    "Включить", this::enableNotifications));
            body.addView(space(12));
        } else if (!ReminderScheduler.exactAllowed(this)) {
            body.addView(notice("Нужна точность", "Иначе Android может задержать напоминания.",
                    "Разрешить", this::enableExactAlarms));
            body.addView(space(12));
        }
        body.addView(progressCard(done, items.size(), openErrors));
        body.addView(space(28));

        LinearLayout section = horizontal();
        section.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout heading = vertical();
        heading.addView(text("Чек-лист", 21, INK, true));
        TextView sub = text("Отметьте проверенные копии", 13, MUTED, false);
        LinearLayout.LayoutParams subParams = matchWrap(); subParams.topMargin = dp(4);
        heading.addView(sub, subParams);
        section.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = button("＋ Добавить", GREEN_DARK, MINT, 14);
        add.setContentDescription("Добавить пункт проверки");
        add.setOnClickListener(v -> editItem(null));
        section.addView(add);
        body.addView(section, matchWrap());
        body.addView(space(15));

        if (items.isEmpty()) {
            LinearLayout empty = card();
            empty.setPadding(dp(18), dp(22), dp(18), dp(22));
            empty.addView(text("Здесь будет ваш список", 16, INK, true));
            TextView hint = text("Добавьте системы или хранилища, резервные копии которых нужно проверять.", 14, MUTED, false);
            hint.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(8);
            empty.addView(hint, hp);
            body.addView(empty, matchWrap());
        } else if (done == items.size()) {
            LinearLayout empty = card();
            empty.setPadding(dp(18), dp(20), dp(18), dp(16));
            empty.addView(text("Все задачи выполнены", 16, INK, true));
            TextView hint = text("Они в архиве и вернутся в список в назначенный день.", 13, MUTED, false);
            LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(8);
            empty.addView(hint, hp);
            TextView archive = smallLink("Открыть архив  →", GREEN_DARK);
            archive.setOnClickListener(v -> openArchive());
            empty.addView(archive, matchWrap());
            body.addView(empty, matchWrap());
        } else {
            for (BackupItem item : items) {
                if (item.checked) continue;
                View card = itemCard(item);
                itemViews.put(item.id, card);
                body.addView(card, matchWrap());
                body.addView(space(10));
            }
            TextView hint = text("Пункты можно переименовать или удалить через меню •••", 12, MUTED, false);
            hint.setPadding(dp(2), dp(4), dp(2), 0);
            body.addView(hint, matchWrap());
        }
        body.addView(space(26));
        body.addView(infoCard());
        body.addView(space(12));
        body.addView(updateCard());
        TextView footer = text("Версия " + BuildConfig.VERSION_NAME + "  ·  Данные хранятся на устройстве", 11, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = matchWrap(); fp.topMargin = dp(20);
        body.addView(footer, fp);
        scroll.post(() -> scroll.scrollTo(0, oldY));
    }

    private void openArchive() {
        showingArchive = true;
        scroll.scrollTo(0, 0);
        render();
    }

    private void openChecklist() {
        showingArchive = false;
        scroll.scrollTo(0, 0);
        render();
    }

    @Override public void onBackPressed() {
        if (showingArchive) openChecklist();
        else super.onBackPressed();
    }

    private void renderArchive(int oldY) {
        List<ArchiveEntry> entries = store.archive();
        LinearLayout top = vertical();
        top.setBackgroundColor(NAVY);
        top.setPadding(dp(22), dp(16), dp(22), dp(24));
        TextView back = button("←  К списку", 0xffc9efdd, NAVY_CARD, 12);
        back.setContentDescription("Вернуться к списку задач");
        back.setOnClickListener(v -> openChecklist());
        top.addView(back, wrapWrap());
        TextView eyebrow = text("ИСТОРИЯ ПРОВЕРОК", 10, 0xffa6d9c7, true);
        eyebrow.setLetterSpacing(.10f);
        LinearLayout.LayoutParams ep = matchWrap(); ep.topMargin = dp(26);
        top.addView(eyebrow, ep);
        TextView title = text("Архив выполнений", 27, Color.WHITE, true);
        LinearLayout.LayoutParams tp = matchWrap(); tp.topMargin = dp(8);
        top.addView(title, tp);
        TextView subtitle = text("Здесь сохраняются дата и время каждой проверки.", 14, 0xffb1c6c5, false);
        subtitle.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams sp = matchWrap(); sp.topMargin = dp(9);
        top.addView(subtitle, sp);
        content.addView(top, matchWrap());

        LinearLayout body = vertical();
        body.setPadding(dp(20), dp(22), dp(20), dp(32));
        content.addView(body, matchWrap());
        TextView count = text("ЗАПИСЕЙ В АРХИВЕ  ·  " + entries.size(), 10, MUTED, true);
        count.setLetterSpacing(.08f);
        body.addView(count);
        body.addView(space(14));
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            empty.setPadding(dp(18), dp(21), dp(18), dp(21));
            empty.addView(text("Пока нет выполненных задач", 16, INK, true));
            TextView hint = text("Отметьте задачу в чек-листе — она появится здесь вместе с датой и временем.", 13, MUTED, false);
            hint.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(8);
            empty.addView(hint, hp);
            body.addView(empty, matchWrap());
        } else {
            for (int i = entries.size() - 1; i >= 0; i--) {
                ArchiveEntry entry = entries.get(i);
                View card = archiveCard(entry);
                archiveViews.put(entry.id, card);
                body.addView(card, matchWrap());
                body.addView(space(10));
            }
        }
        scroll.post(() -> scroll.scrollTo(0, oldY));
    }

    private View archiveCard(ArchiveEntry entry) {
        BackupItem current = store.item(entry.itemId);
        boolean awaiting = current != null && current.checked && current.currentArchiveId == entry.id;
        LinearLayout card = card();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("✓", 16, GREEN_DARK, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(round(MINT, 10));
        row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        LinearLayout copy = vertical();
        TextView name = text(entry.itemName, 15, INK, true);
        name.setMaxLines(3);
        copy.addView(name);
        if (!entry.ips.isEmpty()) {
            TextView addresses = text(IpAddresses.join(entry.ips), 12, MUTED, false);
            LinearLayout.LayoutParams ap = matchWrap(); ap.topMargin = dp(3);
            copy.addView(addresses, ap);
        }
        String completed = entry.completedAt > 0
                ? "Выполнено " + new SimpleDateFormat("d MMMM yyyy, HH:mm", RU).format(entry.completedAt)
                : "Дата выполнения не сохранена в прежней версии";
        TextView date = text(completed, 12, MUTED, false);
        LinearLayout.LayoutParams dp = matchWrap(); dp.topMargin = dp(5);
        copy.addView(date, dp);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(12);
        row.addView(copy, copyParams);
        if (!awaiting) {
            TextView remove = text("•••", 18, MUTED, true);
            remove.setGravity(Gravity.CENTER);
            remove.setFocusable(true);
            remove.setContentDescription("Удалить запись из архива: " + entry.itemName);
            remove.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Удалить запись?")
                    .setMessage("Из архива будет удалена только эта запись о выполнении.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Удалить", (dialog, which) -> {
                        store.deleteArchiveEntry(entry.id);
                        render();
                    }).show());
            row.addView(remove, new LinearLayout.LayoutParams(dp(44), dp(48)));
        }
        card.addView(row, matchWrap());
        if (awaiting) {
            TextView due = text("В списке снова " + displayDateKey(current.availableOn), 12, GREEN_DARK, true);
            LinearLayout.LayoutParams dueParams = matchWrap(); dueParams.topMargin = dp(13);
            card.addView(due, dueParams);
            TextView undo = smallLink("Вернуть в список", GREEN_DARK);
            undo.setContentDescription("Отменить выполнение: " + entry.itemName);
            undo.setOnClickListener(v -> {
                if (store.undoCompletion(entry.id)) {
                    openChecklist();
                    toast("Задача возвращена в список");
                } else {
                    render();
                }
            });
            card.addView(undo, matchWrap());
            if (current.reminderAt > 0) {
                LinearLayout reminder = vertical();
                reminder.setPadding(dp(12), dp(10), dp(12), dp(4));
                reminder.setBackground(round(AMBER_BG, 12));
                String label = current.reminderDelivered ? "НАПОМИНАНИЕ ОТПРАВЛЕНО" :
                        current.reminderAt <= System.currentTimeMillis() ? "ПРОСРОЧЕНО" : "НАПОМИНАНИЕ ОБ ОШИБКЕ";
                reminder.addView(text(label + "  ·  " + shortDate(current.reminderAt), 10, AMBER_TEXT, true));
                String note = current.reminderNote.isEmpty() ? "Разобраться с ошибкой" : current.reminderNote;
                TextView details = text(note, 13, INK, true);
                LinearLayout.LayoutParams noteParams = matchWrap(); noteParams.topMargin = dp(5);
                reminder.addView(details, noteParams);
                TextView edit = smallLink(current.reminderDelivered ? "Разобраться / закрыть" : "Изменить напоминание", AMBER_TEXT);
                edit.setOnClickListener(v -> showReminder(current));
                reminder.addView(edit, matchWrap());
                LinearLayout.LayoutParams rp = matchWrap(); rp.topMargin = dp(4);
                card.addView(reminder, rp);
            }
        }
        return card;
    }

    private String displayDateKey(String date) {
        if (date == null || date.isEmpty()) return "в назначенный день";
        try {
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            input.setLenient(false);
            return new SimpleDateFormat("d MMMM", RU).format(input.parse(date));
        } catch (java.text.ParseException e) {
            return "в назначенный день";
        }
    }

    private View header() {
        LinearLayout header = vertical();
        header.setBackgroundColor(NAVY);
        header.setPadding(dp(22), dp(16), dp(22), dp(20));
        LinearLayout brand = horizontal();
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("✓", 16, MINT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(0xff2b5d58, 10));
        brand.addView(mark, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView brandName = text("BACKUP CONTROL", 11, Color.WHITE, true);
        brandName.setLetterSpacing(.13f);
        LinearLayout.LayoutParams bp = wrapWrap(); bp.leftMargin = dp(10);
        brand.addView(brandName, bp);
        View spacer = new View(this);
        brand.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView archive = button("Архив", 0xffc9efdd, NAVY_CARD, 12);
        archive.setContentDescription("Открыть архив выполненных задач");
        archive.setOnClickListener(v -> openArchive());
        brand.addView(archive);
        header.addView(brand, matchWrap());

        TextView title = text("Ваши бэкапы\nпод контролем.", 28, Color.WHITE, true);
        title.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams tp = matchWrap(); tp.topMargin = dp(21);
        header.addView(title, tp);
        TextView subtitle = text("Регулярная проверка — спокойствие за данные.", 14, 0xffb1c6c5, false);
        LinearLayout.LayoutParams sp = matchWrap(); sp.topMargin = dp(8);
        header.addView(subtitle, sp);

        LinearLayout schedule = horizontal();
        schedule.setGravity(Gravity.CENTER_VERTICAL);
        schedule.setPadding(dp(15), dp(12), dp(12), dp(12));
        schedule.setBackground(round(NAVY_CARD, 16));
        LinearLayout scheduleText = vertical();
        TextView eyebrow = text("СЛЕДУЮЩАЯ ПРОВЕРКА", 10, 0xffa6d9c7, true);
        eyebrow.setLetterSpacing(.08f);
        scheduleText.addView(eyebrow);
        String date = new SimpleDateFormat("EEEE, d MMMM", RU).format(store.nextControl());
        date = date.substring(0, 1).toUpperCase(RU) + date.substring(1);
        TextView when = text(date + "  ·  " + time(store.hour(), store.minute()), 15, Color.WHITE, true);
        LinearLayout.LayoutParams wp = matchWrap(); wp.topMargin = dp(6);
        scheduleText.addView(when, wp);
        schedule.addView(scheduleText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView edit = button("Изменить", 0xffc9efdd, 0xff35594f, 12);
        edit.setContentDescription("Изменить день и время проверки");
        edit.setOnClickListener(v -> showSchedule());
        schedule.addView(edit);
        LinearLayout.LayoutParams cp = matchWrap(); cp.topMargin = dp(20);
        header.addView(schedule, cp);
        return header;
    }

    private View notice(String title, String detail, String action, Runnable onClick) {
        LinearLayout box = horizontal();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(10), dp(12), dp(10));
        box.setBackground(round(AMBER_BG, 15));
        LinearLayout copy = vertical();
        copy.addView(text(title, 13, AMBER_TEXT, true));
        TextView message = text(detail, 12, AMBER_TEXT, false);
        message.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams mp = matchWrap(); mp.topMargin = dp(4);
        copy.addView(message, mp);
        box.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView link = button(action, AMBER_TEXT, 0xffffedca, 12);
        link.setContentDescription(action + ": " + title);
        link.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams linkParams = wrapWrap(); linkParams.leftMargin = dp(8);
        box.addView(link, linkParams);
        return box;
    }

    private View progressCard(int done, int total, int errors) {
        LinearLayout card = card();
        card.setPadding(dp(18), dp(18), dp(18), dp(17));
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout left = vertical();
        TextView label = text("ПРОГРЕСС НЕДЕЛИ", 10, MUTED, true);
        label.setLetterSpacing(.09f);
        left.addView(label);
        TextView status = text(total > 0 && done == total ? "Всё проверено" : "Ваша неделя", 18, INK, true);
        LinearLayout.LayoutParams st = matchWrap(); st.topMargin = dp(6);
        left.addView(status, st);
        row.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
        TextView number = text(done + " / " + total, 19, GREEN_DARK, true);
        number.setPadding(dp(11), dp(8), dp(11), dp(8));
        number.setBackground(round(MINT, 12));
        row.addView(number);
        card.addView(row, matchWrap());
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Math.max(1, total));
        bar.setProgress(done);
        bar.setProgressTintList(ColorStateList.valueOf(GREEN));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xffe5efeb));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, dp(7)); pp.topMargin = dp(17);
        card.addView(bar, pp);
        String line = total == 0 ? "Добавьте первый пункт проверки" : "Проверено " + done + " из " + total;
        if (errors > 0) line += "   ·   Ошибки: " + errors;
        TextView detail = text(line, 12, MUTED, false);
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(12);
        card.addView(detail, lp);
        return card;
    }

    private View itemCard(BackupItem item) {
        LinearLayout card = card();
        card.setPadding(dp(14), dp(10), dp(14), dp(12));
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox check = new CheckBox(this);
        check.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{GREEN, 0xffb2c0bb}));
        check.setChecked(item.checked);
        check.setContentDescription((item.checked ? "Проверено: " : "Отметить проверенным: ") + item.name);
        check.setOnCheckedChangeListener((button, checked) -> {
            if (!checked) return;
            store.setChecked(item.id, true);
            Notifications.cancelWeeklyTask(this, item.id);
            render();
            toast("Задача перенесена в архив");
        });
        row.addView(check, new LinearLayout.LayoutParams(dp(44), dp(48)));
        LinearLayout label = vertical();
        TextView name = text(item.name, 15, INK, true);
        name.setMaxLines(3);
        label.addView(name);
        if (!item.ips.isEmpty()) {
            TextView addresses = text(IpAddresses.join(item.ips), 12, MUTED, false);
            addresses.setLineSpacing(dp(1), 1f);
            LinearLayout.LayoutParams ap = matchWrap(); ap.topMargin = dp(3);
            label.addView(addresses, ap);
        }
        TextView state = text(item.checked ? "Проверено" : "Ожидает проверки", 12,
                item.checked ? GREEN_DARK : MUTED, false);
        LinearLayout.LayoutParams stateParams = matchWrap(); stateParams.topMargin = dp(4);
        label.addView(state, stateParams);
        label.setOnClickListener(v -> check.setChecked(!check.isChecked()));
        label.setFocusable(true);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView menu = text("•••", 18, MUTED, true);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Действия с пунктом " + item.name);
        menu.setOnClickListener(v -> showItemMenu(item));
        menu.setFocusable(true);
        row.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(48)));
        card.addView(row, matchWrap());
        View separator = new View(this); separator.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(-1, dp(1)); sep.topMargin = dp(7); sep.bottomMargin = dp(5);
        card.addView(separator, sep);

        if (item.reminderAt == 0) {
            TextView reminder = text("◷   Напомнить об ошибке", 13, GREEN_DARK, true);
            reminder.setGravity(Gravity.CENTER_VERTICAL);
            reminder.setMinHeight(dp(40));
            reminder.setFocusable(true);
            reminder.setOnClickListener(v -> showReminder(item));
            card.addView(reminder, matchWrap());
        } else {
            LinearLayout reminder = vertical();
            reminder.setPadding(dp(12), dp(11), dp(12), dp(8));
            reminder.setBackground(round(AMBER_BG, 12));
            String prefix = item.reminderDelivered ? "НАПОМИНАНИЕ ОТПРАВЛЕНО" :
                    item.reminderAt <= System.currentTimeMillis() ? "ПРОСРОЧЕНО" : "НАПОМИНАНИЕ";
            TextView meta = text(prefix + "  ·  " + shortDate(item.reminderAt), 10, AMBER_TEXT, true);
            meta.setLetterSpacing(.03f);
            reminder.addView(meta);
            String message = item.reminderNote.isEmpty() ? "Разобраться с ошибкой" : item.reminderNote;
            TextView note = text(message, 13, INK, true);
            LinearLayout.LayoutParams np = matchWrap(); np.topMargin = dp(6);
            reminder.addView(note, np);
            LinearLayout actions = horizontal();
            TextView edit = smallLink("Изменить", AMBER_TEXT);
            edit.setOnClickListener(v -> showReminder(item));
            TextView clear = smallLink(item.reminderDelivered ? "Решено" : "Отменить", AMBER_TEXT);
            clear.setContentDescription(item.reminderDelivered ? "Отметить ошибку решённой" : "Отменить напоминание об ошибке");
            clear.setOnClickListener(v -> {
                ReminderScheduler.cancelError(this, item.id);
                store.clearReminder(item.id);
                render();
            });
            actions.addView(edit); actions.addView(spaceHorizontal(18)); actions.addView(clear);
            reminder.addView(actions, matchWrap());
            LinearLayout.LayoutParams rp = matchWrap(); rp.topMargin = dp(7);
            card.addView(reminder, rp);
        }
        return card;
    }

    private View infoCard() {
        LinearLayout box = vertical();
        box.setPadding(dp(17), dp(16), dp(17), dp(16));
        box.setBackground(round(0xffe8f3ed, 15));
        box.addView(text("Спокойствие по расписанию", 14, GREEN_DARK, true));
        TextView detail = text("Выполненные задачи попадают в архив и возвращаются в список в назначенный день. Напоминания об ошибках сохраняются.", 13, 0xff5b7770, false);
        detail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams p = matchWrap(); p.topMargin = dp(7);
        box.addView(detail, p);
        return box;
    }

    private View updateCard() {
        LinearLayout box = card();
        box.setPadding(dp(17), dp(15), dp(17), dp(15));
        LinearLayout top = horizontal();
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout label = vertical();
        label.addView(text("Обновления", 15, INK, true));
        TextView detail = text(UpdateManager.status(this), 12, MUTED, false);
        LinearLayout.LayoutParams dp = matchWrap(); dp.topMargin = dp(5);
        label.addView(detail, dp);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        boolean ready = UpdateManager.readyFile(this) != null;
        TextView action = button(ready ? "Установить" : "Проверить", GREEN_DARK, MINT, 12);
        action.setOnClickListener(v -> {
            if (ready) showUpdateDialog();
            else {
                toast("Проверяем обновления…");
                UpdateManager.checkAsync(this, true, message -> {
                    if (!isFinishing() && !isDestroyed()) { render(); toast(message); }
                });
            }
        });
        top.addView(action);
        box.addView(top, matchWrap());
        return box;
    }

    private void showSchedule() {
        LinearLayout form = dialogForm();
        form.addView(dialogLabel("ДЕНЬ ПРОВЕРКИ"));
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DAY_LABELS);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        for (int i = 0; i < DAYS.length; i++) if (DAYS[i] == store.weekday()) spinner.setSelection(i);
        form.addView(spinner, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView timeLabel = dialogLabel("ВРЕМЯ НАПОМИНАНИЯ");
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(14);
        form.addView(timeLabel, lp);
        final int[] chosen = {store.hour(), store.minute()};
        TextView timeButton = formButton(time(chosen[0], chosen[1]));
        timeButton.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            chosen[0] = hour; chosen[1] = minute; timeButton.setText(time(hour, minute));
        }, chosen[0], chosen[1], true).show());
        form.addView(timeButton, matchWrap());

        // Everything that can make a reminder arrive late or silently is listed
        // here, with a tap to fix it, plus a way to test the real channel now.
        LinearLayout state = vertical();
        state.setPadding(dp(12), dp(10), dp(12), dp(10));
        state.setBackground(round(CANVAS, 10));
        LinearLayout.LayoutParams stateParams = matchWrap(); stateParams.topMargin = dp(16);
        form.addView(state, stateParams);
        state.addView(text("ПРИДЁТ ЛИ НАПОМИНАНИЕ", 10, MUTED, true));
        boolean canPost = Notifications.canPost(this, Notifications.CHECKS);
        boolean interrupts = Notifications.checksCanInterrupt(this);
        boolean exact = ReminderScheduler.exactAllowed(this);
        state.addView(statusLine(canPost, "Уведомления разрешены", "Уведомления выключены — нажмите",
                this::enableNotifications), topMargin(8));
        state.addView(statusLine(interrupts, "Баннер и звук включены", "Канал заглушён — нажмите",
                this::openChannelSettings), topMargin(5));
        state.addView(statusLine(exact, "Точное время разрешено", "Android может задержать — нажмите",
                this::enableExactAlarms), topMargin(5));
        TextView testNotification = button("Проверить уведомление", GREEN_DARK, MINT, 13);
        testNotification.setContentDescription("Отправить проверочное уведомление сейчас");
        testNotification.setOnClickListener(v -> sendTestNotification());
        state.addView(testNotification, topMargin(10));

        TextView hint = text("Задачи вернутся в список с начала выбранного дня. В указанное время придут уведомления о невыполненных задачах.", 13, MUTED, false);
        hint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(12);
        form.addView(hint, hp);
        // The dialog now also carries the notification check, so it may be
        // taller than a small screen: keep it scrollable.
        ScrollView formScroll = new ScrollView(this);
        formScroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this).setTitle("Расписание проверки").setView(formScroll)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    store.setSchedule(DAYS[spinner.getSelectedItemPosition()], chosen[0], chosen[1]);
                    ReminderScheduler.scheduleWeekly(this);
                    if (resumed) scheduleCycleTick();
                    maybeRequestNotifications();
                    render();
                    toast("Расписание сохранено");
                }).show();
    }

    private void editItem(BackupItem item) {
        LinearLayout form = dialogForm();
        form.addView(dialogLabel(item == null ? "НАЗВАНИЕ" : "НАЗВАНИЕ И АДРЕСА"));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        input.setHint("Например, сервер бухгалтерии");
        if (item != null) { input.setText(item.name); input.setSelection(input.length()); }
        form.addView(input, matchWrap());

        LinearLayout addressHeading = horizontal();
        addressHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView addressLabel = dialogLabel("IP-АДРЕС");
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        addressHeading.addView(addressLabel, headingParams);
        TextView addAddress = button("＋", GREEN_DARK, MINT, 16);
        addAddress.setContentDescription("Добавить ещё один адрес");
        addressHeading.addView(addAddress, wrapWrap());
        LinearLayout.LayoutParams ahp = matchWrap(); ahp.topMargin = dp(16);
        form.addView(addressHeading, ahp);

        LinearLayout rows = vertical();
        ScrollView addressScroll = new ScrollView(this);
        addressScroll.setFillViewport(false);
        addressScroll.addView(rows, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams rowsParams = matchWrap(); rowsParams.topMargin = dp(4);
        form.addView(addressScroll, rowsParams);
        List<EditText> fields = new ArrayList<>();
        List<View> removers = new ArrayList<>();
        Runnable sync = () -> {
            boolean removable = fields.size() > 1;
            for (View remover : removers) remover.setVisibility(removable ? View.VISIBLE : View.GONE);
            addAddress.setVisibility(fields.size() >= MAX_IPS ? View.GONE : View.VISIBLE);
            // More than three addresses scroll inside the dialog, so the
            // buttons never get pushed off a small screen or under the keyboard.
            ViewGroup.LayoutParams params = addressScroll.getLayoutParams();
            int height = fields.size() <= 3 ? ViewGroup.LayoutParams.WRAP_CONTENT : dp(168);
            if (params.height != height) {
                params.height = height;
                addressScroll.setLayoutParams(params);
            }
        };
        if (item == null || item.ips.isEmpty()) {
            addIpRow(rows, fields, removers, "", sync);
        } else {
            for (String address : item.ips) addIpRow(rows, fields, removers, address, sync);
        }
        sync.run();
        addAddress.setOnClickListener(v -> {
            if (fields.size() >= MAX_IPS) return;
            EditText added = addIpRow(rows, fields, removers, "", sync);
            added.requestFocus();
        });
        TextView hint = text("«＋» — ещё один адрес. Точки ставятся автоматически.", 12, MUTED, false);
        hint.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams hp = matchWrap(); hp.topMargin = dp(10);
        form.addView(hint, hp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item == null ? "Новый пункт" : "Изменить пункт")
                .setView(form).setNegativeButton("Отмена", null)
                .setPositiveButton(item == null ? "Добавить" : "Сохранить", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("Введите название"); return; }
            List<String> addresses = new ArrayList<>();
            for (EditText field : fields) {
                String address = field.getText().toString().trim();
                if (address.isEmpty()) continue;
                if (!IpAddresses.isValid(address)) {
                    field.setError("Формат: 192.168.1.10");
                    return;
                }
                if (addresses.contains(address)) {
                    field.setError("Этот адрес уже добавлен");
                    return;
                }
                addresses.add(address);
            }
            if (item == null) store.add(name, addresses); else store.rename(item.id, name, addresses);
            dialog.dismiss();
            render();
        }));
        dialog.show();
    }

    /** One numeric IP field that formats itself; "+" adds more of them. */
    private EditText addIpRow(LinearLayout rows, List<EditText> fields, List<View> removers,
                              String value, Runnable onChange) {
        LinearLayout row = horizontal();
        row.setGravity(Gravity.CENTER_VERTICAL);
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setHint("192.168.1.10");
        field.setContentDescription("IP-адрес");
        // A numeric keyboard keeps the input to digits; the decimal flag keeps
        // the '.' key on keyboards that hide it, and the text watcher inserts
        // dots itself so the address is formed while typing.
        field.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        // Accept an explicit '.' as well: hardware keyboards and automated input
        // deliver it as its own key, which the plain numeric filter drops.
        field.setKeyListener(DigitsKeyListener.getInstance("0123456789."));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        field.setText(value);
        field.setSelection(field.length());
        field.addTextChangedListener(new TextWatcher() {
            private boolean formatting;
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                if (formatting) return;
                String formatted = IpAddresses.formatInput(s);
                if (formatted.contentEquals(s)) return;
                formatting = true;
                // Replace the text in place instead of setText(): rebuilding the
                // Editable while the keyboard is composing can drop or duplicate
                // a character on fast input.
                s.replace(0, s.length(), formatted);
                // Always continue typing at the end of the formatted address.
                field.setSelection(formatted.length());
                formatting = false;
            }
        });
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1));
        TextView remove = button("✕", MUTED, CANVAS, 14);
        remove.setContentDescription("Удалить адрес");
        row.addView(remove);
        LinearLayout.LayoutParams rp = matchWrap(); rp.topMargin = dp(6);
        rows.addView(row, rp);
        fields.add(field);
        removers.add(remove);
        onChange.run();
        remove.setOnClickListener(v -> {
            if (fields.size() <= 1) return;
            rows.removeView(row);
            fields.remove(field);
            removers.remove(remove);
            onChange.run();
        });
        return field;
    }

    private void showItemMenu(BackupItem item) {
        new AlertDialog.Builder(this).setTitle(item.name)
                .setItems(new String[]{"Изменить", "Удалить пункт"}, (dialog, which) -> {
                    if (which == 0) editItem(item);
                    else new AlertDialog.Builder(this).setTitle("Удалить пункт?")
                            .setMessage("Пункт «" + item.name + "» и его напоминание будут удалены.")
                            .setNegativeButton("Отмена", null)
                            .setPositiveButton("Удалить", (d, w) -> {
                                ReminderScheduler.cancelError(this, item.id);
                                store.remove(item.id);
                                Notifications.cancelWeeklyTask(this, item.id);
                                render();
                            }).show();
                }).show();
    }

    private void showReminder(BackupItem item) {
        Calendar selected = Calendar.getInstance();
        if (item.reminderAt > 0) selected.setTimeInMillis(item.reminderAt);
        else {
            selected.add(Calendar.DAY_OF_MONTH, 1);
            selected.set(Calendar.HOUR_OF_DAY, 9); selected.set(Calendar.MINUTE, 0);
        }
        selected.set(Calendar.SECOND, 0); selected.set(Calendar.MILLISECOND, 0);
        LinearLayout form = dialogForm();
        TextView intro = text("«" + item.name + "»", 14, MUTED, false);
        form.addView(intro, matchWrap());
        TextView label = dialogLabel("ЧТО НУЖНО РАЗОБРАТЬ");
        LinearLayout.LayoutParams lp = matchWrap(); lp.topMargin = dp(16);
        form.addView(label, lp);
        EditText note = new EditText(this);
        note.setSingleLine(true);
        note.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        note.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        note.setHint("Описание ошибки (необязательно)");
        note.setText(item.reminderNote);
        form.addView(note, matchWrap());
        TextView dateLabel = dialogLabel("ДАТА");
        LinearLayout.LayoutParams dlp = matchWrap(); dlp.topMargin = dp(16);
        form.addView(dateLabel, dlp);
        TextView dateButton = formButton(longDate(selected.getTimeInMillis()));
        dateButton.setOnClickListener(v -> {
            DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
                selected.set(Calendar.DAY_OF_MONTH, 1);
                selected.set(Calendar.YEAR, year); selected.set(Calendar.MONTH, month); selected.set(Calendar.DAY_OF_MONTH, day);
                dateButton.setText(longDate(selected.getTimeInMillis()));
            }, selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH));
            Calendar today = Calendar.getInstance();
            today.set(Calendar.HOUR_OF_DAY, 0); today.set(Calendar.MINUTE, 0);
            today.set(Calendar.SECOND, 0); today.set(Calendar.MILLISECOND, 0);
            picker.getDatePicker().setMinDate(today.getTimeInMillis());
            picker.show();
        });
        form.addView(dateButton, matchWrap());
        TextView timeLabel = dialogLabel("ВРЕМЯ");
        LinearLayout.LayoutParams tlp = matchWrap(); tlp.topMargin = dp(12);
        form.addView(timeLabel, tlp);
        TextView timeButton = formButton(time(selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE)));
        timeButton.setOnClickListener(v -> new TimePickerDialog(this, (picker, hour, minute) -> {
            selected.set(Calendar.HOUR_OF_DAY, hour); selected.set(Calendar.MINUTE, minute);
            timeButton.setText(time(hour, minute));
        }, selected.get(Calendar.HOUR_OF_DAY), selected.get(Calendar.MINUTE), true).show());
        form.addView(timeButton, matchWrap());
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Напомнить об ошибке")
                .setView(form).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null);
        if (item.reminderAt > 0) builder.setNeutralButton(item.reminderDelivered ? "Решено" : "Удалить", (dialog, which) -> {
            ReminderScheduler.cancelError(this, item.id);
            store.clearReminder(item.id);
            render();
        });
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long at = selected.getTimeInMillis();
            if (at <= System.currentTimeMillis()) { toast("Выберите будущие дату и время"); return; }
            ReminderScheduler.cancelError(this, item.id);
            store.setReminder(item.id, at, note.getText().toString().trim());
            BackupItem updated = store.item(item.id);
            if (updated != null) ReminderScheduler.scheduleError(this, updated);
            dialog.dismiss();
            maybeRequestNotifications();
            render();
            toast("Напоминание сохранено");
        }));
        dialog.show();
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        if (!getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notification_asked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private void enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notification_asked", false)) {
            maybeRequestNotifications();
            return;
        }
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            } else {
                intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
            }
            startActivity(intent);
        } catch (ActivityNotFoundException e) { toast("Включите уведомления в настройках приложения Android"); }
    }

    /** Shows the real reminder channel immediately, so it can be heard and seen. */
    private void sendTestNotification() {
        if (!Notifications.canPost(this, Notifications.CHECKS)) {
            toast("Сначала разрешите уведомления");
            enableNotifications();
            return;
        }
        if (!Notifications.test(this)) {
            toast("Android не показал уведомление. Проверьте настройки приложения.");
            return;
        }
        toast("Уведомление отправлено: проверьте баннер и звук");
    }

    private void openChannelSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())
                    .putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CHECKS));
        } catch (ActivityNotFoundException e) {
            enableNotifications();
        }
    }

    private TextView statusLine(boolean ok, String okText, String problem, Runnable fix) {
        TextView row = text((ok ? "✓  " : "✕  ") + (ok ? okText : problem), 13,
                ok ? GREEN_DARK : AMBER_TEXT, true);
        if (!ok) {
            row.setFocusable(true);
            row.setContentDescription(problem);
            row.setOnClickListener(v -> fix.run());
        }
        return row;
    }

    private LinearLayout.LayoutParams topMargin(int value) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(value);
        return params;
    }

    private void enableExactAlarms() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); }
            catch (ActivityNotFoundException ignored) { toast("Откройте настройки приложения Android"); }
        }
    }

    private void showUpdateDialog() {
        if (UpdateManager.readyFile(this) == null) {
            toast("Обновление ещё не готово. Проверьте наличие новой версии.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("Обновление готово")
                .setMessage("Новая версия загружена и проверена. Android попросит подтвердить установку; ваши данные сохранятся.")
                .setNegativeButton("Позже", null)
                .setPositiveButton("Установить", (dialog, which) -> requestInstall()).show();
    }

    private void requestInstall() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("Разрешить установку обновлений")
                    .setMessage("Android требует разрешить этому приложению установку APK. После разрешения подтвердите обновление в системном окне.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Открыть настройки", (dialog, which) -> {
                        try {
                            pendingInstall = true;
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            pendingInstall = false;
                            toast("Разрешите установку из этого источника в настройках Android");
                        }
                    }).show();
        } else launchInstaller();
    }

    private void launchInstaller() {
        if (UpdateManager.readyFile(this) == null) { toast("Файл обновления не найден"); return; }
        Uri uri = Uri.parse("content://" + getPackageName() + ".updates/update.apk");
        Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(install); }
        catch (ActivityNotFoundException | SecurityException e) { toast("Не удалось открыть системный установщик Android"); }
    }

    private LinearLayout card() {
        LinearLayout layout = vertical();
        GradientDrawable bg = round(Color.WHITE, 17);
        bg.setStroke(dp(1), BORDER);
        layout.setBackground(bg);
        return layout;
    }
    private LinearLayout dialogForm() {
        LinearLayout form = vertical();
        form.setPadding(dp(24), dp(4), dp(24), dp(8));
        return form;
    }
    private TextView dialogLabel(String value) {
        TextView label = text(value, 10, MUTED, true); label.setLetterSpacing(.08f); return label;
    }
    private TextView formButton(String value) {
        TextView button = text(value + "   ▾", 15, INK, true);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(48));
        GradientDrawable bg = round(CANVAS, 10); bg.setStroke(dp(1), BORDER); button.setBackground(bg);
        button.setFocusable(true);
        return button;
    }
    private TextView button(String value, int color, int background, int size) {
        TextView view = text(value, size, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(42));
        view.setPadding(dp(12), dp(4), dp(12), dp(4));
        view.setBackground(round(background, 12));
        view.setFocusable(true);
        return view;
    }
    private TextView smallLink(String value, int color) {
        TextView view = text(value, 12, color, true);
        view.setMinHeight(dp(40)); view.setGravity(Gravity.CENTER_VERTICAL); view.setFocusable(true);
        return view;
    }
    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(true);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private LinearLayout vertical() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout horizontal() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); return l; }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(-2, -2); }
    private View space(int height) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(height))); return view; }
    private View spaceHorizontal(int width) { View view = new View(this); view.setLayoutParams(new LinearLayout.LayoutParams(dp(width), 1)); return view; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String time(int hour, int minute) { return String.format(RU, "%02d:%02d", hour, minute); }
    private String shortDate(long millis) { return new SimpleDateFormat("d MMM, HH:mm", RU).format(millis); }
    private String longDate(long millis) { return new SimpleDateFormat("d MMMM yyyy", RU).format(millis); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
}
