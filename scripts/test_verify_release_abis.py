#!/usr/bin/env python3
"""Unit tests for the BLOCK v1.0.81 release ABI gate (no Android SDK needed)."""

from __future__ import annotations

import unittest

from verify_release_abis import (
    classify_asset,
    parse_native_code,
    validate_asset,
)


class ParseNativeCodeTest(unittest.TestCase):
    def test_parses_single_abi(self) -> None:
        badging = (
            "package: name='vn.unlimit.vpngatepro' versionCode='154' versionName='2.4.20'\n"
            "sdkVersion:'24'\n"
            "native-code: 'arm64-v8a'\n"
        )
        self.assertEqual(parse_native_code(badging), frozenset({"arm64-v8a"}))

    def test_parses_multiple_abis_and_multiple_lines(self) -> None:
        badging = (
            "native-code: 'arm64-v8a' 'armeabi-v7a'\n"
            "native-code: 'x86' 'x86_64'\n"
        )
        self.assertEqual(
            parse_native_code(badging),
            frozenset({"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}),
        )

    def test_ignores_unrelated_lines(self) -> None:
        badging = "application: label='VpnM' icon='res/mipmap/ic_launcher.xml'\n"
        self.assertEqual(parse_native_code(badging), frozenset())


class ClassifyAssetTest(unittest.TestCase):
    def test_recognises_every_release_asset(self) -> None:
        for kind in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "universal"):
            name = f"SoftEthergai-2.4.20-{kind}.apk"
            self.assertEqual(classify_asset(name), kind)

    def test_x86_is_not_shadowed_by_x86_64(self) -> None:
        self.assertEqual(classify_asset("SoftEthergai-2.4.20-x86.apk"), "x86")

    def test_unknown_name(self) -> None:
        self.assertIsNone(classify_asset("app-pro-debug.apk"))


class ValidateAssetTest(unittest.TestCase):
    def test_dual_abi_arm64_asset_passes(self) -> None:
        self.assertEqual(
            validate_asset(
                "SoftEthergai-2.4.20-arm64-v8a.apk",
                frozenset({"arm64-v8a", "armeabi-v7a"}),
            ),
            [],
        )

    def test_strict_arm64_asset_is_rejected(self) -> None:
        """This is the exact defect reported by users for v2.4.16 / v2.4.19."""
        problems = validate_asset(
            "SoftEthergai-2.4.19-arm64-v8a.apk",
            frozenset({"arm64-v8a"}),
        )
        self.assertTrue(any("32-bit ARM fallback" in p for p in problems))

    def test_universal_asset_must_have_all_four(self) -> None:
        self.assertEqual(
            validate_asset(
                "SoftEthergai-2.4.20-universal.apk",
                frozenset({"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}),
            ),
            [],
        )
        self.assertTrue(
            validate_asset(
                "SoftEthergai-2.4.20-universal.apk",
                frozenset({"arm64-v8a", "armeabi-v7a"}),
            )
        )

    def test_strict_slices_must_not_gain_extra_abis(self) -> None:
        for kind in ("armeabi-v7a", "x86", "x86_64"):
            name = f"SoftEthergai-2.4.20-{kind}.apk"
            self.assertEqual(validate_asset(name, frozenset({kind})), [])
            self.assertTrue(
                validate_asset(name, frozenset({kind, "arm64-v8a"})),
                msg=f"{kind} should reject an extra ABI",
            )

    def test_empty_or_unknown_abi_sets_are_rejected(self) -> None:
        self.assertTrue(
            validate_asset("SoftEthergai-2.4.20-arm64-v8a.apk", frozenset())
        )
        self.assertTrue(
            validate_asset(
                "SoftEthergai-2.4.20-arm64-v8a.apk",
                frozenset({"arm64-v8a", "armeabi-v7a", "mips"}),
            )
        )

    def test_unrecognised_filename_is_rejected(self) -> None:
        self.assertTrue(validate_asset("app-pro-debug.apk", frozenset({"x86"})))


if __name__ == "__main__":
    unittest.main()
