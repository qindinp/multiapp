#!/usr/bin/env python3
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "root-dump"
TARGET_CLASS = "com.yuewen.ywlogin.login.YWLoginManager"


def run(args, **kwargs):
    result = subprocess.run(args, cwd=ROOT, text=True, capture_output=True, **kwargs)
    return result


def require_ok(result, label):
    if result.returncode != 0:
        print(f"{label}=failed rc={result.returncode}")
        if result.stdout:
            print(result.stdout)
        if result.stderr:
            print(result.stderr, file=sys.stderr)
        sys.exit(result.returncode)
    print(f"{label}=ok")


def sample_capture_record():
    return {
        "event": "RegisterNatives",
        "when": "2026-06-24T00:00:00.000Z",
        "className": TARGET_CLASS,
        "nMethods": 4,
        "clazz": "0x1234",
        "methodsPtr": "0x5678",
        "caller": {"ptr": "0x7000123400", "module": "libjiagu_vip.so", "offset": "0x123400"},
        "methods": [
            {
                "index": 0,
                "name": "getInstance",
                "signature": "()Lcom/yuewen/ywlogin/login/YWLoginManager;",
                "fnPtr": "0x700010d000",
                "module": "libjiagu_vip.so",
                "path": "/data/app/com.qq.reader/lib/arm64/libjiagu_vip.so",
                "base": "0x7000000000",
                "offset": "0x10d000",
                "jiaguBase": "0x7000000000",
                "jiaguOffset": "0x10d000",
                "symbol": None,
            },
            {
                "index": 1,
                "name": "pwdLogin",
                "signature": "(Landroid/app/Activity;Ljava/lang/String;Ljava/lang/String;Lcom/yuewen/ywlogin/login/YWCallBack;)V",
                "fnPtr": "0x7000111000",
                "module": None,
                "path": None,
                "base": None,
                "offset": None,
                "jiaguBase": "0x7000000000",
                "jiaguOffset": "0x111000",
                "symbol": None,
            },
            {
                "index": 2,
                "name": "sendPhoneCode",
                "signature": "(Landroid/content/Context;Ljava/lang/String;IILcom/yuewen/ywlogin/login/YWCallBack;)V",
                "fnPtr": "0x7000112000",
                "module": None,
                "path": None,
                "base": None,
                "offset": None,
                "jiaguBase": "0x7000000000",
                "jiaguOffset": "0x112000",
                "symbol": None,
            },
            {
                "index": 3,
                "name": "qrCodeV2",
                "signature": "(Lcom/yuewen/ywlogin/callbacks/DefaultYWCallback;)V",
                "fnPtr": "0x7000113000",
                "module": None,
                "path": None,
                "base": None,
                "offset": None,
                "jiaguBase": "0x7000000000",
                "jiaguOffset": "0x113000",
                "symbol": None,
            },
        ],
    }


def write_sample_log(path: Path):
    record = sample_capture_record()
    path.write_text("noise before\nRN_CAPTURE " + json.dumps(record, ensure_ascii=False) + "\nnoise after\n", encoding="utf-8")


def generate_replay(template: Path, table_json: Path, output: Path):
    source = template.read_text(encoding="utf-8")
    table = table_json.read_text(encoding="utf-8")
    output.write_text(source.replace("JSON.parse('[]')", table), encoding="utf-8")


def main():
    with tempfile.TemporaryDirectory(prefix="rn-toolchain-", dir=ROOT / ".tmp") as tmp_name:
        tmp = Path(tmp_name)
        log_path = tmp / "frida-stdout.txt"
        table_path = tmp / "ywlogin-register-table.json"
        generated_js = tmp / "frida_ywlogin_register_replay.generated.js"
        empty_table = tmp / "empty.json"

        write_sample_log(log_path)

        analyze = run([
            sys.executable,
            str(TOOLS / "analyze_rn_capture.py"),
            str(log_path),
            "--out-json",
            str(table_path),
        ])
        require_ok(analyze, "analyze_sample_capture")

        validate = run([
            sys.executable,
            str(TOOLS / "validate_ywlogin_register_table.py"),
            str(table_path),
            "--strict-startup",
            "--strict-login",
        ])
        require_ok(validate, "validate_sample_table")

        empty_table.write_text("[]", encoding="utf-8")
        empty_validate = run([
            sys.executable,
            str(TOOLS / "validate_ywlogin_register_table.py"),
            str(empty_table),
            "--strict-startup",
        ])
        if empty_validate.returncode == 0:
            print("validate_empty_table=unexpected-ok")
            sys.exit(1)
        print(f"validate_empty_table=failed-as-expected rc={empty_validate.returncode}")

        generate_replay(TOOLS / "frida_ywlogin_register_replay.js", table_path, generated_js)
        if shutil.which("node") is not None:
            node_check = run(["node", "--check", str(generated_js)])
            require_ok(node_check, "node_check_generated_replay")
        else:
            print("node_check_generated_replay=skipped node-not-found")

        if shutil.which("powershell") is not None:
            version_tag = Path(tmp.name).name + "\\dryrun"
            dry_run = run([
                "powershell",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(TOOLS / "run_qqreader_ywlogin_replay.ps1"),
                "-CaptureJson",
                str(table_path),
                "-VersionTag",
                version_tag,
                "-DryRun",
            ])
            require_ok(dry_run, "powershell_replay_dryrun")
        else:
            print("powershell_replay_dryrun=skipped powershell-not-found")

        print(f"tmp={tmp}")
        print("rn_replay_toolchain=ok")


if __name__ == "__main__":
    main()
