#!/usr/bin/env python3
import argparse
import hashlib
import struct
import sys
import zipfile
from pathlib import Path


def uleb(data, off):
    result = 0
    shift = 0
    start = off
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if (b & 0x80) == 0:
            return result, off
        shift += 7
        if off - start > 5:
            raise ValueError("bad uleb128")


def get_string(data, string_ids_off, idx):
    (string_data_off,) = struct.unpack_from("<I", data, string_ids_off + idx * 4)
    _, off = uleb(data, string_data_off)
    end = data.index(0, off)
    return data[off:end].decode("utf-8", errors="replace")


def type_name(data, string_ids_off, type_ids_off, idx):
    (string_idx,) = struct.unpack_from("<I", data, type_ids_off + idx * 4)
    return get_string(data, string_ids_off, string_idx)


def proto_desc(data, string_ids_off, type_ids_off, proto_ids_off, idx):
    shorty_idx, return_type_idx, params_off = struct.unpack_from("<III", data, proto_ids_off + idx * 12)
    ret = type_name(data, string_ids_off, type_ids_off, return_type_idx)
    params = []
    if params_off:
        (size,) = struct.unpack_from("<I", data, params_off)
        for i in range(size):
            (type_idx,) = struct.unpack_from("<H", data, params_off + 4 + i * 2)
            params.append(type_name(data, string_ids_off, type_ids_off, type_idx))
    return "(" + "".join(params) + ")" + ret


def update_dex_hashes(dex):
    sig = hashlib.sha1(dex[32:]).digest()
    dex[12:32] = sig
    import zlib
    checksum = zlib.adler32(dex[12:]) & 0xFFFFFFFF
    struct.pack_into("<I", dex, 8, checksum)


def copy_zip_with_patched_dex(in_apk, out_apk, patched_dex):
    with zipfile.ZipFile(in_apk, "r") as zin, zipfile.ZipFile(out_apk, "w") as zout:
        for info in zin.infolist():
            data = patched_dex if info.filename == "classes.dex" else zin.read(info.filename)
            new_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            new_info.compress_type = info.compress_type
            new_info.comment = info.comment
            new_info.extra = info.extra
            new_info.internal_attr = info.internal_attr
            new_info.external_attr = info.external_attr
            new_info.create_system = info.create_system
            zout.writestr(new_info, data)


def patch_dex(dex, mode):
    if dex[:8] not in (b"dex\n035\0", b"dex\n037\0", b"dex\n038\0", b"dex\n039\0"):
        raise ValueError("not a supported dex")

    string_ids_size, string_ids_off = struct.unpack_from("<II", dex, 0x38)
    type_ids_size, type_ids_off = struct.unpack_from("<II", dex, 0x40)
    proto_ids_size, proto_ids_off = struct.unpack_from("<II", dex, 0x48)
    method_ids_size, method_ids_off = struct.unpack_from("<II", dex, 0x58)
    class_defs_size, class_defs_off = struct.unpack_from("<II", dex, 0x60)

    target_class_idx = None
    for i in range(type_ids_size):
        if type_name(dex, string_ids_off, type_ids_off, i) == "Lcom/stub/StubApp;":
            target_class_idx = i
            break
    if target_class_idx is None:
        raise ValueError("Lcom/stub/StubApp; not found")

    method_names = []
    method_protos = []
    method_classes = []
    for i in range(method_ids_size):
        class_idx, proto_idx, name_idx = struct.unpack_from("<HHI", dex, method_ids_off + i * 8)
        method_classes.append(class_idx)
        method_names.append(get_string(dex, string_ids_off, name_idx))
        method_protos.append(proto_desc(dex, string_ids_off, type_ids_off, proto_ids_off, proto_idx))

    interface20_idx = None
    target_load_code_off = None

    for i in range(class_defs_size):
        class_idx, _, _, _, _, _, class_data_off, _ = struct.unpack_from("<IIIIIIII", dex, class_defs_off + i * 32)
        if class_idx != target_class_idx or class_data_off == 0:
            continue
        off = class_data_off
        static_fields_size, off = uleb(dex, off)
        instance_fields_size, off = uleb(dex, off)
        direct_methods_size, off = uleb(dex, off)
        virtual_methods_size, off = uleb(dex, off)
        for _ in range(static_fields_size + instance_fields_size):
            _, off = uleb(dex, off)
            _, off = uleb(dex, off)
        for method_count in (direct_methods_size, virtual_methods_size):
            method_idx = 0
            for _ in range(method_count):
                diff, off = uleb(dex, off)
                method_idx += diff
                _, off = uleb(dex, off)
                code_off, off = uleb(dex, off)
                if method_idx >= len(method_names) or method_classes[method_idx] != target_class_idx:
                    continue
                name = method_names[method_idx]
                proto = method_protos[method_idx]
                if name == "interface20" and proto == "()Z":
                    interface20_idx = method_idx
                if name == "load" and proto == "(Landroid/app/Application;Landroid/content/Context;)V":
                    target_load_code_off = code_off

    if interface20_idx is None:
        raise ValueError("StubApp.interface20()Z not found")
    if target_load_code_off is None:
        raise ValueError("StubApp.load(Application, Context) code not found")

    registers, ins_size, outs_size, tries_size = struct.unpack_from("<HHHH", dex, target_load_code_off)
    debug_info_off, insns_size = struct.unpack_from("<II", dex, target_load_code_off + 8)
    insns_off = target_load_code_off + 16
    code = dex[insns_off:insns_off + insns_size * 2]

    hits = []
    pos = 0
    while pos + 2 <= len(code):
        op = code[pos]
        if op in (0x71, 0x77) and pos + 4 <= len(code):
            method_idx = struct.unpack_from("<H", code, pos + 2)[0]
            if method_idx == interface20_idx:
                hits.append(pos)
        # enough for this targeted patch: advance one code unit unless the
        # candidate is found; the post-call scan below is pattern-based.
        pos += 2

    if not hits:
        raise ValueError("invoke-static StubApp.interface20() not found in load()")
    if len(hits) > 1:
        raise ValueError(f"unexpected multiple interface20 calls in load(): {hits}")

    call_pos = hits[0]
    scan_start = call_pos + 6
    patch_pos = None
    old = None
    branch_off = None
    for p in range(scan_start, min(scan_start + 24, len(code) - 3), 2):
        op = code[p]
        if op in (0x38, 0x39):  # if-eqz / if-nez, format 21t
            patch_pos = p
            old = bytes(code[p:p + 4])
            branch_off = struct.unpack_from("<h", code, p + 2)[0]
            break
    if patch_pos is None:
        raise ValueError("post-interface20 conditional branch not found")

    abs_patch = insns_off + patch_pos
    if mode == "skip-exit":
        # Java was: if (!interface20()) System.exit(1); success label is the
        # existing branch target. Replace if-* with goto/16 to always skip exit.
        dex[abs_patch:abs_patch + 4] = bytes([0x29, 0x00]) + struct.pack("<h", branch_off)
        new_bytes = dex[abs_patch:abs_patch + 4].hex()
    elif mode == "loop-interface20":
        # Keep the original if-nez. Replace the exit block with:
        #   goto/16 interface20_call
        #   nop
        #   nop
        # This gives an external root patcher time to hot-patch native gates;
        # once interface20 returns true, execution falls through normally.
        exit_pos = patch_pos + 4
        exit_abs = insns_off + exit_pos
        exit_old = bytes(dex[exit_abs:exit_abs + 8])
        goto_units = (call_pos // 2) - (exit_pos // 2)
        dex[exit_abs:exit_abs + 8] = bytes([0x29, 0x00]) + struct.pack("<h", goto_units) + b"\x00\x00\x00\x00"
        old = old + b";exit=" + exit_old
        new_bytes = dex[exit_abs:exit_abs + 8].hex()
    else:
        raise ValueError(f"unknown mode: {mode}")
    update_dex_hashes(dex)
    return {
        "interface20_idx": interface20_idx,
        "load_code_off": target_load_code_off,
        "registers": registers,
        "insns_size": insns_size,
        "call_pos": call_pos,
        "patch_pos": patch_pos,
        "old": old.hex(),
        "new": new_bytes,
        "branch_off": branch_off,
        "mode": mode,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("skip-exit", "loop-interface20"), default="skip-exit")
    parser.add_argument("input_apk")
    parser.add_argument("output_apk")
    args = parser.parse_args()

    in_apk = Path(args.input_apk)
    out_apk = Path(args.output_apk)
    with zipfile.ZipFile(in_apk, "r") as z:
        dex = bytearray(z.read("classes.dex"))
    info = patch_dex(dex, args.mode)
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    copy_zip_with_patched_dex(in_apk, out_apk, bytes(dex))
    for k, v in info.items():
        print(f"{k}={v}")
    print(f"wrote={out_apk}")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)
