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


def wait_for(text=None, description=None, klass=None, exact=False):
    for _ in range(8):
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
    expected_ips = ["192.168.1.10", "192.168.0.0"]
    if task.get("ips") != expected_ips:
        raise AssertionError("IP addresses were not saved: " + str(task.get("ips")))
    if not any(entry.get("itemId") == task.get("id") and entry.get("ips") == expected_ips
               for entry in entries):
        raise AssertionError("IP addresses were not archived with the completion")


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

    # Add a task to the real UI, with two IP addresses. The address field must
    # accept plain digits and turn them into a dotted address by itself.
    _, add = scroll_to(description="Добавить пункт проверки")
    click(add)
    click(wait_for(klass="android.widget.EditText"))
    adb("shell", "input", "text", "CI_Server")
    click(wait_for(description="IP-адрес"))
    adb("shell", "input", "text", "192.168.1.10")
    root = tree()
    formatted = [n.get("text", "") for n in root.iter("node") if n.get("text")]
    if "192.168.1.10" not in formatted:
        raise AssertionError("A typed IP address was altered: " + str(formatted))
    # A second machine: the plus button appends another address row, and digits
    # without separators are formed into an address by the field itself.
    click(wait_for(description="Добавить ещё один IP-адрес"))
    adb("shell", "input", "text", "19216800")
    root = tree()
    formatted = [n.get("text", "") for n in root.iter("node") if n.get("text")]
    if "192.168.1.10" not in formatted or "192.168.0.0" not in formatted:
        raise AssertionError("The second IP address row was not formed: " + str(formatted))
    click(wait_for(text="Добавить", exact=True))
    root, _ = scroll_to(text="192.168.1.10 · 192.168.0.0")
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
    if "weekly_task" in adb("shell", "dumpsys", "notification", "--noredact"):
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
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)[-12000:], file=sys.stderr)
        raise
