#!/usr/bin/env python3
"""Patch the official JetBrains Simplified Chinese language pack jar for Rebased."""

import argparse
import json
import os
import re
import sys
import zipfile
from pathlib import Path

SINCE_BUILD = "262.11879"
UNTIL_BUILD = "262.*"
EXPECTED_EXPORT_GROUP_KEYS = (
    "export.group.added",
    "export.group.modified",
    "export.group.moved",
    "export.group.deleted",
)
HIGHLIGHTED_BUNDLES = (
    "GitBundle",
    "VcsBundle",
    "VcsLogBundle",
    "IdeBundle",
    "ActionsBundle",
    "DiagnosticBundle",
    "GitCompareWithRefBundle",
)
LOCALE_SUFFIX_RE = re.compile(r"_[a-z]{2}(?:_[A-Z]{2})?\.properties$")
UNICODE_ESCAPE_RE = re.compile(r"\\u([0-9a-fA-F]{4})")


def decode_unicode_escapes(text: str) -> str:
    return UNICODE_ESCAPE_RE.sub(lambda m: chr(int(m.group(1), 16)), text)


def parse_properties(data: bytes) -> dict[str, str]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        text = data.decode("latin-1")

    props: dict[str, str] = {}
    logical_line = ""
    for raw_line in text.splitlines():
        stripped = raw_line.lstrip()
        if not logical_line and (not stripped or stripped[0] in ("#", "!")):
            continue
        trailing_backslashes = len(raw_line) - len(raw_line.rstrip("\\"))
        if trailing_backslashes % 2 == 1:
            logical_line += stripped[:-1]
            continue
        logical_line += stripped
        match = re.match(r"((?:\\.|[^=:\s])+)\s*[=:\s]\s*(.*)", logical_line)
        if match:
            key = decode_unicode_escapes(match.group(1))
            val = decode_unicode_escapes(match.group(2))
            props[key] = val
        logical_line = ""
    return props


def collect_overlay_files(overlay_dir: Path) -> dict[str, bytes]:
    overlay_files: dict[str, bytes] = {}
    for path in sorted(overlay_dir.rglob("*.properties")):
        if path.is_file():
            rel = path.relative_to(overlay_dir).as_posix()
            overlay_files[rel] = path.read_bytes()
    return overlay_files


def patch_plugin_xml(raw_xml: bytes) -> bytes:
    pattern = re.compile(
        rb'<idea-version\s+since-build="[^"]*"\s+until-build="[^"]*"\s*/>'
    )
    replacement = (
        f'<idea-version since-build="{SINCE_BUILD}" until-build="{UNTIL_BUILD}"/>'
    ).encode("utf-8")
    patched, count = pattern.subn(replacement, raw_xml, count=1)
    if count != 1:
        raise RuntimeError(
            "Failed to locate a single <idea-version since-build=... until-build=.../> tag in META-INF/plugin.xml"
        )
    print(f"Patched plugin.xml idea-version: {replacement.decode('utf-8')}")
    return patched


def build_patched_jar(
    src_jar: Path,
    overlay_files: dict[str, bytes],
    out_jar: Path,
) -> None:
    out_jar.parent.mkdir(parents=True, exist_ok=True)
    consumed_overlays: set[str] = set()

    with zipfile.ZipFile(src_jar, "r") as zin, zipfile.ZipFile(out_jar, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            if info.filename == "META-INF/plugin.xml":
                data = patch_plugin_xml(data)
            elif info.filename in overlay_files:
                consumed_overlays.add(info.filename)
                existing_props = parse_properties(data)
                overlay_bytes = overlay_files[info.filename]
                overlay_props = parse_properties(overlay_bytes)
                duplicate_keys = [k for k in overlay_props if k in existing_props]
                if duplicate_keys:
                    raise RuntimeError(
                        f"Overlay file {info.filename} contains keys that already exist in {src_jar}: {duplicate_keys}"
                    )
                if data and not data.endswith(b"\n"):
                    data += b"\n"
                data += overlay_bytes

            new_info = zipfile.ZipInfo(filename=info.filename, date_time=info.date_time)
            new_info.compress_type = info.compress_type
            new_info.external_attr = info.external_attr
            new_info.create_system = info.create_system
            zout.writestr(new_info, data)

        for rel_path, overlay_bytes in sorted(overlay_files.items()):
            if rel_path in consumed_overlays:
                continue
            new_info = zipfile.ZipInfo(filename=rel_path)
            new_info.compress_type = zipfile.ZIP_DEFLATED
            new_info.external_attr = 0o644 << 16
            zout.writestr(new_info, overlay_bytes)


def validate_and_report(out_jar: Path, dist_dir: Path) -> None:
    product_info_path = dist_dir / "product-info.json"
    if not product_info_path.is_file():
        raise RuntimeError(f"Missing product-info.json at {product_info_path}")
    product_info = json.loads(product_info_path.read_text(encoding="utf-8"))
    build_number = product_info.get("buildNumber", "")
    if not build_number.startswith(f"{SINCE_BUILD}."):
        raise RuntimeError(
            f"Unexpected buildNumber '{build_number}' in {product_info_path}; expected prefix '{SINCE_BUILD}.'"
        )

    with zipfile.ZipFile(out_jar, "r") as zzh:
        plugin_xml = zzh.read("META-INF/plugin.xml").decode("utf-8")
        for required_token in (
            "<id>com.intellij.zh</id>",
            'locale="zh-CN"',
            f'since-build="{SINCE_BUILD}"',
            f'until-build="{UNTIL_BUILD}"',
        ):
            if required_token not in plugin_xml:
                raise RuntimeError(
                    f"Patched META-INF/plugin.xml is missing required token: {required_token}"
                )

        zh_bundles: dict[str, dict[str, str]] = {}
        for name in zzh.namelist():
            if name.startswith("messages/") and name.endswith(".properties"):
                zh_bundles[name] = parse_properties(zzh.read(name))

    vcs_git_jar = dist_dir / "plugins" / "vcs-git" / "lib" / "vcs-git.jar"
    if not vcs_git_jar.is_file():
        raise RuntimeError(f"Missing vcs-git.jar at {vcs_git_jar}")

    compare_bundle_entry = "messages/GitCompareWithRefBundle.properties"
    with zipfile.ZipFile(vcs_git_jar, "r") as zvcs:
        if compare_bundle_entry not in zvcs.namelist():
            raise RuntimeError(
                f"{compare_bundle_entry} not found inside {vcs_git_jar}"
            )
        en_compare_props = parse_properties(zvcs.read(compare_bundle_entry))

    zh_compare_props = zh_bundles.get(compare_bundle_entry)
    if zh_compare_props is None:
        raise RuntimeError(f"{compare_bundle_entry} not found inside patched {out_jar}")

    missing_compare_keys = [k for k in en_compare_props if k not in zh_compare_props]
    if missing_compare_keys:
        raise RuntimeError(
            f"Patched {compare_bundle_entry} is missing keys: {missing_compare_keys}"
        )

    for key in EXPECTED_EXPORT_GROUP_KEYS:
        if key not in en_compare_props:
            raise RuntimeError(f"Missing {key} in English {compare_bundle_entry}")
        if zh_compare_props.get(key) != en_compare_props.get(key):
            raise RuntimeError(
                f"Mismatched {key}: zh={zh_compare_props.get(key)!r} vs en={en_compare_props.get(key)!r}"
            )

    dist_bundles: dict[str, dict[str, str]] = {}
    for jar_path in sorted(dist_dir.rglob("*.jar")):
        if not jar_path.is_file():
            continue
        if "extra-plugins" in jar_path.parts:
            continue
        try:
            with zipfile.ZipFile(jar_path, "r") as zf:
                for entry in zf.namelist():
                    if (
                        entry.startswith("messages/")
                        and entry.endswith(".properties")
                        and "/" not in entry[len("messages/") :]
                        and not LOCALE_SUFFIX_RE.search(entry)
                    ):
                        props = parse_properties(zf.read(entry))
                        if props:
                            dist_bundles.setdefault(entry, {}).update(props)
        except zipfile.BadZipFile:
            continue

    total_keys = 0
    translated_keys = 0
    bundle_stats: dict[str, tuple[int, int]] = {}
    for entry, props in sorted(dist_bundles.items()):
        zh_props = zh_bundles.get(entry, {})
        count = len(props)
        hit = sum(1 for k in props if k in zh_props)
        total_keys += count
        translated_keys += hit
        bundle_stats[entry] = (hit, count)

    overall_pct = (translated_keys * 100.0 / total_keys) if total_keys else 0.0
    print("=== Chinese Language Pack Coverage Report ===")
    print(
        f"Total bundles in distribution: {len(dist_bundles)} | "
        f"Total keys: {translated_keys}/{total_keys} ({overall_pct:.2f}%)"
    )
    for bundle_name in HIGHLIGHTED_BUNDLES:
        entry = f"messages/{bundle_name}.properties"
        hit, count = bundle_stats.get(entry, (0, 0))
        pct = (hit * 100.0 / count) if count else 0.0
        print(f"  - {bundle_name}: {hit}/{count} ({pct:.1f}%)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", required=True, type=Path, help="Original localization-zh.jar")
    parser.add_argument("--overlay", required=True, type=Path, help="Overlay l10n-zh directory")
    parser.add_argument("--dist", required=True, type=Path, help="Extracted distribution root directory")
    parser.add_argument("--out", required=True, type=Path, help="Output patched localization-zh.jar")
    args = parser.parse_args()

    if not args.jar.is_file():
        print(f"ERROR: --jar does not exist: {args.jar}", file=sys.stderr)
        return 1
    if not args.overlay.is_dir():
        print(f"ERROR: --overlay is not a directory: {args.overlay}", file=sys.stderr)
        return 1
    if not args.dist.is_dir():
        print(f"ERROR: --dist is not a directory: {args.dist}", file=sys.stderr)
        return 1

    overlay_files = collect_overlay_files(args.overlay)
    build_patched_jar(args.jar, overlay_files, args.out)
    validate_and_report(args.out, args.dist)
    return 0


if __name__ == "__main__":
    sys.exit(main())
