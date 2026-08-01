#!/usr/bin/env python3
"""Release artifact content gate (D1 decision, 2026-08-01).

hosted 变体为唯一商业发布路径。本脚本断言 hosted APK 不携带任何
Legacy/实验资产；任一 DENY 项命中即 exit 1（fail-closed）。

用法:
    python tools/audit/check-release-content.py <apk-path>
"""
import sys
import zipfile

# hosted 产物中禁止出现的条目（子串匹配，大小写敏感）
DENY_PATTERNS = [
    "loader.dex",                    # Legacy Stub 加载器
    "liblsplant.so",                 # LSPlant native hook（仅 Legacy Xposed 路线）
    "xposed_init",                   # Xposed 模块入口声明
    "assets/xposed_modules/",        # Xposed 模块包
]
# 说明：
# - assets/stubs/*.json 是 hosted 白名单加固壳兼容 profile（AppStubsConfig 加载），合法资产。
# - dex 字符串池不做整词扫描：LoaderFactory 对 xposed 的符号引用在 compileOnly 下
#   会留下类名字符串，属预期；类实体不进制品。类级审计由 R8 mapping + 依赖树承担。

# hosted 产物中必须存在的条目（前缀匹配）
REQUIRE_PREFIXES = [
    "classes",                       # 至少一个 dex
    "AndroidManifest.xml",
]


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: check-release-content.py <apk-path>", file=sys.stderr)
        return 2

    apk_path = sys.argv[1]
    with zipfile.ZipFile(apk_path) as apk:
        names = apk.namelist()

    violations = []
    for name in names:
        for pattern in DENY_PATTERNS:
            if pattern in name:
                violations.append(f"DENY  {name}  (pattern: {pattern})")

    for prefix in REQUIRE_PREFIXES:
        if not any(n.startswith(prefix) for n in names):
            violations.append(f"MISS  required entry with prefix: {prefix}")

    if violations:
        print(f"CONTENT GATE FAIL: {apk_path}")
        for v in violations:
            print("  " + v)
        return 1

    print(f"CONTENT GATE PASS: {apk_path} ({len(names)} entries)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
