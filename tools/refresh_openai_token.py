#!/usr/bin/env python3
"""
OpenAI Token 刷新工具 (CPA 格式)

批量刷新 ChatGPT 账号的 OAuth2 token。

普通用法:
  双击 refresh_token.bat，然后按提示选择 token 文件或目录。

命令行用法:
  python refresh_openai_token.py token_xxx.json              # 刷新单个文件（原地覆盖，自动备份）
  python refresh_openai_token.py D:/Downloads/cpa/           # 刷新目录下所有 token_*.json
  python refresh_openai_token.py D:/Downloads/cpa/ --dry-run # 预览，不实际写入
  python refresh_openai_token.py D:/Downloads/cpa/ --force   # 忽略1小时内防抖，强制刷新
"""

import argparse
import json
import shutil
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError


TOKEN_URL = "https://auth0.openai.com/oauth/token"
MAX_RETRIES = 2
RETRY_DELAY = 3
RECENTLY_REFRESHED_THRESHOLD = timedelta(hours=1)
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG_NAME = "openai_token_config.json"


# ─── 普通用户交互 ───────────────────────────────────────────────

def clean_input_path(value: str) -> str:
    """清理拖拽到命令行里的路径。"""
    return value.strip().strip('"').strip("'").strip()


def pick_path_interactively() -> str:
    """让普通用户选择 token 文件或目录。"""
    print()
    print("请选择要刷新的 token 文件或目录：")
    print("  1. 可以把 token_*.json 文件直接拖到这个窗口，然后按回车")
    print("  2. 也可以直接按回车，打开选择窗口")
    print()
    try:
        typed = clean_input_path(input("文件或目录路径: "))
    except EOFError:
        typed = ""
    if typed:
        return typed

    try:
        import tkinter as tk
        from tkinter import filedialog

        root = tk.Tk()
        root.withdraw()
        root.attributes("-topmost", True)

        selected = filedialog.askopenfilename(
            title="选择 token_*.json 文件；批量刷新请取消后选择目录",
            filetypes=[("Token JSON", "token_*.json"), ("JSON", "*.json"), ("所有文件", "*.*")],
        )
        if not selected:
            selected = filedialog.askdirectory(title="选择包含 token_*.json 的目录")
        root.destroy()

        if selected:
            return selected
    except Exception:
        pass

    print(f"{C.RED}未选择文件或目录。{C.RESET}", file=sys.stderr)
    sys.exit(1)


def ask_yes_no(prompt: str, default: bool = False) -> bool:
    suffix = "Y/n" if default else "y/N"
    answer = input(f"{prompt} ({suffix}): ").strip().lower()
    if not answer:
        return default
    return answer in ("y", "yes")


def load_json_file(path: Path):
    """读取 JSON，兼容 Windows UTF-8 BOM。"""
    with open(path, "r", encoding="utf-8-sig") as f:
        return json.load(f)


def load_default_client_id(config_path: Path | None) -> str:
    """从配置文件读取默认 client_id。"""
    candidates = []
    if config_path:
        candidates.append(config_path)
    candidates.append(SCRIPT_DIR / DEFAULT_CONFIG_NAME)

    for path in candidates:
        if not path.exists():
            continue
        try:
            data = load_json_file(path)
        except (json.JSONDecodeError, OSError):
            continue
        if isinstance(data, dict):
            value = str(data.get("client_id", "")).strip()
            if value and not value.startswith("YOUR_"):
                return value
    return ""


# ─── ANSI 颜色 ─────────────────────────────────────────────────

class C:
    RESET  = "\033[0m"
    BOLD   = "\033[1m"
    DIM    = "\033[2m"
    RED    = "\033[31m"
    GREEN  = "\033[32m"
    YELLOW = "\033[33m"
    CYAN   = "\033[36m"

    @staticmethod
    def disable():
        for attr in ("RESET", "BOLD", "DIM", "RED", "GREEN", "YELLOW", "CYAN"):
            setattr(C, attr, "")


# ─── 数据结构 ───────────────────────────────────────────────────

@dataclass
class RefreshResult:
    file: str
    email: str
    status: str  # "ok", "skipped", "error"
    message: str = ""
    old_exp: str = ""
    new_exp: str = ""
    index: int = 0


@dataclass
class Summary:
    total: int = 0
    success: int = 0
    skipped: int = 0
    failed: int = 0
    results: list = field(default_factory=list)


# ─── JWT 解析 ───────────────────────────────────────────────────

def decode_jwt_exp(token: str) -> int | None:
    """从 JWT 中提取 exp 字段（不验证签名）"""
    try:
        import base64
        parts = token.split(".")
        if len(parts) < 2:
            return None
        payload_b64 = parts[1]
        padding = 4 - len(payload_b64) % 4
        if padding != 4:
            payload_b64 += "=" * padding
        payload_json = base64.urlsafe_b64decode(payload_b64)
        payload = json.loads(payload_json)
        return payload.get("exp")
    except Exception:
        return None


def token_expires_at(data: dict) -> datetime | None:
    """获取 access_token 的过期时间"""
    at = data.get("access_token", "")
    exp = decode_jwt_exp(at)
    if exp:
        return datetime.fromtimestamp(exp, tz=timezone.utc)
    return None


def is_token_expired(data: dict, within_hours: float = 0) -> bool:
    """检查 token 是否已过期或即将过期"""
    exp = token_expires_at(data)
    if not exp:
        return True
    threshold = datetime.now(timezone.utc) + timedelta(hours=within_hours)
    return exp <= threshold


def token_data_expires_within(data, within_hours: float) -> bool:
    """对象或对象列表里只要有一个 token 即将过期，就需要刷新。"""
    if isinstance(data, dict):
        return is_token_expired(data, within_hours)
    if isinstance(data, list):
        return any(isinstance(item, dict) and is_token_expired(item, within_hours) for item in data)
    return True


def format_expiry(data: dict) -> str:
    """人类可读的过期时间"""
    exp = token_expires_at(data)
    if not exp:
        return "未知"
    now = datetime.now(timezone.utc)
    delta = exp - now
    if delta.total_seconds() < 0:
        return f"已过期 {abs(delta.days)}天{abs(delta.seconds // 3600)}小时"
    hours = int(delta.total_seconds() // 3600)
    if hours < 1:
        return f"剩余 {int(delta.total_seconds() // 60)}分钟"
    if hours < 24:
        return f"剩余 {hours}小时"
    return f"剩余 {delta.days}天{hours % 24}小时"


def was_recently_refreshed(data: dict, threshold: timedelta = RECENTLY_REFRESHED_THRESHOLD) -> bool:
    """检查是否在1小时内刚刷新过"""
    lr = data.get("last_refresh", "")
    if not lr:
        return False
    try:
        last = datetime.fromisoformat(lr)
        if last.tzinfo is None:
            last = last.replace(tzinfo=timezone.utc)
        return (datetime.now(timezone.utc) - last) < threshold
    except (ValueError, TypeError):
        return False


# ─── 核心刷新逻辑 ───────────────────────────────────────────────

def call_auth0(client_id: str, refresh_token: str) -> dict:
    """调用 Auth0 换取新 token（自动重试）"""
    last_err = None
    for attempt in range(MAX_RETRIES + 1):
        payload = json.dumps({
            "grant_type": "refresh_token",
            "client_id": client_id,
            "refresh_token": refresh_token,
            "scope": "openid profile email offline_access",
        }).encode("utf-8")

        req = Request(
            TOKEN_URL,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )

        try:
            with urlopen(req, timeout=30) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            last_err = f"HTTP {e.code}: {body}"
            if 400 <= e.code < 500:
                break
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY * (attempt + 1))
        except URLError as e:
            last_err = f"网络错误: {e.reason}"
            if attempt < MAX_RETRIES:
                time.sleep(RETRY_DELAY * (attempt + 1))

    raise RuntimeError(last_err or "未知错误")


def merge_tokens(original: dict, auth_response: dict) -> dict:
    """将 Auth0 返回的新 token 合并到原数据中"""
    now_iso = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00")

    updated = dict(original)
    updated["access_token"] = auth_response.get("access_token", original.get("access_token", ""))
    updated["id_token"] = auth_response.get("id_token", original.get("id_token", ""))

    new_rt = auth_response.get("refresh_token")
    if new_rt:
        updated["refresh_token"] = new_rt

    updated["last_refresh"] = now_iso
    updated["expired"] = ""

    return updated


def token_email(data: dict) -> str:
    return str(data.get("email") or data.get("account") or data.get("username") or "N/A")


def refresh_record(
    data: dict,
    force: bool,
    dry_run: bool,
    default_client_id: str = "",
) -> tuple[RefreshResult, dict]:
    """刷新一个账号对象，返回结果和更新后的对象。"""
    email = token_email(data)
    client_id = str(data.get("client_id") or default_client_id).strip()
    rt = data.get("refresh_token", "")

    if not client_id:
        return (
            RefreshResult(file="", email=email, status="error",
                          message="缺少 client_id：请在 token JSON 中添加，或创建 tools/openai_token_config.json"),
            data,
        )
    if not rt:
        return (
            RefreshResult(file="", email=email, status="error", message="缺少 refresh_token 字段"),
            data,
        )

    old_exp = format_expiry(data)

    if dry_run:
        return (
            RefreshResult(file="", email=email, status="skipped",
                          message="预览模式，不请求网络", old_exp=old_exp),
            data,
        )

    # 1小时内刚刷新过就跳过（除非 --force）
    if not force and was_recently_refreshed(data):
        return (
            RefreshResult(file="", email=email, status="skipped",
                          message="1小时内已刷新，加 --force 可强制刷新",
                          old_exp=old_exp),
            data,
        )

    try:
        auth_response = call_auth0(client_id, rt)
    except RuntimeError as e:
        return (
            RefreshResult(file="", email=email, status="error",
                          message=str(e), old_exp=old_exp),
            data,
        )

    updated = merge_tokens(data, auth_response)
    new_exp = format_expiry(updated)
    return (
        RefreshResult(file="", email=email, status="ok",
                      old_exp=old_exp, new_exp=new_exp),
        updated,
    )


def save_token_data(dest: Path, source: Path, data) -> None:
    """写回 token JSON，原地覆盖前自动备份。"""
    if dest == source:
        backup = source.with_suffix(".json.bak")
        if not backup.exists():
            shutil.copy2(source, backup)
    with open(dest, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)


def refresh_one(
    file_path: Path,
    output_dir: Path | None,
    force: bool,
    dry_run: bool,
    default_client_id: str = "",
) -> RefreshResult:
    """刷新单个 token 文件"""
    fname = file_path.name

    try:
        data = load_json_file(file_path)
    except (json.JSONDecodeError, OSError) as e:
        return RefreshResult(file=fname, email="?", status="error",
                             message=f"读取失败: {e}")

    if isinstance(data, dict):
        result, updated = refresh_record(data, force, dry_run, default_client_id)
        result.file = fname
        if dry_run or result.status != "ok":
            return result

        dest = output_dir / fname if output_dir else file_path
        try:
            save_token_data(dest, file_path, updated)
        except OSError as e:
            return RefreshResult(file=fname, email=result.email, status="error",
                                 message=f"写入失败: {e}",
                                 old_exp=result.old_exp, new_exp=result.new_exp)

        result.message = str(dest)
        return result

    if isinstance(data, list):
        if not data:
            return RefreshResult(file=fname, email="0 accounts", status="error",
                                 message="JSON 列表为空")

        updated_items = list(data)
        child_results: list[RefreshResult] = []
        for i, item in enumerate(data):
            if not isinstance(item, dict):
                return RefreshResult(file=fname, email=f"{len(data)} accounts",
                                     status="error",
                                     message=f"第 {i + 1} 项不是账号对象")
            child, updated = refresh_record(item, force, dry_run, default_client_id)
            child_results.append(child)
            updated_items[i] = updated

        ok = sum(1 for r in child_results if r.status == "ok")
        skipped = sum(1 for r in child_results if r.status == "skipped")
        failed = sum(1 for r in child_results if r.status == "error")
        emails = ", ".join(r.email for r in child_results[:3])
        if len(child_results) > 3:
            emails += f" 等 {len(child_results)} 个账号"

        first_old = child_results[0].old_exp if child_results else ""
        first_new = next((r.new_exp for r in child_results if r.new_exp), "")

        if dry_run:
            return RefreshResult(file=fname, email=emails, status="skipped",
                                 message=f"预览模式：{len(child_results)} 个账号，不请求网络",
                                 old_exp=first_old, new_exp=first_new)

        if ok > 0:
            dest = output_dir / fname if output_dir else file_path
            try:
                save_token_data(dest, file_path, updated_items)
            except OSError as e:
                return RefreshResult(file=fname, email=emails, status="error",
                                     message=f"写入失败: {e}",
                                     old_exp=first_old, new_exp=first_new)

        if failed:
            details = "; ".join(f"{r.email}: {r.message}" for r in child_results if r.status == "error")
            saved = "，成功项已保存" if ok > 0 else ""
            return RefreshResult(file=fname, email=emails, status="error",
                                 message=f"{ok} 成功，{skipped} 跳过，{failed} 失败{saved}。{details}",
                                 old_exp=first_old, new_exp=first_new)

        dest = output_dir / fname if output_dir else file_path
        return RefreshResult(file=fname, email=emails, status="ok",
                             message=f"{dest}（{ok} 成功，{skipped} 跳过）",
                             old_exp=first_old, new_exp=first_new)

    return RefreshResult(file=fname, email="?", status="error",
                         message="JSON 顶层必须是对象或账号对象列表")


# ─── 文件发现 ───────────────────────────────────────────────────

def discover_files(path: str, expires_within: float | None) -> list[Path]:
    """查找 token 文件"""
    p = Path(path)
    if p.is_file():
        if not p.exists():
            print(f"{C.RED}错误:{C.RESET} 文件不存在: {path}", file=sys.stderr)
            sys.exit(1)
        return [p]
    if p.is_dir():
        # 跳过已刷新的文件和备份
        files = sorted(
            f for f in p.glob("*.json")
            if not f.name.endswith("_refreshed.json")
            and not f.name.endswith(".bak")
            and f.name not in (DEFAULT_CONFIG_NAME, "openai_token_config.example.json")
        )
        if expires_within is not None:
            filtered = []
            for f in files:
                try:
                    data = load_json_file(f)
                    if token_data_expires_within(data, within_hours=expires_within):
                        filtered.append(f)
                except Exception:
                    filtered.append(f)
            return filtered
        return files
    print(f"{C.RED}错误:{C.RESET} 路径不存在: {path}", file=sys.stderr)
    sys.exit(1)


# ─── 输出格式化 ─────────────────────────────────────────────────

def print_header():
    print(f"\n{C.BOLD}{C.CYAN}OpenAI Token 刷新工具{C.RESET}")
    print(f"{C.DIM}{'-' * 60}{C.RESET}\n")


def print_result(r: RefreshResult):
    if r.status == "ok":
        icon = f"{C.GREEN}[成功]{C.RESET}"
    elif r.status == "skipped":
        icon = f"{C.YELLOW}[跳过]{C.RESET}"
    else:
        icon = f"{C.RED}[失败]{C.RESET}"

    print(f"  {icon} [{r.index}] {C.BOLD}{r.email}{C.RESET}")
    if r.old_exp:
        print(f"      {C.DIM}刷新前:{C.RESET} {r.old_exp}")
    if r.new_exp and r.new_exp != r.old_exp:
        print(f"      {C.DIM}刷新后:{C.RESET} {r.new_exp}")
    if r.message and r.status == "error":
        print(f"      {C.RED}{r.message}{C.RESET}")
    elif r.message and r.status == "ok":
        print(f"      {C.DIM}已保存到: {r.message}{C.RESET}")


def print_summary(s: Summary, elapsed: float):
    print(f"\n{C.DIM}{'-' * 60}{C.RESET}")
    print(f"  {C.GREEN}{s.success} 个成功{C.RESET}  "
          f"{C.YELLOW}{s.skipped} 个跳过{C.RESET}  "
          f"{C.RED}{s.failed} 个失败{C.RESET}  "
          f"{C.DIM}(耗时 {elapsed:.1f}秒){C.RESET}\n")

    if s.failed > 0:
        print(f"  {C.RED}失败详情:{C.RESET}")
        for r in sorted(s.results, key=lambda x: x.index):
            if r.status == "error":
                print(f"    - {r.email}: {r.message}")
        print()


def run_refresh(files: list[Path], output_dir: Path | None, args, default_client_id: str = "") -> Summary:
    """执行刷新并打印结果。"""
    summary = Summary()
    start_time = time.time()

    tasks = [(f, i + 1) for i, f in enumerate(files)]

    def run_task(item):
        f, idx = item
        r = refresh_one(f, output_dir, args.force, args.dry_run, default_client_id)
        r.index = idx
        return r

    if len(tasks) == 1 or args.workers <= 1:
        for item in tasks:
            r = run_task(item)
            summary.results.append(r)
            if r.status == "ok":
                summary.success += 1
            elif r.status == "skipped":
                summary.skipped += 1
            else:
                summary.failed += 1
            if not args.json:
                print_result(r)
    else:
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {pool.submit(run_task, item): item[1] for item in tasks}
            for future in as_completed(futures):
                r = future.result()
                summary.results.append(r)
                if r.status == "ok":
                    summary.success += 1
                elif r.status == "skipped":
                    summary.skipped += 1
                else:
                    summary.failed += 1
                if not args.json:
                    print_result(r)

    summary.results.sort(key=lambda x: x.index)
    summary.total = len(summary.results)
    elapsed = time.time() - start_time

    if args.json:
        output = {
            "total": summary.total,
            "success": summary.success,
            "skipped": summary.skipped,
            "failed": summary.failed,
            "elapsed_seconds": round(elapsed, 2),
            "results": [
                {
                    "file": r.file,
                    "email": r.email,
                    "status": r.status,
                    "message": r.message,
                    "old_exp": r.old_exp,
                    "new_exp": r.new_exp,
                }
                for r in sorted(summary.results, key=lambda x: x.index)
            ],
        }
        print(json.dumps(output, indent=2, ensure_ascii=False))
    else:
        print_summary(summary, elapsed)

    return summary


# ─── 命令行参数 ─────────────────────────────────────────────────

def parse_expires_within(val: str | None) -> float | None:
    """解析 '24h', '7d', '30m' 为小时数"""
    if val is None:
        return None
    val = val.strip().lower()
    if val.endswith("h"):
        return float(val[:-1])
    if val.endswith("d"):
        return float(val[:-1]) * 24
    if val.endswith("m"):
        return float(val[:-1]) / 60
    return float(val)


def main():
    parser = argparse.ArgumentParser(
        description="OpenAI Token 刷新工具 (CPA 格式)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
用法示例:
  %(prog)s token_xxx.json                       # 刷新单个文件（原地覆盖，自动备份 .bak）
  %(prog)s D:/Downloads/cpa/                    # 刷新目录下所有 token_*.json
  %(prog)s D:/Downloads/cpa/ --expires-within 24h  # 只刷新快过期的
  %(prog)s D:/Downloads/cpa/ --dry-run          # 预览，不实际修改
  %(prog)s D:/Downloads/cpa/ --output-dir ./out/    # 输出到另一个目录
  %(prog)s D:/Downloads/cpa/ --force            # 忽略1小时防抖，强制刷新
  %(prog)s D:/Downloads/cpa/ --client-id app_xxx # 文件缺少 client_id 时使用
        """,
    )
    parser.add_argument("path", nargs="?", help="token 文件路径，或包含 token_*.json 的目录")
    parser.add_argument("--output-dir", "-o", help="输出目录（默认原地覆盖）")
    parser.add_argument("--dry-run", action="store_true", help="预览模式，不实际写入")
    parser.add_argument("--expires-within", metavar="TIME",
                        help="只刷新即将过期的 token（如 24h, 7d, 30m）")
    parser.add_argument("--force", action="store_true",
                        help="忽略1小时防抖，强制刷新")
    parser.add_argument("--workers", type=int, default=4, help="并发数（默认 4）")
    parser.add_argument("--no-color", action="store_true", help="禁用彩色输出")
    parser.add_argument("--json", action="store_true", help="以 JSON 格式输出结果")
    parser.add_argument("--wizard", action="store_true",
                        help="普通用户模式：选择文件、预览、确认后刷新")
    parser.add_argument("--client-id", help="文件缺少 client_id 时使用的默认 client_id")
    parser.add_argument("--config", help="配置文件路径，默认读取 tools/openai_token_config.json")
    args = parser.parse_args()

    if args.no_color or not sys.stdout.isatty():
        C.disable()

    if not args.path:
        args.wizard = True
    if args.wizard and args.json:
        print(f"{C.RED}错误:{C.RESET} --wizard 不能和 --json 一起使用", file=sys.stderr)
        sys.exit(1)
    if args.wizard and not args.path:
        args.path = pick_path_interactively()

    expires_within = parse_expires_within(args.expires_within)
    files = discover_files(args.path, expires_within)
    config_path = Path(args.config) if args.config else None
    default_client_id = str(args.client_id or "").strip() or load_default_client_id(config_path)

    if not files:
        print(f"{C.YELLOW}未找到 token 文件。{C.RESET}")
        sys.exit(0)

    output_dir = None
    if args.output_dir:
        output_dir = Path(args.output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

    if args.wizard:
        print_header()
        print(f"  已选择: {Path(args.path)}")
        print(f"  找到 {len(files)} 个 token 文件。")
        if default_client_id:
            print("  已加载默认 client_id。")
        else:
            print("  未加载默认 client_id；缺少 client_id 的文件会提示错误。")
        print(f"  下面先预览，不会修改文件。\n")

        original_dry_run = args.dry_run
        args.dry_run = True
        preview = run_refresh(files, output_dir, args, default_client_id)
        args.dry_run = original_dry_run

        if preview.failed > 0:
            print(f"{C.YELLOW}有文件预览失败，请先处理失败项。{C.RESET}")
        if not ask_yes_no("确认开始刷新并覆盖原文件？刷新前会自动生成 .bak 备份"):
            print("已取消。")
            sys.exit(0)
        print()

    if not args.json:
        print_header()
        mode = []
        if args.dry_run:
            mode.append(f"{C.YELLOW}预览模式{C.RESET}")
        if output_dir:
            mode.append(f"输出到: {output_dir}")
        else:
            mode.append(f"{C.DIM}原地覆盖（自动备份 .bak）{C.RESET}")
        if expires_within is not None:
            mode.append(f"仅 {args.expires_within} 内过期的")
        if args.force:
            mode.append(f"{C.RED}强制刷新{C.RESET}")
        if mode:
            print(f"  模式: {' | '.join(mode)}")
        print(f"  文件数: {len(files)}\n")

    summary = run_refresh(files, output_dir, args, default_client_id)

    sys.exit(1 if summary.failed > 0 else 0)


if __name__ == "__main__":
    main()
