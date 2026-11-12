"""Small adb UI driver for the vault end-to-end run. Emulator only: every command names the serial."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

SERIAL = "emulator-5554"

def adb(*a, check=True, timeout=120):
    r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f"adb {a}: {r.stderr[:300]}")
    return r.stdout

def nodes():
    for _ in range(5):
        out = adb("exec-out", "uiautomator", "dump", "/dev/tty", check=False)
        m = re.search(r"<\?xml.*</hierarchy>", out, re.S)
        if m:
            return list(ET.fromstring(m.group(0)).iter("node"))
        time.sleep(0.7)
    raise RuntimeError("no UI dump")

def texts():
    return [n.get("text") or n.get("content-desc") for n in nodes() if n.get("text") or n.get("content-desc")]

def find(label, exact=False, ns=None):
    for n in ns or nodes():
        for v in (n.get("text"), n.get("content-desc")):
            if v and (v == label if exact else label.lower() in v.lower()):
                return n
    return None

def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2

def wait(label, secs=30, exact=False):
    end = time.time() + secs
    while time.time() < end:
        n = find(label, exact)
        if n is not None:
            return n
        time.sleep(0.5)
    raise AssertionError(f"not on screen: {label!r}\nfocus: {focus()}\nscreen: {texts()[:30]}")

def tap(label, secs=30, exact=False):
    x, y = center(wait(label, secs, exact))
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(0.6)

def tap_xy(x, y):
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.6)

def typ(text):
    adb("shell", "input", "text", text.replace(" ", "%s").replace("&", "\\&").replace("'", "\\'").replace("(", "\\(").replace(")", "\\)"))
    time.sleep(0.5)

def field(label, text):
    tap(label)
    typ(text)

def back():
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(0.6)

def hide_keys():
    if "mInputShown=true" in adb("shell", "dumpsys", "input_method"):
        back()

def scroll_to(label, tries=8):
    for _ in range(tries):
        if find(label) is not None:
            return
        adb("shell", "input", "swipe", "500", "1700", "500", "700", "300"); time.sleep(0.7)
    raise AssertionError(f"not found after scrolling: {label}")

def focus():
    m = re.search(r"mCurrentFocus=.*", adb("shell", "dumpsys", "window"))
    return m.group(0) if m else "?"

def log_clear():
    adb("logcat", "-c")

def log(tag="QA"):
    return adb("logcat", "-d", "-s", f"{tag}:*", check=False)
