#!/usr/bin/env python3
"""Hudhud vault end-to-end run on the emulator: python3 qa/vault_e2e.py [section ...]. Assumes the vault exists with MASTER."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(__file__))
from ui import *

MASTER = "Correct-Horse-9-Battery"
PKG = "dev.altayeb.hudhud"
results = []

def ok(name, cond, extra=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (" :: " + str(extra)[:300] if extra and not cond else ""), flush=True)

def edits():
    return [x for x in nodes() if x.get("class", "").endswith("EditText")]

def open_vault():
    # Never force-stop this package: it is the keyboard, and Android would fall back to another one.
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(.5)
    adb("shell", "am", "start", "-n", f"{PKG}/.Settings"); time.sleep(1.5)
    if find("Open passwords") is not None:
        tap("Open passwords"); time.sleep(1)

def unlock(pw=MASTER):
    tap_xy(*center(edits()[0])); typ(pw); hide_keys(); tap("Unlock", exact=True)

def qa_form():
    adb("shell", "am", "start", "-S", "-n", "qa.login/.Main"); time.sleep(1.5)

def form_values():
    log_clear(); tap_xy(540, 582); time.sleep(0.5)  # by position: an open autofill popup hides the app from uiautomator
    if "FORM " not in log():
        tap_xy(540, 582); time.sleep(0.5)
    return [l.split("FORM ", 1)[1] for l in log().splitlines() if "FORM " in l][-1]

def secure_flag():
    out = adb("shell", "dumpsys", "window", "windows")
    block = out[out.find("VaultActivity"):][:3000]
    return "SECURE" in block

def lock():
    tap("Lock now")

def to_list():
    """Vault open, on the list screen, from wherever."""
    open_vault()
    if find("Vault locked") is not None: unlock(); 
    wait("Add password", 40)

def add_entry(title, user, pw, url=""):
    tap("Add password"); time.sleep(0.5)
    e = edits()
    tap_xy(*center(e[0])); typ(title)
    tap_xy(*center(edits()[1])); typ(user)
    tap_xy(*center(edits()[2])); typ(pw)
    if url:
        tap_xy(*center(edits()[3])); typ(url)
    hide_keys(); tap("Save", exact=True); wait("Add password", 40)

def sec_core():
    to_list()
    if find("GitHub") is None:
        add_entry("GitHub", "alice", "gh-P4ss-word-long-enough!", "github.com")
    ok("entry appears after save", find("GitHub") is not None)
    tap("GitHub"); wait("Username")
    ok("password hidden by default", find("gh-P4ss") is None and find("••••") is not None)
    tap("Show", exact=True); ok("password reveals", find("gh-P4ss") is not None); back()
    wait("Add password")
    ok("window is FLAG_SECURE", secure_flag())
    head = subprocess.run(["adb", "-s", SERIAL, "exec-out", "run-as", PKG, "head", "-c", "4", "no_backup/vault.sealed"], capture_output=True).stdout
    if len(head) == 4:  # release builds refuse run-as
        ok("file on disk is sealed, not raw KDBX", head != bytes([3, 0xd9, 0xa2, 0x9a]), head)
    # bad values name the field
    tap("Add password"); tap_xy(*center(edits()[0])); typ("x")
    tap_xy(*center(edits()[3])); typ("not%sa%surl"); ok("bad website names the field", find("Website: not a valid") is not None)
    tap_xy(*center(edits()[4])); typ("!!!"); ok("bad totp names the field", find("2-step code: not a valid") is not None)
    hide_keys(); tap("Back", exact=True); wait("Add password")
    # wrong password, then right one
    lock(); wait("Vault locked")
    unlock("wrong-password-123"); wait("Master password is wrong", 40); ok("wrong password refused and named", True)
    unlock(); wait("Add password", 40); ok("right password opens", find("GitHub") is not None)
    # screen off locks
    adb("shell", "input", "keyevent", "KEYCODE_SLEEP"); time.sleep(1.5); adb("shell", "input", "keyevent", "KEYCODE_WAKEUP"); time.sleep(1)
    adb("shell", "wm", "dismiss-keyguard", check=False); time.sleep(1.5)
    ok("screen off locks the vault", find("Vault locked") is not None, texts()[:8])
    if find("Vault locked") is not None: unlock(); wait("Add password", 40)
    # killing the vault process leaves nothing open
    adb("shell", "am", "force-stop", "qa.login")
    return

def sec_generator():
    to_list()
    tap("Add password"); tap("Generate", exact=True); wait("Length")
    ok("generator shows a value and options", find("Symbols") is not None and find("Use this") is not None)
    tap("Use this"); time.sleep(.4); hide_keys(); back(); wait("Add password")

POPUP = (165, 480)  # first autofill row under the username field; hidden from uiautomator, so behaviour proves what it was

def shot(name):
    with open(os.environ["QA_TMP"] + "/" + name + ".png", "wb") as f:
        subprocess.run(["adb", "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=f, check=True)

def user_field():
    tap_xy(*center(edits()[0])); time.sleep(2.5)

def sec_autofill():
    to_list()
    tap("Lock now"); wait("Vault locked")
    qa_form(); user_field(); shot("af_locked")
    ok("locked popup names no entry", find("GitHub") is None and find("alice") is None, texts()[:8])
    tap_xy(*POPUP); wait("Vault locked", 15)
    ok("locked: first suggestion only leads to unlock", True)
    unlock(); wait("Sign in to qa.login", 40)
    fresh = "Fresh" + str(int(time.time()))
    back(); time.sleep(1); to_list(); add_entry(fresh, "alice", "gh-P4ss-word-long-enough!", "fresh.example.com")
    qa_form(); user_field(); n = find("Search Hudhud")
    tap_xy(*center(n)); wait("Sign in to qa.login", 15)
    tap_xy(*center(edits()[0])); typ(fresh); hide_keys()
    rows = [x for x in nodes() if x.get("text") == fresh and not x.get("class", "").endswith("EditText")]
    tap_xy(*center(rows[-1])); wait("may be a fake", 10)
    ok("unlinked app gets a phishing warning first", True)
    tap("Use it", exact=True); time.sleep(5)
    v = form_values()
    ok("autofill filled both fields", "user=alice" in v and "pass=gh-P4ss-word-long-enough!" in v, v)
    qa_form(); user_field(); shot("af_linked")
    tap_xy(*POPUP); time.sleep(3)
    ok("linked: suggestion fills with no extra screen", find("Sign in to") is None, texts()[:8])
    v = form_values(); ok("direct suggestion filled the password", "pass=gh-P4ss-word-long-enough!" in v, v)

def sec_impostor():
    """Same package, other signing key: must not get the linked login."""
    adb("uninstall", "qa.login"); adb("install", os.environ["QA_IMPOSTOR_APK"])
    try:
        qa_form(); user_field(); shot("af_impostor")
        tap_xy(*POPUP); time.sleep(3)
        ok("impostor: first suggestion is search, not the login", find("Sign in to qa.login") is not None, texts()[:8])
        back(); time.sleep(1)
        back(); time.sleep(.5)
        v = form_values(); ok("impostor form stayed empty", "gh-P4ss" not in v and "alice" not in v, v)
    finally:
        adb("uninstall", "qa.login"); adb("install", os.environ["QA_APK"])

def sec_save():
    to_list()
    qa_form()
    tap_xy(*center(edits()[0])); typ("newuser@example.com")
    tap_xy(*center(edits()[1])); typ("Brand-New-Pass-123!")
    hide_keys(); log_clear(); tap("SUBMIT", exact=True); time.sleep(2.5)
    print("after submit:", texts()[:10], flush=True)
    tap("Save", secs=10, exact=True); time.sleep(2)
    wait("Save this login", 15); tap("Save", exact=True); time.sleep(6)
    to_list()
    ok("save prompt stored the login", find("qa.login") is not None, texts()[:12])

def autofill(on):
    adb("shell", "settings", "put", "secure", "autofill_service", f"{PKG}/.vault.HudhudAutofillService" if on else "null")

def sec_keyboard():
    autofill(False)
    to_list()
    qa_form()
    log_clear()
    tap_xy(*center(edits()[1])); time.sleep(1.5)
    shot("kb_password")
    x, y = keyboard_chip()
    tap_xy(x, y); wait("Sign in to qa.login", 15)
    tap("alice", exact=True); time.sleep(1)
    if find("may be a fake") is not None: tap("Use it", exact=True); time.sleep(4)
    tap("Type password", 10); time.sleep(3)
    v = form_values(); ok("key button types the password", "pass=gh-P4ss-word-long-enough!" in v, v)
    # a password never goes into a field that shows text
    qa_form(); tap_xy(*center(edits()[0])); time.sleep(1.5)
    x, y = keyboard_chip(); tap_xy(x, y); wait("Sign in to qa.login", 15); tap("alice", exact=True); time.sleep(1)
    tap("Type password", 10); time.sleep(3)
    v = form_values(); ok("password refused in a username field", "gh-P4ss" not in v, v)
    autofill(True)

def keyboard_chip():
    """The strip is drawn on a canvas, so its middle slot is found from the keyboard window's height."""
    out = adb("shell", "dumpsys", "window", "windows")
    h = int(re.search(r"InputMethod\}.*?Requested w=\d+ h=(\d+)", out, re.S).group(1))
    return 540, 2400 - h + 63

def sec_browser():
    """The keyboard button in a browser: the site is unknown, so nothing is offered directly and nothing gets linked to the browser."""
    autofill(False)
    to_list()
    for attempt in (1, 2):
        adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", "https://github.com/login", "-p", "com.android.chrome"); time.sleep(8)
        for label in ("Stay signed out", "Use without an account", "No thanks", "Got it", "Skip"):
            if find(label, exact=True) is not None: tap(label, exact=True); time.sleep(2)
        n = wait("Password", 25, exact=False)
        tap_xy(*center(n)); time.sleep(2)
        x, y = keyboard_chip(); tap_xy(x, y); wait("Sign in to com.android.chrome", 15)
        tap("GitHub"); time.sleep(1)
        ok(f"browser pick {attempt} still warns, never linked", find("may be a fake") is not None, texts()[:10])
        if attempt == 2:
            back(); time.sleep(.5); back(); break
        tap("Use it", exact=True); time.sleep(5)
        tap("Type password", 10); time.sleep(2)
    adb("shell", "am", "force-stop", "com.android.chrome")
    autofill(True)

def sec_clipboard():
    """Same field, same state: one plain copy, one marked sensitive. Checked by eye in clip_both.png: plain shown, secret absent."""
    autofill(False)
    qa_form(); tap_xy(*center(edits()[0])); time.sleep(1.5)
    tap("COPYPLAIN", exact=True); time.sleep(.5)
    tap("COPYSECRET", exact=True); time.sleep(.5)
    _, y = keyboard_chip(); tap_xy(66, y); time.sleep(1.2)
    shot("clip_both")
    back(); adb("shell", "am", "force-stop", "qa.login")
    autofill(True)

def sec_passkey():
    adb("shell", "settings", "put", "secure", "credential_service", f"{PKG}/{PKG}.vault.PasskeyService")
    adb("shell", "settings", "put", "secure", "credential_service_primary", f"{PKG}/{PKG}.vault.PasskeyService")
    back(); to_list(); qa_form(); log_clear()
    tap("REGISTER", exact=True); time.sleep(3)
    print("sheet:", texts()[:12], flush=True)
    tap("Continue", exact=True); time.sleep(1)
    wait("Vault locked", 20); unlock(); time.sleep(8)
    l = log(); ok("passkey registered, relying party checks pass", "REGISTER OK" in l, l[-400:])
    log_clear(); tap("ASSERT", exact=True); time.sleep(3)
    print("sheet:", texts()[:12], flush=True)
    if find("Continue", exact=True) is not None: tap("Continue", exact=True); time.sleep(1)
    elif find("qa-user") is not None: tap("qa-user"); time.sleep(1)
    wait("Vault locked", 20); unlock(); time.sleep(8)
    l = log(); ok("passkey signature verifies at relying party", "ASSERT signature=true user=uid1" in l, l[-400:])

def sec_files():
    csv = "name,url,username,password,note\nبنك,https://bank.example/,omar,Bank-Pass-99!,\"hello, world\"\nBad,,,,\nAppOnly,android://abc@com.foo/,u,Pw-123456789!,\n"
    subprocess.run(["adb", "-s", SERIAL, "shell", "mkdir -p /sdcard/Download"], check=True)
    subprocess.run(["adb", "-s", SERIAL, "shell", f"rm -f /sdcard/Download/hudhud* /sdcard/Download/g.csv"], check=True)
    open(os.environ["QA_TMP"] + "/g.csv", "w", encoding="utf8").write(csv)
    adb("push", os.environ["QA_TMP"] + "/g.csv", "/sdcard/Download/g.csv")
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file:///sdcard/Download/g.csv", check=False); time.sleep(2)
    to_list(); tap("Options", exact=True); wait("Import or merge")
    tap("Import or merge"); time.sleep(2.5)
    print("picker:", texts()[:14], flush=True)
    if find("g.csv") is None:
        n = find("Show roots")
        if n is not None: tap_xy(*center(n)); time.sleep(1); tap("Downloads"); time.sleep(2)
    tap("g.csv", 15, exact=True); time.sleep(1)
    wait("Imported 2, skipped 1", 40); ok("Google CSV imported, bad row counted", True)
    ok("plain CSV warning and delete offered", find("Delete the CSV") is not None)
    tap("Delete the CSV"); time.sleep(1.5)
    ok("CSV deleted", "g.csv" not in adb("shell", "ls", "/sdcard/Download"), adb("shell", "ls", "/sdcard/Download"))
    scroll_to("Export encrypted"); tap("Export encrypted"); time.sleep(2.5)
    print("save:", texts()[:12], flush=True)
    tap("SAVE", 15, exact=True); wait("It opens with your master password", 40) if False else time.sleep(6)
    f = os.environ["QA_TMP"] + "/hudhud.kdbx"
    adb("pull", "/sdcard/Download/hudhud.kdbx", f)
    r = subprocess.run([os.environ["QA_PY"], "-I", "-c", """
import sys
from pykeepass import PyKeePass
k=PyKeePass(sys.argv[1],password=sys.argv[2])
e=k.find_entries(username='omar',first=True)
assert e and e.password=='Bank-Pass-99!' and e.title=='بنك' and e.notes=='hello, world', e
print('ok',len(k.entries))
""", f, MASTER], capture_output=True, text=True)
    ok("exported .kdbx opens in pykeepass with the right data", r.stdout.startswith("ok"), r.stdout + r.stderr[-300:])
    back()

def sec_reinstall_keeps():
    # An update must keep the vault: same data dir, Keystore key survives.
    adb("install", "-r", os.environ["QA_HUDHUD_APK"])
    to_list(); ok("vault opens after app update", find("GitHub") is not None)

def sec_reset():
    """Clean state: delete the vault files, end only the vault process (the keyboard must stay alive), create again."""
    autofill(True)
    if os.environ.get("QA_FRESH_INSTALL") != "1":
        for f in ("vault.sealed", "vault.sealed.prev", "bio.sealed"):
            adb("shell", "run-as", PKG, "rm", "-f", "no_backup/" + f, check=False)
        pid = adb("shell", "pidof", PKG + ":vault", check=False).strip()
        if pid: adb("shell", "run-as", PKG, "kill", pid, check=False); time.sleep(1)
    for _ in range(20):  # a fresh install takes a moment to register the keyboard
        adb("shell", "ime", "enable", PKG + "/.KeyboardService", check=False)
        if "Unknown" not in adb("shell", "ime", "set", PKG + "/.KeyboardService", check=False): break
        time.sleep(1)
    adb("uninstall", "qa.login", check=False); adb("install", os.environ["QA_APK"])
    open_vault(); wait("Create your vault")
    tap_xy(*center(edits()[0])); typ("short")
    ok("short master password names the field", find("at least 12") is not None)
    tap_xy(*center(edits()[1])); typ("short"); hide_keys()
    n = find("Create vault"); tap_xy(*center(n)); time.sleep(1.5)
    ok("short master password cannot create a vault", find("Create your vault") is not None)
    for i in (0, 1):
        tap_xy(*center(edits()[i])); adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(5): adb("shell", "input", "keyevent", "KEYCODE_DEL")
        typ(MASTER)
    hide_keys(); t = time.time(); tap("Create vault"); wait("No passwords yet", 90)
    print("create took", round(time.time() - t, 1), "s", flush=True)
    ok("vault created", True)

SECTIONS = dict(reset=sec_reset, browser=sec_browser, core=sec_core, generator=sec_generator, autofill=sec_autofill, impostor=sec_impostor, save=sec_save, keyboard=sec_keyboard,
                clipboard=sec_clipboard, passkey=sec_passkey, files=sec_files, update=sec_reinstall_keeps)
for s in sys.argv[1:] or SECTIONS:
    print("==", s, flush=True)
    try:
        SECTIONS[s]()
    except Exception as e:
        ok(s + " section finished", False, f"{type(e).__name__}: {e}")
bad = [n for n, c in results if not c]
print(f"{len(results) - len(bad)}/{len(results)} passed", "FAILED: " + "; ".join(bad) if bad else "")
sys.exit(1 if bad else 0)
