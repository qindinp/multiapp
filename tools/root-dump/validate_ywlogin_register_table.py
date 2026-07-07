#!/usr/bin/env python3
import argparse
import json
import sys
from pathlib import Path


TARGET_CLASS = "com.yuewen.ywlogin.login.YWLoginManager"
BOOTSTRAP_METHODS = {
    "getInstance",
    "registerParameter",
    "resetParameter",
    "setDefaultParameters",
    "fetchSettings",
}
LOGIN_ACTION_METHODS = {
    "pwdLogin",
    "sendPhoneCode",
    "qrCodeV2",
}


def load_rows(path: Path):
    data = json.loads(path.read_text(encoding="utf-8", errors="replace"))
    if not isinstance(data, list):
        raise ValueError("top-level JSON must be a list")
    rows = []
    for item in data:
        if not isinstance(item, dict):
            continue
        if item.get("className") != TARGET_CLASS:
            continue
        rows.append(item)
    return rows


def method_key(row):
    return f"{row.get('name')} {row.get('signature')}"


def usable_rows(rows):
    return [row for row in rows if row.get("name") and row.get("signature") and row.get("jiaguOffset")]


def summarize(rows):
    by_name = {}
    for row in rows:
        by_name.setdefault(row.get("name"), []).append(row)

    usable = usable_rows(rows)
    usable_by_name = {}
    for row in usable:
        usable_by_name.setdefault(row.get("name"), []).append(row)

    print(f"targetClass={TARGET_CLASS}")
    print(f"rows={len(rows)} usableWithJiaguOffset={len(usable)}")
    if rows:
        print("methods:")
        for row in rows:
            print(
                "  {key} fn={fn} module={module} offset={offset} jiaguOffset={jiaguOffset}".format(
                    key=method_key(row),
                    fn=row.get("fnPtr"),
                    module=row.get("module"),
                    offset=row.get("offset"),
                    jiaguOffset=row.get("jiaguOffset"),
                )
            )

    bootstrap_present = sorted(BOOTSTRAP_METHODS.intersection(usable_by_name.keys()))
    actions_present = sorted(LOGIN_ACTION_METHODS.intersection(usable_by_name.keys()))
    bootstrap_missing = sorted(BOOTSTRAP_METHODS.difference(usable_by_name.keys()))
    actions_missing = sorted(LOGIN_ACTION_METHODS.difference(usable_by_name.keys()))

    print(f"bootstrapPresent={','.join(bootstrap_present) if bootstrap_present else '<none>'}")
    print(f"bootstrapMissing={','.join(bootstrap_missing) if bootstrap_missing else '<none>'}")
    print(f"loginActionsPresent={','.join(actions_present) if actions_present else '<none>'}")
    print(f"loginActionsMissing={','.join(actions_missing) if actions_missing else '<none>'}")

    can_fix_startup_crash = "getInstance" in usable_by_name
    can_try_password_or_sms = bool({"pwdLogin", "sendPhoneCode"}.intersection(usable_by_name.keys()))
    can_try_qr = "qrCodeV2" in usable_by_name

    print(f"canFixStartupCrash={'yes' if can_fix_startup_crash else 'no'}")
    print(f"canTryPasswordOrSmsLogin={'yes' if can_try_password_or_sms else 'no'}")
    print(f"canTryQrLogin={'yes' if can_try_qr else 'no'}")
    return {
        "can_fix_startup_crash": can_fix_startup_crash,
        "can_try_password_or_sms": can_try_password_or_sms,
        "can_try_qr": can_try_qr,
        "actions_missing": actions_missing,
    }


def main():
    parser = argparse.ArgumentParser(description="Validate captured YWLogin RegisterNatives replay table.")
    parser.add_argument("json", type=Path)
    parser.add_argument("--strict-startup", action="store_true", help="exit non-zero unless getInstance has jiaguOffset")
    parser.add_argument("--strict-login", action="store_true", help="exit non-zero unless pwdLogin or sendPhoneCode has jiaguOffset")
    args = parser.parse_args()

    rows = load_rows(args.json)
    result = summarize(rows)
    if args.strict_startup and not result["can_fix_startup_crash"]:
        print("strictStartup=failed")
        return 2
    if args.strict_login and not result["can_try_password_or_sms"]:
        print("strictLogin=failed")
        return 3
    print("validation=ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
