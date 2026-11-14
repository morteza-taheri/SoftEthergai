#!/usr/bin/env python3
"""BLOCK v1.0.81 — release ABI gate.

A strict ``arm64-v8a``-only APK cannot be installed on an ARMv8 phone that runs a
32-bit Android userspace: ``ro.product.cpu.abilist`` has no ``arm64-v8a``, so the
package installer answers INSTALL_FAILED_NO_MATCHING_ABIS / "App not installed".
Users reported exactly this for the v2.4.16 and v2.4.19 per-ABI assets.

The fix is to publish, under the ``arm64-v8a`` name, an APK carrying BOTH ARM
ABIs (built with ``-Parm64Fallback``). This script is the regression guard: run
it against the five release APKs before uploading them.

Usage:
    python scripts/verify_release_abis.py <apk> [<apk> ...] [--aapt2 PATH]

The aapt2 binary is auto-detected from ANDROID_HOME / ANDROID_SDK_ROOT, or can be
passed explicitly. Parsing and validation are pure functions so they are unit
testable without an Android SDK.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ALL_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

# The 64-bit ARM asset must keep its 32-bit fallback. Every other slice stays
# strict: a smaller download for devices that can really use that ABI.
STRICT_SLICES = {
    "armeabi-v7a": frozenset({"armeabi-v7a"}),
    "x86": frozenset({"x86"}),
    "x86_64": frozenset({"x86_64"}),
}
ARM64_REQUIRED = frozenset({"arm64-v8a", "armeabi-v7a"})
UNIVERSAL_REQUIRED = frozenset(ALL_ABIS)

_NATIVE_CODE_RE = re.compile(r"'([^']+)'")
_SUFFIX_RE = re.compile(r"-(arm64-v8a|armeabi-v7a|x86_64|x86|universal)\.apk$")


def parse_native_code(badging: str) -> frozenset[str]:
    """Extract the ABI set from ``aapt2 dump badging`` output."""
    abis: set[str] = set()
    for line in badging.splitlines():
        if line.startswith("native-code:"):
            abis.update(_NATIVE_CODE_RE.findall(line))
    return frozenset(abis)


def classify_asset(filename: str) -> str | None:
    """Return the slice name encoded in a release asset filename."""
    match = _SUFFIX_RE.search(filename)
    return match.group(1) if match else None


def validate_asset(filename: str, abis: frozenset[str]) -> list[str]:
    """Return human-readable problems; an empty list means the asset is OK."""
    problems: list[str] = []
    if not abis:
        return ["no native-code entries found (aapt2 output missing or APK has no JNI libs)"]

    kind = classify_asset(filename)
    if kind is None:
        return [f"unrecognised asset name: expected one of {sorted(set(STRICT_SLICES) | {'arm64-v8a', 'universal'})}"]

    if kind == "arm64-v8a":
        missing = ARM64_REQUIRED - abis
        if missing:
            problems.append(
                "arm64-v8a asset is missing the 32-bit ARM fallback "
                f"({', '.join(sorted(missing))}); it will fail to install on "
                "ARMv8 phones with 32-bit Android userspace"
            )
    elif kind == "universal":
        missing = UNIVERSAL_REQUIRED - abis
        if missing:
            problems.append(f"universal asset is missing {', '.join(sorted(missing))}")
    else:
        expected = STRICT_SLICES[kind]
        if abis != expected:
            problems.append(
                f"{kind} asset should contain exactly {', '.join(sorted(expected))}, "
                f"found {', '.join(sorted(abis))}"
            )

    unknown = abis - UNIVERSAL_REQUIRED
    if unknown:
        problems.append(f"unexpected ABI(s): {', '.join(sorted(unknown))}")
    return problems


def find_aapt2(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("aapt2")
    if found:
        return found
    roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
        os.path.expanduser("~/AppData/Local/Android/Sdk"),
    ]
    for root in roots:
        if not root:
            continue
        build_tools = Path(root) / "build-tools"
        if build_tools.is_dir():
            candidates = sorted(build_tools.glob("*/aapt2*"), reverse=True)
            if candidates:
                return str(candidates[0])
    raise SystemExit("aapt2 not found; pass --aapt2 or set ANDROID_HOME")


def read_abis(aapt2: str, apk: Path) -> frozenset[str]:
    result = subprocess.run(
        [aapt2, "dump", "badging", str(apk)],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"aapt2 failed on {apk.name}: {result.stderr.strip()}")
    return parse_native_code(result.stdout)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apks", nargs="+", type=Path, help="release APKs to check")
    parser.add_argument("--aapt2", default=None, help="path to aapt2")
    args = parser.parse_args(argv)

    aapt2 = find_aapt2(args.aapt2)
    kinds: set[str] = set()
    failed = False
    for apk in args.apks:
        if not apk.is_file():
            print(f"FAIL {apk.name}: file not found")
            failed = True
            continue
        abis = read_abis(aapt2, apk)
        kind = classify_asset(apk.name)
        if kind:
            kinds.add(kind)
        print(f"{apk.name}: {', '.join(sorted(abis)) or '(none)'}")
        for problem in validate_asset(apk.name, abis):
            print(f"  FAIL {problem}")
            failed = True

    expected_kinds = set(STRICT_SLICES) | {"arm64-v8a", "universal"}
    missing_kinds = expected_kinds - kinds
    if missing_kinds:
        print(f"FAIL missing release asset(s): {', '.join(sorted(missing_kinds))}")
        failed = True

    if failed:
        print("ABI gate FAILED — do not publish these APKs.")
        return 1
    print("ABI gate PASSED — all five assets have installable native libraries.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
