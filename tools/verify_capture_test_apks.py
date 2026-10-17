#!/usr/bin/env python3
"""Check actual DEX class_def entries, not dependency reports or string references.

No third-party packages. This is a packaging regression guard, NOT an Android runtime test.
"""
import argparse
from pathlib import Path
import re
import struct
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]


def dex_classes(apk):
    definitions, references = set(), set()
    with ZipFile(apk) as archive:
        dex_files = [n for n in archive.namelist() if re.fullmatch(r"classes(?:\d+)?\.dex", n)]
        if not dex_files:
            raise ValueError("APK has no classes DEX")
        for name in dex_files:
            data = archive.read(name)
            if not data.startswith(b"dex\n"):
                raise ValueError("Unsupported DEX format")

            def u32(offset):
                return struct.unpack_from("<I", data, offset)[0]

            def string(index):
                offset = u32(u32(0x3C) + index * 4)
                # Skip the ULEB128 UTF-16 length. Class descriptors checked below are ASCII.
                for _ in range(5):
                    byte = data[offset]
                    offset += 1
                    if byte < 0x80:
                        break
                else:
                    raise ValueError("Invalid DEX string length")
                end = data.index(b"\0", offset)
                return data[offset:end].decode("utf-8", errors="replace")

            types = [string(u32(u32(0x44) + i * 4)) for i in range(u32(0x40))]
            references.update(types)
            definitions.update(types[u32(u32(0x64) + i * 32)] for i in range(u32(0x60)))
    return definitions, references


def require(condition, message):
    if not condition:
        raise ValueError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host-apk", type=Path, default=ROOT / "capture-test-host/build/outputs/apk/debug/capture-test-host-debug.apk")
    parser.add_argument("--app-apk", type=Path, default=ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
    parser.add_argument("--driver-apk", type=Path, default=ROOT / "capture-test-host/build/outputs/apk/androidTest/debug/capture-test-host-debug-androidTest.apk")
    parser.add_argument("--release-apk", type=Path, help="Also verify debug bridge classes are absent from a release APK")
    args = parser.parse_args()
    host, host_refs = dex_classes(args.host_apk)
    # These must be DEFINED by the standalone host. An unresolved type/string is insufficient.
    for descriptor in (
        "Lkotlin/jvm/internal/Intrinsics;", "Lkotlin/jvm/internal/DefaultConstructorMarker;",
        "Lkotlin/Unit;", "Lkotlin/KotlinVersion;", "Lkotlin/collections/CollectionsKt;",
        "Lkr/closeforyou/capturetest/SyntheticPopupActivity;",
        "Lkr/closeforyou/capturetest/SyntheticNoticeKt;",
        "Lkr/closeforyou/capturetest/SyntheticPatternView;",
        "Lkr/closeforyou/synthetictest/PatternMapping;",
        "Lkr/closeforyou/synthetictest/SyntheticDialogControl;",
    ):
        require(descriptor in host, "Standalone host missing DEX definition: " + descriptor)
    forbidden_refs = ("Lkr/closeforyou/accessibility/", "Lkr/closeforyou/app/", "Landroidx/test/", "Lorg/junit/")
    require(not any(t.startswith(forbidden_refs) for t in host_refs),
            "Standalone host references target-app/instrumentation classes")
    app, _ = dex_classes(args.app_apk)
    tests, _ = dex_classes(args.test_apk)
    require("Lkotlin/jvm/internal/Intrinsics;" in app, "Instrumentation target lacks Kotlin runtime")
    for name, classes in (("Production APK", app), ("Instrumentation APK", tests)):
        require(not any(c.startswith("Lkr/closeforyou/capturetest/") for c in classes),
                name + " unexpectedly contains the standalone host")
    require("Lkr/closeforyou/accessibility/capture/BundledKoreanOcrTest;" in tests, "OCR instrumentation tests missing")
    require("Lkr/closeforyou/accessibility/capture/SyntheticHostLaunchTest;" in tests, "Independent-process smoke test missing")
    require("Lkr/closeforyou/accessibility/capture/ScreenGeometryInstrumentedTest;" in tests, "Window-context geometry instrumentation missing")
    driver, driver_refs = dex_classes(args.driver_apk)
    require("Lkr/closeforyou/servicetest/ServiceCaptureOptInTest;" in driver, "External service driver missing")
    require("Lkr/closeforyou/servicetest/ServiceBufferGeometryOptInTest;" in driver, "Synthetic buffer geometry instrumentation missing")
    require("Lkr/closeforyou/servicetest/ServiceSyntheticPatternOptInTest;" in driver, "Synthetic landmark instrumentation missing")
    require("Lkr/closeforyou/servicetest/ServiceSyntheticRenderControlsOptInTest;" in driver, "Real Dialog render controls missing")
    require("Lkr/closeforyou/servicetest/RenderControlMatrix;" in driver, "Render control comparison missing")
    require("Lkr/closeforyou/servicetest/SyntheticPatternObservation;" in driver, "Shared real-API observer missing")
    require("Lkr/closeforyou/servicetest/PatternModeRunner;" in driver, "Two-mode observation runner missing")
    require("Lkr/closeforyou/servicetest/PatternModeComparison;" in driver, "Strict final two-mode comparison missing")
    require("Lkr/closeforyou/servicetest/SyntheticDialogGeometryTest;" in driver, "Synthetic Dialog geometry instrumentation missing")
    require(not any(t.startswith(("Lkr/closeforyou/accessibility/", "Lkr/closeforyou/app/", "Lkr/closeforyou/core/")) for t in driver_refs),
            "External driver must not read target-app process-local singletons")
    require("Lkr/closeforyou/accessibility/capture/ServiceCaptureOptInTest;" not in tests,
            "Service integration test still targets/restarts the real service app")
    require("Lkr/closeforyou/accessibility/testing/SyntheticCaptureTestProvider;" in app, "Debug probe missing")
    require("Lkr/closeforyou/accessibility/testing/SyntheticPatternProbe;" in app, "Debug pattern inspector missing")
    require("Lkr/closeforyou/synthetictest/PatternMapping;" in app, "Debug pattern matcher missing")
    if args.release_apk:
        release, _ = dex_classes(args.release_apk)
        require(not any(t.startswith("Lkr/closeforyou/accessibility/testing/") for t in release),
                "Debug probe leaked into release APK")
        require(not any(t.startswith("Lkr/closeforyou/synthetictest/") for t in release),
                "Synthetic pattern implementation leaked into release APK")
        print("PASS: debug probe classes absent from release APK")
    print("PASS: host defines Intrinsics + Kotlin runtime + synthetic Activity/image helper")
    print("PASS: host has no target-app/test-runner references; app and test APKs contain no host")
    print("PASS: instrumentation tests retained; target app defines its own Kotlin runtime")
    print("PASS: external service driver has no target-app Runtime/Preferences/model class dependencies")
    print("Packaging verified. Standalone Android launch/OCR still require device execution.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, IndexError, struct.error) as error:
        raise SystemExit("FAIL: " + str(error)) from error
