#!/usr/bin/env python3
"""Build disposable signing lineages and optionally exercise Android package upgrades.

Requires JDK, Android Build Tools 36.0.0 and an SDK platform. This does not run
Gradle, start an emulator, use release secrets, or install the FileAccess app.
Only signed APKs, public certificates, and a JSON report survive the run.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import uuid


ROOT = Path(__file__).resolve().parent.parent
PASSWORD_ENV = "FILEACCESS_ROTATION_FIXTURE_PASSWORD"


def command(args, *, env=None, input_text=None, check=True, timeout=120):
    result = subprocess.run(
        [str(arg) for arg in args], input=input_text, capture_output=True,
        text=True, encoding="utf-8", errors="replace", env=env, timeout=timeout,
    )
    if check and result.returncode:
        # Passwords are supplied through an environment variable, never argv.
        raise RuntimeError(f"Command failed ({result.returncode}): {' '.join(map(str, args))}\n"
                           f"{result.stdout}{result.stderr}")
    return result


def find_sdk(argument):
    candidates = [argument, os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    properties = ROOT / "local.properties"
    if properties.is_file():
        match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(), re.MULTILINE)
        if match:
            candidates.append(match[1].strip().replace("\\:", ":").replace("\\\\", "\\"))
    for candidate in candidates:
        if candidate and (Path(candidate) / "build-tools").is_dir():
            return Path(candidate).resolve()
    raise RuntimeError("Set ANDROID_HOME or pass --sdk with an installed Android SDK")


def find_java(argument):
    executable = "java.exe" if os.name == "nt" else "java"
    candidates = [argument, os.environ.get("JAVA_HOME")]
    if os.environ.get("LOCALAPPDATA"):
        candidates.append(str(Path(os.environ["LOCALAPPDATA"]) / "Programs/Android Studio/jbr"))
    if os.environ.get("ProgramFiles"):
        candidates.append(str(Path(os.environ["ProgramFiles"]) / "Android/Android Studio/jbr"))
    located = shutil.which("java")
    if located:
        candidates.append(str(Path(located).resolve().parent.parent))
    for candidate in candidates:
        if candidate and (Path(candidate) / "bin" / executable).is_file():
            return Path(candidate).resolve()
    raise RuntimeError("Set JAVA_HOME or pass --java-home with a JDK (including keytool)")


class Fixture:
    def __init__(self, arguments, output, temporary):
        self.arguments = arguments
        self.output = output
        self.temporary = temporary
        self.sdk = find_sdk(arguments.sdk)
        self.build_tools = self.sdk / "build-tools" / arguments.build_tools
        executable = ".exe" if os.name == "nt" else ""
        java_home = find_java(arguments.java_home)
        self.java = java_home / "bin" / f"java{executable}"
        self.keytool = java_home / "bin" / f"keytool{executable}"
        self.aapt2 = self.build_tools / f"aapt2{executable}"
        self.zipalign = self.build_tools / f"zipalign{executable}"
        self.adb = self.sdk / "platform-tools" / f"adb{executable}"
        self.apksigner = [self.java, "-jar", self.build_tools / "lib/apksigner.jar"]
        platform_candidates = list((self.sdk / "platforms").glob("android-*/android.jar"))
        if not platform_candidates:
            raise RuntimeError("No installed Android SDK platform found")
        self.android_jar = max(platform_candidates, key=lambda path: tuple(
            int(number) for number in re.findall(r"\d+", path.parent.name)))
        for path in (self.keytool, self.aapt2, self.zipalign, self.apksigner[-1]):
            if not path.is_file():
                raise RuntimeError(f"Required SDK/JDK tool is missing: {path}")
        self.env = dict(os.environ)
        self.env[PASSWORD_ENV] = secrets.token_urlsafe(30)
        self.package = "space.zhuoling.fileaccess.rotationfixture.t" + uuid.uuid4().hex[:16]
        self.report = {"package": self.package, "serial": arguments.serial,
                       "fixtures": {}, "device_checks": [], "release_signer_checks": [],
                       "rotation_preparation_checks": [], "status": "running"}
        self.keys = {}
        self.lineages = {}
        self.apks = {}
        self.installed = False

    def run(self, *args, **kwargs):
        return command(args, env=self.env, **kwargs)

    def signer(self, key):
        return ["--ks", self.keys[key], "--ks-key-alias", "fixture",
                "--ks-pass", f"env:{PASSWORD_ENV}", "--key-pass", f"env:{PASSWORD_ENV}"]

    def bundle(self, key, lineage=None):
        value = {"schema": 1, "keystore_base64": base64.b64encode(self.keys[key].read_bytes()).decode("ascii"),
                 "store_password": self.env[PASSWORD_ENV], "key_alias": "fixture",
                 "key_password": self.env[PASSWORD_ENV]}
        if lineage:
            value["lineage_base64"] = base64.b64encode(self.lineages[lineage].read_bytes()).decode("ascii")
        return value

    def create_keys(self):
        certificates = self.output / "certificates"
        certificates.mkdir()
        for key in "ABCX":
            path = self.temporary / f"{key}.p12"
            self.run(self.keytool, "-genkeypair", "-noprompt", "-keystore", path,
                     "-storetype", "PKCS12", "-alias", "fixture", "-keyalg", "RSA",
                     "-keysize", "2048", "-validity", "7", "-dname", f"CN=Disposable rotation fixture {key}",
                     "-storepass:env", PASSWORD_ENV, "-keypass:env", PASSWORD_ENV)
            self.keys[key] = path
            self.run(self.keytool, "-exportcert", "-keystore", path, "-alias", "fixture",
                     "-storepass:env", PASSWORD_ENV, "-file", certificates / f"{key}.der")

    def rotate(self, name, old, new, parent=None, installed_data=True, rollback=False):
        output = self.temporary / f"{name}.lineage"
        args = [*self.apksigner, "rotate", "--out", output]
        if parent:
            args += ["--in", self.lineages[parent]]
        args += ["--old-signer", *self.signer(old), "--set-installed-data", str(installed_data).lower(),
                 "--set-rollback", str(rollback).lower(), "--new-signer", *self.signer(new)]
        self.run(*args)
        self.lineages[name] = output

    def unsigned_apk(self, name, code, debuggable=True):
        version = f"1.{code - 1}.0"
        manifest = self.temporary / f"{name}.xml"
        manifest.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            f'package="{self.package}" android:versionCode="{code}" android:versionName="{version}">\n'
            '  <uses-sdk android:minSdkVersion="35" android:targetSdkVersion="35"/>\n'
            '  <application android:label="Disposable signing rotation fixture" '
            f'android:hasCode="false" android:debuggable="{str(debuggable).lower()}" android:allowBackup="false"/>\n'
            '</manifest>\n', encoding="utf-8")
        unsigned = self.temporary / f"{name}-unsigned.apk"
        aligned = self.temporary / f"{name}-aligned.apk"
        self.run(self.aapt2, "link", "-I", self.android_jar, "--manifest", manifest, "-o", unsigned)
        self.run(self.zipalign, "-f", "4", unsigned, aligned)
        return aligned

    def apk(self, name, code, key, lineage=None):
        version = f"1.{code - 1}.0"
        aligned = self.unsigned_apk(name, code)
        output = self.output / f"{name}.apk"
        args = [*self.apksigner, "sign", "--min-sdk-version", "35", "--rotation-min-sdk-version", "28",
                "--v1-signing-enabled", "false", "--v2-signing-enabled", "false",
                "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", *self.signer(key)]
        if lineage:
            args += ["--lineage", self.lineages[lineage]]
        self.run(*args, "--in", aligned, "--out", output)
        verification = self.run(*self.apksigner, "verify", "--verbose", "--print-certs",
                                "--min-sdk-version", "35", output).stdout
        certificate = hashlib.sha256((self.output / "certificates" / f"{key}.der").read_bytes()).hexdigest()
        if not re.search(rf"Signer #1 certificate SHA-256 digest: {certificate}\b", verification):
            raise AssertionError(f"Unexpected current signing certificate for {name}")
        if "Verified using v3 scheme (APK Signature Scheme v3): true" not in verification:
            raise AssertionError(f"Missing verified v3 signature for {name}")
        self.apks[name] = output
        self.report["fixtures"][name] = {
            "apk": str(output), "versionCode": code, "versionName": version,
            "signer": key, "lineage": lineage, "certificateSha256": certificate,
            "apkSha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        }
        print(f"Verified fixture: {name} ({key}, lineage {lineage or 'none'})", flush=True)

    def build(self):
        self.create_keys()
        self.rotate("AB", "A", "B")
        self.rotate("ABC", "B", "C", "AB")
        self.rotate("BC", "B", "C")
        self.rotate("AX", "A", "X")
        for args in [("original-a", 1, "A"), ("base-b", 2, "B", "AB"),
                     ("forward-c", 3, "C", "ABC"), ("same-b", 4, "B", "AB"),
                     ("rollback-a", 5, "A"), ("fork-x", 6, "X", "AX"),
                     ("unrelated-x", 7, "X"), ("forward-truncated-c", 8, "C", "BC")]:
            self.apk(*args)
        arguments = {"rotationBaseApk": "base-b", "rotationSameApk": "same-b",
                     "rotationForwardApk": "forward-c", "rotationForwardTruncatedApk": "forward-truncated-c",
                     "rotationRollbackApk": "rollback-a", "rotationForkApk": "fork-x",
                     "rotationUnrelatedApk": "unrelated-x"}
        (self.output / "instrumentation-arguments.json").write_text(json.dumps(
            {key: str(self.apks[value]) for key, value in arguments.items()}, indent=2) + "\n", encoding="utf-8")

    def test_release_signer(self):
        """Exercise the production entry point using only disposable, non-debuggable input."""
        source = self.unsigned_apk("production-signer-input", 10, debuggable=False)
        self.rotate("AB-no-data", "A", "B", installed_data=False)
        self.rotate("AB-rollback", "A", "B", rollback=True)
        directory = self.output / "release-signer"
        directory.mkdir()
        accepted = {}

        def case(name, key, previous=None, lineage=None, expected=True, bundle=False):
            output = directory / f"{name}.apk"
            # Deliberately replace every release setting: no configured production
            # credential or token participates in this subprocess.
            env = {name: value for name, value in self.env.items()
                   if not name.startswith("RELEASE_") and name not in {"GH_TOKEN", "GITHUB_TOKEN"}}
            env["JAVA_HOME"] = str(self.java.parent.parent)
            if bundle:
                env["RELEASE_SIGNING_BUNDLE"] = json.dumps(self.bundle(key, lineage))
            else:
                env.update({"RELEASE_STORE_FILE": str(self.keys[key]), "RELEASE_KEY_ALIAS": "fixture",
                            "RELEASE_STORE_PASSWORD": self.env[PASSWORD_ENV], "RELEASE_KEY_PASSWORD": self.env[PASSWORD_ENV]})
                if lineage:
                    env["RELEASE_SIGNING_LINEAGE_FILE"] = str(self.lineages[lineage])
            args = [sys.executable, ROOT / "scripts/sign-release.py", "--input", source,
                    "--output", output, "--build-tools", self.build_tools]
            if previous:
                args += ["--previous-apk", accepted[previous]]
            result = command(args, env=env, check=False)
            if expected:
                if result.returncode or not output.is_file():
                    raise AssertionError(f"Production signer should accept {name}: {result.stdout}{result.stderr}")
                accepted[name] = output
            elif not result.returncode or output.exists():
                raise AssertionError(f"Production signer should reject {name} without creating an output")
            self.report["release_signer_checks"].append({"case": name, "expected": "accept" if expected else "reject"})
            print(f"Passed release signer check: {name}", flush=True)

        case("first-a", "A", bundle=True)
        case("same-a", "A", "first-a")
        case("forward-ab", "B", "first-a", "AB", bundle=True)
        case("forward-abc", "C", "forward-ab", "ABC")
        case("missing-lineage", "B", "first-a", expected=False)
        case("rollback-to-a", "A", "forward-ab", expected=False)
        case("unrelated-key", "X", "first-a", expected=False)
        case("removed-history", "B", "forward-ab", expected=False)
        case("non-leaf-key", "A", "first-a", "AB", expected=False)
        case("key-not-in-lineage", "X", "first-a", "AB", expected=False)
        case("missing-installed-data", "B", "first-a", "AB-no-data", expected=False)
        case("rollback-capability", "B", "first-a", "AB-rollback", expected=False)
        case("truncated-published-history", "C", "forward-ab", "BC", expected=False)

    def test_rotation_preparation(self):
        """Validate maintenance artifacts and request binding without contacting GitHub."""
        directory = self.output / "rotation-preparation"
        directory.mkdir()
        nonce, source_sha = uuid.uuid4().hex, "1" * 40

        def fingerprint(key):
            return hashlib.sha256((self.output / "certificates" / f"{key}.der").read_bytes()).hexdigest()

        def case(name, operation, old="A", new="B", old_lineage=None, expected=True,
                 nonce_arg=None, request_override=None, ref="refs/heads/release",
                 next_lineage=None, bound_old=None, corrupt_lineage=False):
            requested_nonce = nonce if nonce_arg is None else nonce_arg
            request = {"nonce": nonce, "source_sha": source_sha,
                       "new_certificate_sha256": fingerprint(new)} if new else None
            if bound_old:
                request["old_certificate_sha256"] = fingerprint(bound_old)
            if request_override:
                request.update(request_override)
            env = {name: value for name, value in self.env.items()
                   if not name.startswith("RELEASE_") and name not in {"GH_TOKEN", "GITHUB_TOKEN"}}
            env.update({"JAVA_HOME": str(self.java.parent.parent), "GITHUB_REF": ref, "GITHUB_SHA": source_sha,
                        "RELEASE_SIGNING_BUNDLE": json.dumps(self.bundle(old, old_lineage))})
            if new:
                next_bundle = self.bundle(new, next_lineage)
                if corrupt_lineage:
                    next_bundle["lineage_base64"] = base64.b64encode(b"invalid proof of rotation" * 8).decode("ascii")
                env["RELEASE_NEXT_SIGNING_BUNDLE"] = json.dumps({
                    "schema": 1, "bundle": next_bundle, "request": request})
            output = directory / name
            result = command([sys.executable, ROOT / "scripts/prepare-signing-rotation.py",
                              "--operation", operation, "--nonce", requested_nonce, "--output", output,
                              "--build-tools", self.build_tools, "--android-jar", self.android_jar], env=env, check=False)
            if expected:
                if result.returncode:
                    raise AssertionError(f"Rotation preparation should accept {name}: {result.stdout}{result.stderr}")
                metadata = json.loads((output / "metadata.json").read_text())
                assert metadata["operation"] == operation and metadata["nonce"] == requested_nonce
                assert metadata["source_sha"] == source_sha
                if operation == "prepare":
                    assert {item.name for item in output.iterdir()} == {"metadata.json", "lineage.bin"}
                    assert metadata["verified"] is True
                    assert metadata["old_certificate_sha256"] == fingerprint(old)
                    assert metadata["new_certificate_sha256"] == fingerprint(new)
                    assert metadata["lineage_sha256"] == hashlib.sha256((output / "lineage.bin").read_bytes()).hexdigest()
                else:
                    assert {item.name for item in output.iterdir()} == {"metadata.json"}
                    assert metadata["active_certificate_sha256"] == fingerprint(old)
                    assert metadata["next_certificate_sha256"] == (fingerprint(new) if new else None)
                    assert metadata["next_request"] == request
                    assert metadata["next_has_lineage"] is bool(next_lineage)
                for artifact in output.iterdir():
                    if self.env[PASSWORD_ENV].encode() in artifact.read_bytes():
                        raise AssertionError("A private test password escaped into a public artifact")
            elif not result.returncode or (output.exists() and any(output.iterdir())):
                raise AssertionError(f"Rotation preparation should reject {name} without public output")
            self.report["rotation_preparation_checks"].append({"case": name, "expected": "accept" if expected else "reject"})
            print(f"Passed rotation preparation check: {name}", flush=True)

        case("prepare-ab", "prepare")
        case("prepare-abc", "prepare", old="B", new="C", old_lineage="AB")
        case("wrong-nonce", "prepare", nonce_arg="2" * 32, expected=False)
        case("wrong-source-sha", "prepare", request_override={"source_sha": "2" * 40}, expected=False)
        case("wrong-fingerprint", "prepare", request_override={"new_certificate_sha256": "0" * 64}, expected=False)
        case("wrong-ref", "prepare", ref="refs/heads/staging", expected=False)
        case("inspect-pending-different-nonce", "inspect", nonce_arg="3" * 32)
        case("inspect-without-next", "inspect", new=None)
        case("inspect-prepared-before-promotion", "inspect", next_lineage="AB", bound_old="A")
        case("inspect-prepared-after-promotion", "inspect", old="B", old_lineage="AB",
             next_lineage="AB", bound_old="A")
        case("inspect-prepared-wrong-old-fingerprint", "inspect", old="B", old_lineage="AB",
             next_lineage="AB", bound_old="A", request_override={"old_certificate_sha256": "0" * 64}, expected=False)
        case("inspect-prepared-wrong-new-fingerprint", "inspect", next_lineage="AB", bound_old="A",
             request_override={"new_certificate_sha256": "0" * 64}, expected=False)
        case("inspect-prepared-corrupt-lineage", "inspect", next_lineage="AB", bound_old="A",
             corrupt_lineage=True, expected=False)
        case("inspect-prepared-wrong-leaf", "inspect", next_lineage="ABC", bound_old="A", expected=False)
        case("inspect-prepared-missing-old-binding", "inspect", next_lineage="AB", expected=False)
        case("inspect-ancestor-is-not-direct-predecessor", "inspect", old="C", new="C", old_lineage="ABC",
             next_lineage="ABC", bound_old="A", expected=False)

    def device(self, *args, **kwargs):
        return self.run(self.adb, "-s", self.arguments.serial, *args, **kwargs)

    def install(self, name, expected=True):
        result = self.device("install", "--no-streaming", "-r", self.apks[name], check=False)
        output = result.stdout + result.stderr
        if expected:
            if result.returncode or "Success" not in output:
                raise AssertionError(f"Expected installation of {name}: {output}")
            self.installed = True
        elif not result.returncode or "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in output:
            raise AssertionError(f"Expected signature rejection for {name}: {output}")

    def mark(self):
        self.marker = secrets.token_hex(24)
        self.device("shell", "run-as", self.package, "mkdir", "-p", "files")
        result = self.device("shell", "run-as", self.package, "tee", "files/rotation-marker", input_text=self.marker)
        if result.stdout.strip() != self.marker:
            raise AssertionError("Could not write disposable application data")

    def assert_marker(self):
        value = self.device("shell", "run-as", self.package, "cat", "files/rotation-marker").stdout
        if value.strip() != self.marker:
            raise AssertionError("Application data changed across a signing upgrade")

    def uninstall(self):
        if self.installed:
            self.device("uninstall", self.package)
            self.installed = False

    def device_check(self, name):
        self.report["device_checks"].append(name)
        print(f"Passed device check: {name}", flush=True)

    def test_device(self):
        serial = self.arguments.serial
        if not re.fullmatch(r"emulator-\d+", serial):
            raise RuntimeError("--serial must explicitly identify an emulator-NNNN; physical devices are refused")
        if self.device("get-state").stdout.strip() != "device":
            raise RuntimeError("Selected emulator is not available")
        if self.device("shell", "getprop", "ro.kernel.qemu").stdout.strip() != "1":
            raise RuntimeError("Selected device did not identify itself as an emulator")
        api = int(self.device("shell", "getprop", "ro.build.version.sdk").stdout.strip())
        if api < 35:
            raise RuntimeError("The signing fixtures require emulator API 35 or newer")
        self.report["deviceApi"] = api
        if "package:" in self.device("shell", "pm", "path", self.package, check=False).stdout:
            raise RuntimeError("Random fixture package already exists; refusing to replace it")
        try:
            self.install("original-a")
            self.mark()
            for name, label in [("base-b", "A → B preserves data"), ("forward-c", "B → C preserves data")]:
                self.install(name)
                self.assert_marker()
                self.device_check(label)
            self.uninstall()
            self.install("original-a")
            self.mark()
            self.install("forward-c")
            self.assert_marker()
            self.device_check("A → C skips B and preserves data")
            self.uninstall()
            self.install("base-b")
            self.mark()
            for name, label in [("rollback-a", "B rejects older signer A at higher versionCode"),
                                ("fork-x", "B rejects sibling A → X at higher versionCode"),
                                ("unrelated-x", "B rejects unrelated X at higher versionCode")]:
                self.install(name, expected=False)
                self.assert_marker()
                self.device_check(label)
            self.install("same-b")
            self.assert_marker()
            self.device_check("B → B preserves data")
            self.install("forward-truncated-c")
            self.assert_marker()
            self.device_check("A → B accepts a forward B → C lineage and preserves data")
        finally:
            self.uninstall()
        if "package:" in self.device("shell", "pm", "path", self.package, check=False).stdout:
            raise AssertionError("Disposable fixture package was not removed")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", help="Android SDK root; defaults to environment/local.properties")
    parser.add_argument("--java-home", help="JDK root; defaults to JAVA_HOME/Android Studio/PATH")
    parser.add_argument("--build-tools", default="36.0.0", help="Installed Build Tools version")
    parser.add_argument("--serial", help="Optional emulator-NNNN to test; no device is started automatically")
    parser.add_argument("--test-release-signer", action="store_true", help="Also exercise the production signer with disposable keys")
    parser.add_argument("--test-rotation-preparation", action="store_true", help="Also test maintenance request binding with disposable bundles")
    parser.add_argument("--output", type=Path, help="New output directory; existing paths are refused")
    args = parser.parse_args()
    if args.serial and not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("--serial must be emulator-NNNN; physical devices are never modified")
    output = (args.output or ROOT / "build/verification/signing-rotation" / uuid.uuid4().hex[:12]).resolve()
    output.mkdir(parents=True, exist_ok=False)
    fixture = None
    try:
        with tempfile.TemporaryDirectory(prefix="fileaccess-signing-fixture-") as temporary:
            fixture = Fixture(args, output, Path(temporary))
            fixture.build()
            if args.test_release_signer:
                fixture.test_release_signer()
            if args.test_rotation_preparation:
                fixture.test_rotation_preparation()
            if args.serial:
                fixture.test_device()
            fixture.report["status"] = "passed" if args.serial else "fixtures-verified-device-not-run"
    except Exception as error:
        if fixture:
            fixture.report["status"] = "failed"
            fixture.report["error"] = str(error)
        print(str(error), file=sys.stderr)
        return 1
    finally:
        if fixture:
            (output / "report.json").write_text(json.dumps(fixture.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"Signed fixtures and report: {output}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
