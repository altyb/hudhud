#!/usr/bin/env python3
"""Exchange vault files with pykeepass, an independent KDBX implementation. See InteropTest.kt."""
import sys
from pykeepass import PyKeePass, create_database

PW = "pässword-كلمة-🔑"
step, folder = sys.argv[1], sys.argv[2]
if step == "write":
    kp = create_database(f"{folder}/theirs.kdbx", password=PW)
    assert kp.version[0] == 4, kp.version
    team = kp.add_group(kp.root_group, "Team")
    sub = kp.add_group(team, "Sub")
    e = kp.add_entry(sub, "من البايثون", "py-user", "py-pass-🔑", url="https://py.example/")
    e.set_custom_property("PyCustom", "custom-value")
    kp.save()
    print("wrote", kp.version, kp.kdbx.header.value.dynamic_header.kdf_parameters.data.dict["$UUID"].value.hex())
else:
    kp = PyKeePass(f"{folder}/ours.kdbx", password=PW)
    assert kp.version == (4, 0), kp.version
    assert kp.database_name == "Interop"
    e = kp.find_entries(username="user@example.com", first=True)
    assert e and e.title == "مصرف <&>\"'" and e.password == "s3cret-🔑-كلمة", e
    assert e.url == "https://example.com/login" and e.notes == "line1\nline2"
    assert e.get_custom_property("Custom") == "value"
    assert e.group.path == ["Work", "Mail"], e.group.path
    assert int(e.mtime.timestamp()) == 1_700_000_000, e.mtime
    assert e.otp.startswith("otpauth://")
    assert kp.find_entries(title="root", first=True).password == "x"
    kp.save(f"{folder}/resaved.kdbx")
    print("read ok", kp.version, "kdf", kp.kdbx.header.value.dynamic_header.kdf_parameters.data.dict["$UUID"].value.hex())
