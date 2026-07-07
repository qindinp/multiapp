#!/usr/bin/env python3
import argparse
import json
from pathlib import Path


PREFIX = "RN_CAPTURE "


def load_records(path: Path):
    records = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), 1):
        index = line.find(PREFIX)
        if index < 0:
            continue
        payload = line[index + len(PREFIX):].strip()
        try:
            record = json.loads(payload)
        except json.JSONDecodeError as exc:
            print(f"skip line {line_no}: {exc}")
            continue
        records.append(record)
    return records


def class_matches(record, target_class: str):
    class_name = record.get("className") or ""
    if class_name == target_class:
        return True
    return target_class in class_name


def summarize(records, target_class: str):
    matched = [record for record in records if class_matches(record, target_class)]
    if not matched:
        print(f"no RegisterNatives capture for {target_class}")
        print(f"total captures={len(records)}")
        return []

    table = []
    for capture_index, record in enumerate(matched, 1):
        print(f"capture[{capture_index}] class={record.get('className')} nMethods={record.get('nMethods')} caller={record.get('caller', {}).get('ptr')}")
        for method in record.get("methods", []):
            row = {
                "captureIndex": capture_index,
                "className": record.get("className"),
                "name": method.get("name"),
                "signature": method.get("signature"),
                "fnPtr": method.get("fnPtr"),
                "module": method.get("module"),
                "path": method.get("path"),
                "base": method.get("base"),
                "offset": method.get("offset"),
                "jiaguBase": method.get("jiaguBase"),
                "jiaguOffset": method.get("jiaguOffset"),
                "symbol": method.get("symbol"),
            }
            table.append(row)
            print(
                "  [{index}] {name} {signature} fn={fnPtr} module={module} offset={offset} jiaguOffset={jiaguOffset} symbol={symbol}".format(
                    index=method.get("index"),
                    name=method.get("name"),
                    signature=method.get("signature"),
                    fnPtr=method.get("fnPtr"),
                    module=method.get("module"),
                    offset=method.get("offset"),
                    jiaguOffset=method.get("jiaguOffset"),
                    symbol=method.get("symbol"),
                )
            )
    return table


def main():
    parser = argparse.ArgumentParser(description="Summarize Frida RegisterNatives captures.")
    parser.add_argument("log", type=Path, help="Frida stdout log containing RN_CAPTURE lines")
    parser.add_argument("--target-class", default="com.yuewen.ywlogin.login.YWLoginManager")
    parser.add_argument("--out-json", type=Path)
    args = parser.parse_args()

    records = load_records(args.log)
    table = summarize(records, args.target_class)
    if args.out_json:
        args.out_json.parent.mkdir(parents=True, exist_ok=True)
        args.out_json.write_text(json.dumps(table, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"wrote={args.out_json}")


if __name__ == "__main__":
    main()
