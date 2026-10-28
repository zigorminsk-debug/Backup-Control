#!/usr/bin/env python3
"""Exercise the real Android UI on the Actions emulator without test dependencies."""
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
        if (val == needle if exact else needle in val) and node.get("bounds"):
            return node
    return None


def click(node):
    if node is None:
        raise AssertionError("Expected UI control was not found")
    coordinates = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
    if len(coordinates) != 4:
        raise AssertionError("Invalid control bounds: " + str(node.attrib))
    x = (coordinates[0] + coordinates[2]) // 2
    y = (coordinates[1] + coordinates[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)


def scroll_to(text=None, description=None):
    for _ in range(8):
        root = tree()
        result = find(root, text=text, description=description)
        if result is not None:
            return root, result
        adb("shell", "input", "swipe", "500", "1600", "500", "560", "400")
        time.sleep(.7)
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
    # Preview conversion is optional; the PNG artifact is always available.
    try:
        from PIL import Image
        with Image.open(output) as image:
            image.thumbnail((360, 1000))
            image.convert("RGB").save("ui-smoke-preview.jpg", quality=65, optimize=True)
    except (ImportError, OSError) as error:
        print("Preview conversion skipped:", error)


def main():
    adb("install", "-r", APK)
    adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    time.sleep(3)
    if not adb("shell", "pidof", PACKAGE).strip():
        raise AssertionError("App process did not start")
    root = tree()
    if find(root, text="Ваши бэкапы") is None:
        raise AssertionError("Home screen did not appear")
    capture()

    # Add a task to the real UI.
    _, add = scroll_to(text="Добавить")
    click(add)
    root = tree()
    field = next((n for n in root.iter("node") if n.get("class") == "android.widget.EditText"), None)
    click(field)
    adb("shell", "input", "text", "CI_Server")
    root = tree()
    click(find(root, text="Добавить", exact=True))
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
        adb("shell", "input", "swipe", "500", "1350", "500", "700", "350")
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
    root = tree()
    if find(root, text="Напомнить об ошибке") is None:
        raise AssertionError("Reminder dialog did not open")
    click(find(root, text="Сохранить", exact=True))
    allow_notifications_if_asked()
    _, reminder_label = scroll_to(text="НАПОМИНАНИЕ")
    if reminder_label is None:
        raise AssertionError("Reminder was not saved")
    alarms = adb("shell", "dumpsys", "alarm")
    if PACKAGE not in alarms:
        raise AssertionError("No Android alarms registered for the app")
    print("PASS: launch, checklist add/check persistence, reminder creation and scheduled alarms")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print("SMOKE TEST FAILED:", exc, file=sys.stderr)
        print(adb("logcat", "-d", "-s", "AndroidRuntime:E", check=False)[-12000:], file=sys.stderr)
        raise
