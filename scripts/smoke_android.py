#!/usr/bin/env python3
"""Exercise the real Android UI on the Actions emulator without test dependencies."""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

PACKAGE = "com.zigorminsk.backupcontrol"
APK = "app/build/outputs/apk/debug/app-debug.apk"


def adb(*args, check=True):
    result = subprocess.run(["adb", *args], text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, check=check)
    return result.stdout


def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/backup-control-window.xml")
    xml = adb("exec-out", "cat", "/sdcard/backup-control-window.xml")
    return ET.fromstring(xml[xml.index("<?xml"):])


def find(root, text=None, description=None, exact=False):
    for node in root.iter("node"):
        val = node.get("text", "") if text is not None else node.get("content-desc", "")
        needle = text if text is not None else description
        if (val.casefold() == needle.casefold() if exact else needle.casefold() in val.casefold()) and node.get("bounds"):
            return node
    return None


def wait_for(text=None, description=None, klass=None, exact=False, attempts=8):
    for _ in range(attempts):
        root = tree()
        if klass is not None:
            node = next((n for n in root.iter("node") if n.get("class") == klass), None)
        else:
            node = find(root, text=text, description=description, exact=exact)
        if node is not None:
            return node
        time.sleep(.6)
    return None


def click(node):
    if node is None:
        root = tree()
        print("Visible controls:", [(n.get("text"), n.get("content-desc"), n.get("bounds"))
                                    for n in root.iter("node") if n.get("text") or n.get("content-desc")])
        raise AssertionError("Expected UI control was not found")
    coordinates = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(coordinates) != 4:
        raise AssertionError("Invalid control bounds: " + str(node.attrib))
    x = (coordinates[0] + coordinates[2]) // 2
    y = (coordinates[1] + coordinates[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def swipe(up=True):
    sizes = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))
    if not sizes:
        raise AssertionError("Cannot determine emulator screen size")
    width, height = map(int, sizes[-1])
    start, end = (int(height * .78), int(height * .30)) if up else (int(height * .30), int(height * .78))
    adb("shell", "input", "swipe", str(width // 2), str(start), str(width // 2), str(end), "400")
    time.sleep(.7)


def scroll_to(text=None, description=None, prefer_down=True):
    root = None
    for direction in (prefer_down, not prefer_down):
        for _ in range(8):
            root = tree()
            result = find(root, text=text, description=description)
            if result is not None:
                return root, result
            swipe(direction)
    if root is not None:
        print("Visible controls:", [(n.get("text"), n.get("content-desc"), n.get("bounds"))
                                    for n in root.iter("node") if n.get("text") or n.get("content-desc")])
    raise AssertionError("Could not scroll to " + str(text or description))


def find_or_scroll(text=None, description=None, swipes=3):
    """Find a control that may sit below the fold of a scrollable dialog."""
    for _ in range(swipes + 1):
        node = wait_for(text=text, description=description)
        if node is not None:
            return node
        swipe(True)
    return None


def notification_row(prefix):
    for node in tree().iter("node"):
        text = node.get("text", "")
        if text.startswith(prefix):
            return node
    return None


def bounds(node):
    values = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
    return values if len(values) == 4 else (0, 0, 0, 0)


def open_archive():
    if find(tree(), text="Архив выполнений") is not None:
        return
    _, button = scroll_to(description="Открыть архив выполненных задач", prefer_down=False)
    click(button)
    if wait_for(text="Архив выполнений") is None:
        raise AssertionError("Archive screen did not open")


def open_checklist():
    if find(tree(), text="Архив выполнений") is not None:
        click(wait_for(description="Вернуться к списку задач"))


def assert_archive_record():
    root, _ = scroll_to(text="CI_Server")
    timestamp = find(root, text="Выполнено ")
    if timestamp is None or not re.search(r"\d{1,2}:\d{2}", timestamp.get("text", "")):
        raise AssertionError("Archive does not show the completion date and time")


def saved_state():
    raw = adb("shell", "run-as", PACKAGE, "cat", "shared_prefs/backup_control.xml")
    preferences = ET.fromstring(raw)
    values = {node.get("name"): node.text for node in preferences if node.tag == "string"}
    return json.loads(values.get("items", "[]")), json.loads(values.get("archive", "[]"))


def assert_hidden_and_saved():
    root = tree()
    if find(root, description="Отметить проверенным: CI_Server") is not None or find(root, text="CI_Server", exact=True) is not None:
        raise AssertionError("Completed task is still visible in the checklist")
    items, entries = saved_state()
    task = next((item for item in items if item.get("name") == "CI_Server"), None)
    if not task or not task.get("checked") or not any(entry.get("itemId") == task.get("id")
            and entry.get("completedAt", 0) > 0 for entry in entries):
        raise AssertionError("Completed task and its timestamp were not persisted")
    expected_ips = ["25.62.56.252", "99.99.99.99"]
    if task.get("ips") != expected_ips:
        raise AssertionError("IP addresses were not saved: " + str(task.get("ips")))
    if not any(entry.get("itemId") == task.get("id") and entry.get("ips") == expected_ips
               for entry in entries):
        raise AssertionError("IP addresses were not archived with the completion")


IP_FIELD = "IP-адрес"


def ip_fields():
    return [n for n in tree().iter("node") if n.get("content-desc") == IP_FIELD]


def describe_fields():
    return [(n.get("text", ""), n.get("bounds", ""), n.get("focused", "")) for n in ip_fields()]


def tap_fresh(text=None, description=None, exact=False):
    """Tap using bounds taken immediately before the tap: dialogs move while
    the keyboard animates, and a stale node silently misses the button."""
    node = wait_for(text=text, description=description, exact=exact)
    if node is None:
        raise AssertionError("Control not found: " + str(text or description))
    fresh = find(tree(), text=text, description=description, exact=exact)
    click(fresh if fresh is not None else node)


def type_ip(digits, expected, attempts=3):
    """Type an address digit by digit. Injecting a whole string at once races
    with the field reformatting itself, so a character can be lost."""
    seen = None
    for _ in range(attempts):
        clear_focused_field()
        for digit in digits:
            adb("shell", "input", "text", digit)
            time.sleep(.12)
        values = [n.get("text", "") for n in ip_fields()]
        seen = values
        if values and values[-1] == expected:
            return
    raise AssertionError("Typing %s produced %s, expected %s" % (digits, seen, expected))


def clear_focused_field():
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(16):
        adb("shell", "input", "keyevent", "KEYCODE_DEL")


def allow_notifications_if_asked():
    root = tree()
    for node in root.iter("node"):
        if node.get("resource-id", "").endswith("permission_allow_button"):
            click(node)
            return


def capture():
    output = Path("ui-smoke.png")
    with output.open("wb") as out:
        subprocess.run(["adb", "exec-out", "screencap", "-p"], stdout=out, check=True)


def main():
    adb("install", "-r", APK)
    # Test exact delivery, rather than Android's intentionally deferred fallback.
    adb("shell", "appops", "set", PACKAGE, "SCHEDULE_EXACT_ALARM", "allow")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    # Exercise the production updater's APK verifier on an actual device: an
    # update with the same key must pass, a different signer must fail.
    test_dir = "/sdcard/Android/data/" + PACKAGE + "/files/Download"
    adb("shell", "mkdir", "-p", test_dir)
    adb("push", str(Path(os.environ["RUNNER_TEMP"], "updated-debug.apk")), test_dir + "/trusted-update.apk")
    adb("push", str(Path(os.environ["RUNNER_TEMP"], "untrusted-debug.apk")), test_dir + "/untrusted-update.apk")
    result = adb("shell", "am", "instrument", "-w", PACKAGE + "/.UpdateVerificationInstrumentation")
    if "signature_check=PASS" not in result:
        raise AssertionError("Update signature verification failed: " + result)
    # The reminder must be able to interrupt: banner, sound and vibration, and
    # only the weekly summary may alert. A silently posted notification is a
    # missed reminder.
    adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
    result = adb("shell", "am", "instrument", "-w", PACKAGE + "/.NotificationVerificationInstrumentation")
    if "notification_check=PASS" not in result:
        raise AssertionError("Notification delivery verification failed: " + result)
    # Swiping the weekly reminder away must ask how long to postpone it. The
    # notification check leaves that reminder in the shade on purpose, so this
    # runs before anything else can dismiss it.
    def swipe_reminder_once():
        adb("shell", "cmd", "statusbar", "expand-notifications")
        time.sleep(2)
        row = notification_row("Пора проверить бэкапы")
        if row is None:
            return False
        x1, y1, x2, y2 = bounds(row)
        middle = (y1 + y2) // 2
        # Start well inside the row and drag it across the screen: a swipe that
        # begins at the very edge sometimes misses the touch target.
        adb("shell", "input", "swipe", str(x2 - 40), str(middle), "20", str(middle), "400")
        time.sleep(1)
        adb("shell", "cmd", "statusbar", "collapse")
        return wait_for(text="Отложить напоминание?", attempts=10) is not None

    if not any(swipe_reminder_once() for _ in range(3)):
        raise AssertionError("Swiping the reminder away did not offer to postpone it")
    for option in ["Отложить на 15 мин", "Отложить на 30 мин", "Отложить на 1 ч",
                   "Отложить на 2 ч", "Отложить на 3 ч"]:
        if wait_for(text=option, exact=True, attempts=10) is None:
            raise AssertionError("Postpone dialog is missing: " + option)
    # Choosing an option must clear the reminder. "Не напоминать" is used here so
    # no postpone alarm is left pending for the schedule checks below.
    click(wait_for(text="Не напоминать на этой неделе", exact=True))
    time.sleep(1)
    if "tag=weekly_summary" in adb("shell", "dumpsys", "notification", "--noredact"):
        raise AssertionError("The reminder stayed visible after being postponed")

    result = adb("shell", "am", "instrument", "-w", PACKAGE + "/.ArchiveVerificationInstrumentation")
    if "archive_check=PASS" not in result:
        raise AssertionError("Archive and recurrence verification failed: " + result)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    if not adb("shell", "pidof", PACKAGE).strip():
        raise AssertionError("App process did not start")
    if wait_for(text="Ваши бэкапы") is None:
        root = tree()
        print("Visible controls:", [(n.get("text"), n.get("content-desc"), n.get("class"))
                                    for n in root.iter("node") if n.get("text") or n.get("content-desc")])
        raise AssertionError("Home screen did not appear")
    capture()

    # The schedule dialog must report why a reminder could be late and offer an
    # immediate check of the real channel.
    _, schedule = scroll_to(description="Изменить день и время проверки", prefer_down=False)
    click(schedule)
    if wait_for(text="ПРИДЁТ ЛИ НАПОМИНАНИЕ") is None:
        raise AssertionError("Schedule dialog does not report notification state")
    test_button = find_or_scroll(description="Отправить проверочное уведомление сейчас")
    if test_button is None:
        raise AssertionError("Notification test button is unreachable")
    click(test_button)
    if wait_for(text="ОТМЕНА", exact=True) is None:
        raise AssertionError("Schedule dialog closed unexpectedly")
    posted = adb("shell", "dumpsys", "notification", "--noredact")
    if "notification_test" not in posted:
        raise AssertionError("The test notification did not reach the shade")
    if "weekly_checks_v3" not in posted:
        # Some platform builds omit channel ids from this dump; the channel's
        # importance, sound, vibration and its own chime are asserted by
        # instrumentation.
        print("note: channel id is not listed by dumpsys on this build")
    click(wait_for(text="Отмена", exact=True))

    # Add a task to the real UI, with two IP addresses. The address field must
    # accept plain digits and turn them into a dotted address by itself.
    _, add = scroll_to(description="Добавить пункт проверки")
    click(add)
    click(wait_for(klass="android.widget.EditText"))
    adb("shell", "input", "text", "CI_Server")
    tap_fresh(description=IP_FIELD, exact=True)
    clear_focused_field()
    # Plain digits: the field has to insert the dots itself. 256 cannot be an
    # octet, so typing those digits forms the four-octet address 25.62.56.252.
    type_ip("256256256256", "25.62.56.252")
    # A second machine: the plus button appends another address row, and digits
    # without separators are formed into an address by the field itself.
    tap_fresh(description="Добавить ещё один адрес", exact=True)
    fields = ip_fields()
    if len(fields) != 2:
        raise AssertionError("The plus button did not add exactly one row: "
                             + str(describe_fields()))
    click(fields[-1])
    type_ip("9999999999", "99.99.99.99")
    if [n.get("text", "") for n in ip_fields()] != ["25.62.56.252", "99.99.99.99"]:
        raise AssertionError("IP rows are wrong after typing: " + str(describe_fields()))
    tap_fresh(text="Добавить", exact=True)
    root, _ = scroll_to(text="25.62.56.252 · 99.99.99.99")
    # Schedule an error reminder before completing the task. It must remain
    # accessible even when the task has moved to the archive.
    root, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    parents = {child: parent for parent in root.iter() for child in parent}
    node = checkbox
    reminder = None
    while node in parents:
        node = parents[node]
        reminder = find(node, text="Напомнить об ошибке")
        if reminder is not None:
            break
    if reminder is None:
        swipe(True)
        root = tree()
        checkbox = find(root, description="Отметить проверенным: CI_Server")
        parents = {child: parent for parent in root.iter() for child in parent}
        node = checkbox
        while node in parents:
            node = parents[node]
            reminder = find(node, text="Напомнить об ошибке")
            if reminder is not None:
                break
    click(reminder)
    if wait_for(text="Напомнить об ошибке") is None:
        raise AssertionError("Reminder dialog did not open")
    click(wait_for(text="Сохранить", exact=True))
    allow_notifications_if_asked()
    _, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    click(checkbox)
    assert_hidden_and_saved()
    open_archive()
    assert_archive_record()
    scroll_to(text="НАПОМИНАНИЕ ОБ ОШИБКЕ")
    items, _ = saved_state()
    archived_id = next(item["id"] for item in items if item.get("name") == "CI_Server")
    # The intent used by an error notification must open the archived task and
    # its reminder, not silently return to an empty checklist.
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity",
        "--ei", "item_id", str(archived_id), "--ez", "open_error", "true")
    if wait_for(text="Напомнить об ошибке") is None:
        raise AssertionError("Error notification did not open the archived reminder")
    click(wait_for(text="Отмена", exact=True))

    # Undo an accidental completion, then complete the task again.
    _, undo = scroll_to(description="Отменить выполнение: CI_Server")
    click(undo)
    _, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    click(checkbox)
    open_archive()
    assert_archive_record()

    # Close and reopen the process: the archive and reminder must persist.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(2)
    open_archive()
    assert_archive_record()
    scroll_to(text="НАПОМИНАНИЕ ОБ ОШИБКЕ")
    alarms = adb("shell", "dumpsys", "alarm")
    if PACKAGE not in alarms:
        raise AssertionError("No Android alarms registered for the app")

    # An update signed with the same key must install over the old package
    # without losing its checklist or unresolved reminder.
    before = re.search(r"versionCode=(\d+)", adb("shell", "dumpsys", "package", PACKAGE))
    if before is None:
        raise AssertionError("Cannot read installed version")
    update_apk = str(Path(os.environ["RUNNER_TEMP"], "updated-debug.apk"))
    adb("install", "-r", update_apk)
    after = re.search(r"versionCode=(\d+)", adb("shell", "dumpsys", "package", PACKAGE))
    if after is None or int(after.group(1)) <= int(before.group(1)):
        raise AssertionError("The second APK did not increase the version code")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(2)
    open_archive()
    assert_archive_record()
    scroll_to(text="НАПОМИНАНИЕ ОБ ОШИБКЕ")

    # Advance to Monday 00:01: the archived task must reappear at the start
    # of the selected day, not wait until the 09:00 notification time.
    adb("root")
    adb("wait-for-device")
    adb("shell", "settings", "put", "global", "auto_time", "0")
    now = int(adb("shell", "date", "+%s").strip())
    zone_name = adb("shell", "getprop", "persist.sys.timezone").strip() or "UTC"
    local_now = datetime.fromtimestamp(now, ZoneInfo(zone_name))
    days_to_monday = (7 - local_now.weekday()) % 7 or 7
    monday = (local_now + timedelta(days=days_to_monday)).replace(hour=0, minute=1, second=0, microsecond=0)
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "date", "-s", "@" + str(int(monday.timestamp())))
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    open_checklist()
    _, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    scroll_to(text="НАПОМИНАНИЕ ОТПРАВЛЕНО")
    if re.search(r"tag=weekly_task\b", adb("shell", "dumpsys", "notification", "--noredact")):
        raise AssertionError("Weekly notification was sent before the selected time")

    # Completing it before 09:00 must keep it in the archive throughout that
    # day; the scheduled notification must mention only unfinished tasks.
    _, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    click(checkbox)
    assert_hidden_and_saved()
    at_nine = monday.replace(hour=9, minute=1)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "date", "-s", "@" + str(int(at_nine.timestamp())))
    time.sleep(5)
    posted = adb("shell", "dumpsys", "notification", "--noredact")
    if "weekly_task" not in posted or "Пора проверить: CI_Server" in posted:
        raise AssertionError("Background notification did not exclude the completed task")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    open_archive()
    assert_archive_record()

    # On the following Monday it is due again. Verify that its named
    # notification is delivered while the app is in the background.
    next_monday = at_nine + timedelta(days=7)
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    adb("shell", "date", "-s", "@" + str(int(next_monday.timestamp())))
    time.sleep(5)
    posted = adb("shell", "dumpsys", "notification", "--noredact")
    if "weekly_task" not in posted or "Пора проверить: CI_Server" not in posted:
        raise AssertionError("Background weekly notification did not name the due task")
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    open_checklist()
    scroll_to(description="Отметить проверенным: CI_Server")
    open_archive()
    assert_archive_record()
    items, entries = saved_state()
    task = next(item for item in items if item.get("name") == "CI_Server")
    if len([entry for entry in entries if entry.get("itemId") == task.get("id")]) != 2:
        raise AssertionError("Historical completion records were not retained across cycles")
    print("PASS: archive timestamp and undo, scheduled return, task notification, error reminder and same-key upgrade")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SMOKE TEST FAILED:", exc, file=sys.stderr)
        print("Screen:", adb("shell", "wm", "size", check=False), file=sys.stderr)
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)[-12000:], file=sys.stderr)
        raise
