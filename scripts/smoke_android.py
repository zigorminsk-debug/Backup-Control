#!/usr/bin/env python3
"""Exercise the real Android UI on the Actions emulator without test dependencies."""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

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


def scroll_to(text=None, description=None):
    root = None
    for direction in (True, False):
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

    # Add a task to the real UI.
    _, add = scroll_to(description="Добавить пункт проверки")
    click(add)
    click(wait_for(klass="android.widget.EditText"))
    adb("shell", "input", "text", "CI_Server")
    click(wait_for(text="Добавить", exact=True))
    _, checkbox = scroll_to(description="Отметить проверенным: CI_Server")
    click(checkbox)
    _, checked = scroll_to(description="Проверено: CI_Server")
    if checked is None:
        raise AssertionError("Checkbox was not saved")

    # Close and reopen the process: checklist state must survive.
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(2)
    root, checked = scroll_to(description="Проверено: CI_Server")

    # Find the reminder action belonging to this item (not a neighbouring item).
    parents = {child: parent for parent in root.iter() for child in parent}
    node = checked
    reminder = None
    while node in parents:
        node = parents[node]
        reminder = find(node, text="Напомнить об ошибке")
        if reminder is not None:
            break
    if reminder is None:
        # The action may be just below the visible edge.
        swipe(True)
        root = tree()
        checked = find(root, description="Проверено: CI_Server")
        parents = {child: parent for parent in root.iter() for child in parent}
        node = checked
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
    _, reminder_label = scroll_to(text="НАПОМИНАНИЕ")
    if reminder_label is None:
        raise AssertionError("Reminder was not saved")
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
    scroll_to(description="Проверено: CI_Server")
    scroll_to(text="НАПОМИНАНИЕ")

    # Move the emulator clock ahead by eight days. On the next launch the app
    # must reset weekly checks and deliver the missed error reminder, even if
    # the process was stopped while the scheduled time passed.
    adb("root")
    adb("wait-for-device")
    adb("shell", "settings", "put", "global", "auto_time", "0")
    now = int(adb("shell", "date", "+%s").strip())
    adb("shell", "date", "-s", "@" + str(now + 8 * 24 * 60 * 60))
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    scroll_to(description="Отметить проверенным: CI_Server")
    scroll_to(text="НАПОМИНАНИЕ ОТПРАВЛЕНО")
    print("PASS: signature verification, same-key upgrade, checklist persistence, alarms, weekly reset and overdue reminder")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SMOKE TEST FAILED:", exc, file=sys.stderr)
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)[-12000:], file=sys.stderr)
        raise
