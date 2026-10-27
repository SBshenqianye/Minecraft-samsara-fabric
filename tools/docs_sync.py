#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
samsara · 文档同步检查 / 机械同步

用法:
    python tools/docs_sync.py            # 检查
    python tools/docs_sync.py --apply    # 同步 README 当前版本等机械字段
    python tools/docs_sync.py --strict   # 把内部标识告警当成错误

规则见 docs/设计规范.md〈文档维护与权威顺序〉：
- 项目版本唯一来源：mod/gradle.properties 的 mod_version
- README 当前版本、CHANGELOG 最新条目必须与之一致
- README / CHANGELOG 面向“瞥一眼”的读者，不使用内部编号、类名、源码文件名

零依赖：仅 Python 3 标准库。
"""

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE_PROPERTIES = ROOT / "mod" / "gradle.properties"
DOCS = ROOT / "docs"

MOD_VERSION_RE = re.compile(r"^mod_version\s*=\s*(\d+\.\d+\.\d+)\s*$", re.MULTILINE)
README_VERSION_RE = re.compile(r"\*\*v(\d+\.\d+\.\d+)\*\*")
CHANGELOG_HEADING_RE = re.compile(
    r"^##\s+v(\d+\.\d+\.\d+)(?:（(\d{4}-\d{2}-\d{2})）)?", re.MULTILINE
)
LINK_RE = re.compile(r"\[[^\]]+\]\(([^)\s]+)\)")
DOC_STATUS_RE = re.compile(r"^>\s*状态[：:]\s*(.+?)（(\d{4}-\d{2}-\d{2})）", re.MULTILINE)

# README / CHANGELOG 里不该出现的内部标识
INTERNAL_PATTERNS = [
    (re.compile(r"\bF-\d{2,}\b"), "需求编号 F-xx"),
    (re.compile(r"(?<![\d-])\d-\d{2}(?![\d-])"), "实现状态编号 x-yy"),
    (re.compile(r"§\s*\d+"), "章节编号"),
    (re.compile(r"\bM\d+[a-z]?\b"), "里程碑编号 Mx"),
    (
        re.compile(
            r"\b[A-Z][A-Za-z0-9]*(Manager|Recorder|Service|Command|Config|Store|Engine|Mixin)\b"
        ),
        "内部类名",
    ),
    (re.compile(r"\b\w+\.java\b"), "源码文件名"),
]


def read(path):
    return path.read_text(encoding="utf-8")


def write(path, text):
    path.write_text(text, encoding="utf-8")


def project_version():
    m = MOD_VERSION_RE.search(read(GRADLE_PROPERTIES))
    if not m:
        raise SystemExit("无法从 mod/gradle.properties 读取 mod_version")
    return m.group(1)


def semver(value):
    return tuple(int(part) for part in value.split("."))


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


def apply_readme_version(version):
    text = read(README)
    m = README_VERSION_RE.search(text)
    if not m:
        return
    if m.group(1) == version:
        print("README 当前版本已是最新，无需修改")
        return
    old = m.group(1)
    write(README, README_VERSION_RE.sub(f"**v{version}**", text, count=1))
    print(f"已同步 README 当前版本: v{old} → v{version}")


def check_links(path, errors):
    text = read(path)
    link_count = 0
    for match in LINK_RE.finditer(text):
        target = match.group(1).strip()
        if target.startswith("<") and target.endswith(">"):
            target = target[1:-1]
        if target.startswith(("http://", "https://", "mailto:", "#")):
            continue
        target = unquote(target.split("#", 1)[0])
        if not target:
            continue
        link_count += 1
        if not (ROOT / target).exists():
            errors.append(
                f"{path.name} 第 {line_of(text, match.start())} 行的本地链接不存在: {target}"
            )
    print(f"{path.name} 本地链接: {link_count} 个")


def check_readme(version, errors, warnings):
    text = read(README)
    m = README_VERSION_RE.search(text)
    if not m:
        errors.append("README 未找到 `**vX.Y.Z**` 形式的当前版本行")
    elif m.group(1) != version:
        errors.append(
            f"README 当前版本是 v{m.group(1)}，项目版本是 v{version}；运行 --apply 同步"
        )
    else:
        print(f"README 当前版本: v{m.group(1)} ✓")
    check_links(README, errors)


def check_changelog(version, errors, warnings):
    text = read(CHANGELOG)
    headings = []
    for match in CHANGELOG_HEADING_RE.finditer(text):
        headings.append((match.group(1), match.group(2), line_of(text, match.start())))
    if not headings:
        errors.append("CHANGELOG 未找到 `## vX.Y.Z` 版本条目")
        return

    seen = set()
    for value, _, line in headings:
        if value in seen:
            errors.append(f"CHANGELOG 第 {line} 行版本重复: v{value}")
        seen.add(value)

    for (newer, _, _), (older, _, line) in zip(headings, headings[1:]):
        if semver(newer) <= semver(older):
            errors.append(
                f"CHANGELOG 第 {line} 行版本顺序错误: v{older} 应低于 v{newer}"
            )

    top_version, top_date, top_line = headings[0]
    if top_version != version:
        errors.append(
            f"CHANGELOG 最新条目是 v{top_version}（第 {top_line} 行），项目版本是 v{version}；"
            "请先补当前版本的变更条目"
        )
    else:
        print(f"CHANGELOG 最新版本: v{top_version} ✓")
    if not top_date:
        warnings.append("CHANGELOG 最新条目标题缺少日期（YYYY-MM-DD）")
    check_links(CHANGELOG, errors)


def check_doc_headers():
    parts = []
    for path in sorted(DOCS.glob("*.md")):
        match = DOC_STATUS_RE.search(read(path))
        if match:
            parts.append(f"{path.stem} {match.group(1).strip()}（{match.group(2)}）")
        else:
            parts.append(f"{path.stem} 无状态行")
    if parts:
        print("文档修订: " + " · ".join(parts))


def lint_internal_identifiers(errors, warnings, strict):
    for path in (README, CHANGELOG):
        text = read(path)
        hits = []
        for pattern, label in INTERNAL_PATTERNS:
            for match in pattern.finditer(text):
                hits.append((line_of(text, match.start()), label, match.group(0)))
        if not hits:
            print(f"{path.name} 内部标识: 0 处 ✓")
            continue
        for line, label, token in hits:
            message = f"{path.name} 第 {line} 行出现{label}: {token}"
            if strict:
                errors.append(message)
            else:
                warnings.append(message)
        print(f"{path.name} 内部标识: {len(hits)} 处（建议移到实现状态/设计文档）")


def main():
    parser = argparse.ArgumentParser(description="samsara 文档同步检查")
    parser.add_argument("--check", action="store_true", help="只检查（默认行为）")
    parser.add_argument("--apply", action="store_true", help="同步 README 当前版本")
    parser.add_argument("--strict", action="store_true", help="内部标识告警视为错误")
    args = parser.parse_args()

    errors = []
    warnings = []
    version = project_version()

    print("samsara · 文档同步检查")
    print(f"项目版本: v{version}（mod/gradle.properties）")

    if args.apply:
        apply_readme_version(version)

    check_readme(version, errors, warnings)
    check_changelog(version, errors, warnings)
    check_doc_headers()
    lint_internal_identifiers(errors, warnings, args.strict)

    if errors:
        print("\n✗ 未通过：")
        for message in errors:
            print(f"  - {message}")
        for message in warnings:
            print(f"  ! {message}")
        return 1

    print("\n✓ 通过")
    for message in warnings:
        print(f"  ! {message}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
