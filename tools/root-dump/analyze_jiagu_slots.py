#!/usr/bin/env python3
import argparse
import re
import struct
from dataclasses import dataclass
from pathlib import Path


SLOTS = [
    ("payload_slot_253010", 0x253010),
    ("payload_slot_253018", 0x253018),
    ("token_manager_253148", 0x253148),
    ("seed_table_253150", 0x253150),
    ("registry_cache_2531b0", 0x2531B0),
]


@dataclass
class Segment:
    start: int
    end: int
    path: Path
    label: str


def parse_int(text: str) -> int:
    return int(text, 16 if text.lower().startswith("0x") else 10)


def detect_base(dump_dir: Path, explicit_base: int | None) -> int:
    if explicit_base is not None:
        return explicit_base

    custom_maps = dump_dir / "jiagu-runtime-maps.txt"
    if custom_maps.exists():
        for line in custom_maps.read_text(errors="ignore").splitlines():
            m = re.match(r"base=0x([0-9a-fA-F]+)", line)
            if m:
                return int(m.group(1), 16)

    raw_maps = dump_dir / "maps.txt"
    if raw_maps.exists():
        best = None
        for line in raw_maps.read_text(errors="ignore").splitlines():
            if "libjiagu_vip.so" not in line:
                continue
            m = re.match(r"([0-9a-fA-F]+)-([0-9a-fA-F]+)\s+\S+\s+([0-9a-fA-F]+)", line)
            if not m:
                continue
            start = int(m.group(1), 16)
            if best is None or start < best:
                best = start
        if best is not None:
            return best

    raise SystemExit("Could not detect Jiagu base. Pass --base 0x...")


def collect_segments(dump_dir: Path, base: int) -> list[Segment]:
    segments: list[Segment] = []
    ordered_bss_ranges: list[tuple[int, int, str]] = []
    raw_maps = dump_dir / "maps.txt"
    if raw_maps.exists():
        for line in raw_maps.read_text(errors="ignore").splitlines():
            if "[anon:.bss]" not in line:
                continue
            m = re.match(r"([0-9a-fA-F]+)-([0-9a-fA-F]+)\s+(\S+)", line)
            if not m:
                continue
            start = int(m.group(1), 16)
            end = int(m.group(2), 16)
            if end > start:
                ordered_bss_ranges.append((start, end, line.strip()))

    for path in sorted(dump_dir.glob("*.bin")):
        name = path.name
        m_abs = re.search(r"([0-9a-fA-F]{8,16})-([0-9a-fA-F]{8,16})", name)
        m_rel = re.search(r"rel_([0-9a-fA-F]+)-([0-9a-fA-F]+)-", name)
        m_seq = re.fullmatch(r"r(\d+)\.bin", name)
        if m_rel:
            rel_start = int(m_rel.group(1), 16)
            rel_end = int(m_rel.group(2), 16)
            if rel_end <= rel_start:
                continue
            segments.append(Segment(base + rel_start, base + rel_end, path, f"rel:{rel_start:x}-{rel_end:x}"))
        elif m_abs:
            start = int(m_abs.group(1), 16)
            end = int(m_abs.group(2), 16)
            if end <= start:
                continue
            segments.append(Segment(start, end, path, f"abs:{start:x}-{end:x}"))
        elif m_seq:
            idx = int(m_seq.group(1))
            if 0 <= idx < len(ordered_bss_ranges):
                start, end, _ = ordered_bss_ranges[idx]
                segments.append(Segment(start, end, path, f"maps-bss[{idx}]:{start:x}-{end:x}"))
    return segments


def read_u64(seg: Segment, addr: int) -> int | None:
    if not (seg.start <= addr and addr + 8 <= seg.end):
        return None
    data = seg.path.read_bytes()
    off = addr - seg.start
    if off + 8 > len(data):
        return None
    return struct.unpack_from("<Q", data, off)[0]


def read_qwords_at(segments: list[Segment], addr: int) -> list[tuple[Segment, tuple[int, int, int, int]]] | None:
    hits = []
    for seg in segments:
        if not (seg.start <= addr and addr + 32 <= seg.end):
            continue
        data = seg.path.read_bytes()
        off = addr - seg.start
        if off + 32 > len(data):
            continue
        hits.append((seg, struct.unpack_from("<QQQQ", data, off)))
    return hits or None


def ascii_hint(values: tuple[int, int, int, int]) -> str:
    raw = b"".join(v.to_bytes(8, "little", signed=False) for v in values)
    chars = []
    for b in raw[:48]:
        chars.append(chr(b) if 32 <= b < 127 else ".")
    return "".join(chars).rstrip(".")


def main() -> int:
    parser = argparse.ArgumentParser(description="Read Jiagu runtime slot values from offline dump directories.")
    parser.add_argument("dump_dir", type=Path)
    parser.add_argument("--base", type=parse_int)
    parser.add_argument("--slot", action="append", help="Extra slot as name=0xOFF or 0xOFF")
    args = parser.parse_args()

    dump_dir = args.dump_dir
    if not dump_dir.exists():
        raise SystemExit(f"dump dir not found: {dump_dir}")
    if not any(dump_dir.glob("*.bin")):
        children = [p for p in dump_dir.iterdir() if p.is_dir()]
        if len(children) == 1 and any(children[0].glob("*.bin")):
            dump_dir = children[0]

    base = detect_base(dump_dir, args.base)
    segments = collect_segments(dump_dir, base)
    if not segments:
        raise SystemExit(f"no dump segments found under {dump_dir}")

    slots = list(SLOTS)
    for item in args.slot or []:
        if "=" in item:
            name, off_text = item.split("=", 1)
            slots.append((name, parse_int(off_text)))
        else:
            off = parse_int(item)
            slots.append((f"slot_{off:x}", off))

    print(f"dump_dir={dump_dir}")
    print(f"base=0x{base:x}")
    print(f"segments={len(segments)}")
    for name, off in slots:
        addr = base + off
        hits = [(seg, read_u64(seg, addr)) for seg in segments if seg.start <= addr and addr + 8 <= seg.end]
        hits = [(seg, value) for seg, value in hits if value is not None]
        if not hits:
            print(f"{name} off=0x{off:x} addr=0x{addr:x} value=<not-dumped>")
            continue
        for seg, value in hits:
            print(f"{name} off=0x{off:x} addr=0x{addr:x} value=0x{value:x} file={seg.path.name}")
            if value:
                ptr_hits = read_qwords_at(segments, value)
                if ptr_hits:
                    for ptr_seg, qwords in ptr_hits:
                        qtext = ",".join(f"0x{q:x}" for q in qwords)
                        print(f"  ptr=0x{value:x} qwords={qtext} ascii={ascii_hint(qwords)!r} file={ptr_seg.path.name}")
                else:
                    print(f"  ptr=0x{value:x} qwords=<not-dumped>")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
