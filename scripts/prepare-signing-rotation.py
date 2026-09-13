"""Prepare public proof of signing-key rotation without exporting keys or changing Secrets."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location("release_signing", Path(__file__).with_name("sign-release.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


def bundle(value):
    if not value:
        raise ValueError("Signing bundle is missing")
    # Reuse strict schema validation, without forwarding a bundle to any native tool.
    signing.signing_environment({"RELEASE_SIGNING_BUNDLE": value})
    return json.loads(value)


def pending(value):
    if not value:
        return None
    if len(value) > 64 * 1024:
        raise ValueError("Pending signing request is unexpectedly large")
    try:
        envelope = json.loads(value)
    except (ValueError, TypeError):
        raise ValueError("Invalid pending signing request JSON") from None
    if not isinstance(envelope, dict) or set(envelope) != {"schema", "bundle", "request"} \
            or type(envelope["schema"]) is not int or envelope["schema"] != 1:
        raise ValueError("Unsupported pending signing request schema")
    bundle(json.dumps(envelope["bundle"]))
    request = envelope["request"]
    patterns = {"nonce": r"[0-9a-f]{32}", "source_sha": r"[0-9a-f]{40}", "new_certificate_sha256": r"[0-9a-f]{64}"}
    if not isinstance(request, dict) or not set(patterns) <= set(request) \
            or set(request) - (set(patterns) | {"old_certificate_sha256"}):
        raise ValueError("Pending request has missing or unsupported fields")
    if "old_certificate_sha256" in request:
        patterns["old_certificate_sha256"] = r"[0-9a-f]{64}"
    for name, pattern in patterns.items():
        if not isinstance(request[name], str) or not re.fullmatch(pattern, request[name]):
            raise ValueError("Invalid pending request binding")
    return envelope


def validate_request(operation, nonce, source_sha, ref, envelope):
    if ref != "refs/heads/release":
        raise ValueError("Signing maintenance only runs on the release branch")
    if operation not in {"prepare", "inspect"} or not re.fullmatch(r"[0-9a-f]{32}", nonce):
        raise ValueError("Invalid signing maintenance operation or nonce")
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("Invalid source commit SHA")
    if operation == "prepare":
        if envelope is None:
            raise ValueError("A pending signing bundle is required")
        request = envelope["request"]
        if request["nonce"] != nonce or request["source_sha"] != source_sha:
            raise ValueError("Pending signing request does not match this nonce and tested source commit")


def execute(command, *, passwords=None, signing_bundle=None):
    environment = {key: value for key, value in os.environ.items()
                   if not key.startswith(("RELEASE_", "FILEACCESS_ROTATION_"))
                   and key not in {"GH_TOKEN", "GITHUB_TOKEN"}}
    if passwords:
        environment.update(passwords)
    if signing_bundle is not None:
        # Only the Python release signer receives a bundle; it filters native child environments.
        environment["RELEASE_SIGNING_BUNDLE"] = json.dumps(signing_bundle, separators=(",", ":"))
    result = subprocess.run(command, env=environment, stdin=subprocess.DEVNULL, capture_output=True)
    if result.returncode:
        raise ValueError("Signing maintenance tool failed; output withheld to protect signing material")


def keytool_path(java):
    sibling = Path(java).with_name("keytool.exe" if os.name == "nt" else "keytool")
    if sibling.is_file():
        return str(sibling)
    located = shutil.which("keytool")
    if located is None:
        raise ValueError("Missing JDK keytool")
    return located


def unpack(directory, name, data, keytool):
    secrets = signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(data)})
    key = signing.material(directory, f"{name}.jks", "RELEASE_KEYSTORE_BASE64", "RELEASE_STORE_FILE",
                           required=True, environment=secrets)
    certificate = Path(directory) / f"{name}.der"
    execute([keytool, "-exportcert", "-keystore", str(key), "-alias", secrets["RELEASE_KEY_ALIAS"],
             "-storepass:env", "FILEACCESS_ROTATION_STORE_PASSWORD", "-file", str(certificate)],
            passwords={"FILEACCESS_ROTATION_STORE_PASSWORD": secrets["RELEASE_STORE_PASSWORD"]})
    return key, secrets, hashlib.sha256(certificate.read_bytes()).hexdigest()


def rotation_command(java, jar, old_key, old_alias, new_key, new_alias, output, old_lineage=None):
    command = [java, "-jar", str(jar), "rotate", "--out", str(output)]
    if old_lineage is not None:
        command += ["--in", str(old_lineage)]
    command += ["--old-signer", "--ks", str(old_key), "--ks-key-alias", old_alias,
                "--ks-pass", "env:FILEACCESS_ROTATION_OLD_STORE", "--key-pass", "env:FILEACCESS_ROTATION_OLD_KEY",
                "--set-installed-data", "true", "--set-rollback", "false",
                "--new-signer", "--ks", str(new_key), "--ks-key-alias", new_alias,
                "--ks-pass", "env:FILEACCESS_ROTATION_NEW_STORE", "--key-pass", "env:FILEACCESS_ROTATION_NEW_KEY",
                "--set-installed-data", "true", "--set-rollback", "false"]
    return command


def verify_rotation(directory, old_bundle, new_bundle, lineage, build_tools, android_jar):
    manifest = Path(directory) / "AndroidManifest.xml"
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
        package="space.zhuoling.fileaccess.signingfixture" android:versionCode="1" android:versionName="1.0.0">
        <uses-sdk android:minSdkVersion="35" android:targetSdkVersion="37"/>
        <application android:hasCode="false" android:debuggable="false" android:label="Signing verification fixture"/>
    </manifest>''', encoding="utf-8")
    unsigned = Path(directory) / "unsigned.apk"
    execute([signing.tool(build_tools, "aapt2"), "link", "-I", str(android_jar), "--manifest", str(manifest), "-o", str(unsigned)])
    signer = str(Path(__file__).with_name("sign-release.py"))
    old_apk, new_apk = Path(directory) / "old.apk", Path(directory) / "new.apk"
    command = [sys.executable, signer, "--input", str(unsigned), "--build-tools", str(build_tools)]
    execute(command + ["--output", str(old_apk)], signing_bundle=old_bundle)
    rotated = {**new_bundle, "lineage_base64": base64.b64encode(Path(lineage).read_bytes()).decode("ascii")}
    execute(command + ["--output", str(new_apk), "--previous-apk", str(old_apk)], signing_bundle=rotated)
    return new_apk


def verify_rotation_binding(directory, apk, old_fingerprint, new_fingerprint, build_tools):
    java, javac = signing.java_tools()
    jar = Path(build_tools) / "lib" / "apksigner.jar"
    helper = Path(__file__).with_name("VerifyRotationBinding.java")
    execute([javac, "-classpath", str(jar), "-d", str(directory), str(helper)])
    execute([java, "-classpath", os.pathsep.join((str(jar), str(directory))), "VerifyRotationBinding",
             str(apk), old_fingerprint, new_fingerprint])


def inspect_lineage(directory, active, envelope, active_fingerprint, next_fingerprint, build_tools, android_jar):
    if envelope is None or not envelope["bundle"].get("lineage_base64"):
        return False
    request = envelope["request"]
    old_fingerprint = request.get("old_certificate_sha256")
    if old_fingerprint is None or old_fingerprint == next_fingerprint:
        raise ValueError("Prepared lineage requires a distinct, verified previous certificate binding")
    if request["new_certificate_sha256"] != next_fingerprint:
        raise ValueError("Pending certificate does not match the prepared rotation request")
    # Recovery must work both before and after the atomic ACTIVE bundle replacement.
    if active_fingerprint not in {old_fingerprint, next_fingerprint}:
        raise ValueError("Active certificate is unrelated to the prepared rotation")
    secrets = signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(envelope["bundle"])})
    lineage = signing.material(directory, "next-lineage.bin", "RELEASE_SIGNING_LINEAGE_BASE64",
                               "RELEASE_SIGNING_LINEAGE_FILE", required=True, environment=secrets)
    apk = verify_rotation(directory, active, envelope["bundle"], lineage, build_tools, android_jar)
    verify_rotation_binding(directory, apk, old_fingerprint, next_fingerprint, build_tools)
    return True


def maintain(operation, nonce, output, build_tools, android_jar):
    source_sha = os.environ.get("GITHUB_SHA", "")
    envelope = pending(os.environ.get("RELEASE_NEXT_SIGNING_BUNDLE"))
    validate_request(operation, nonce, source_sha, os.environ.get("GITHUB_REF", ""), envelope)
    active = bundle(os.environ.get("RELEASE_SIGNING_BUNDLE"))
    output = Path(output)
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("Public artifact output directory must be empty")
    java, _ = signing.java_tools()
    keytool = keytool_path(java)
    metadata = {"operation": operation, "nonce": nonce, "source_sha": source_sha}
    with tempfile.TemporaryDirectory(prefix="fileaccess-rotation-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        directory = Path(temporary)
        old_key, old_secrets, old_fingerprint = unpack(directory, "active", active, keytool)
        new_key = new_secrets = new_fingerprint = None
        if envelope is not None:
            new_key, new_secrets, new_fingerprint = unpack(directory, "next", envelope["bundle"], keytool)
            if operation == "prepare" and new_fingerprint != envelope["request"]["new_certificate_sha256"]:
                raise ValueError("Pending key certificate does not match its request")
        if operation == "inspect":
            has_lineage = inspect_lineage(directory, active, envelope, old_fingerprint, new_fingerprint, build_tools, android_jar)
            metadata.update(active_certificate_sha256=old_fingerprint, next_certificate_sha256=new_fingerprint,
                            next_request=None if envelope is None else envelope["request"],
                            next_has_lineage=has_lineage)
            public_lineage = None
        else:
            if old_fingerprint == new_fingerprint:
                raise ValueError("Rotation requires a different new signing certificate")
            if envelope["request"].get("old_certificate_sha256", old_fingerprint) != old_fingerprint:
                raise ValueError("Pending request previous certificate does not match the active key")
            old_lineage = signing.material(directory, "old-lineage.bin", "RELEASE_SIGNING_LINEAGE_BASE64",
                                           "RELEASE_SIGNING_LINEAGE_FILE", environment=old_secrets)
            lineage = directory / "lineage.bin"
            execute(rotation_command(java, Path(build_tools) / "lib" / "apksigner.jar", old_key,
                                     old_secrets["RELEASE_KEY_ALIAS"], new_key, new_secrets["RELEASE_KEY_ALIAS"], lineage, old_lineage),
                    passwords={"FILEACCESS_ROTATION_OLD_STORE": old_secrets["RELEASE_STORE_PASSWORD"],
                               "FILEACCESS_ROTATION_OLD_KEY": old_secrets["RELEASE_KEY_PASSWORD"],
                               "FILEACCESS_ROTATION_NEW_STORE": new_secrets["RELEASE_STORE_PASSWORD"],
                               "FILEACCESS_ROTATION_NEW_KEY": new_secrets["RELEASE_KEY_PASSWORD"]})
            verify_rotation(directory, active, envelope["bundle"], lineage, build_tools, android_jar)
            public_lineage = lineage.read_bytes()
            metadata.update(old_certificate_sha256=old_fingerprint, new_certificate_sha256=new_fingerprint,
                            lineage_sha256=hashlib.sha256(public_lineage).hexdigest(), verified=True)
        # The artifact directory contains only explicitly selected public files.
        output.mkdir(parents=True, exist_ok=True)
        if public_lineage is not None:
            (output / "lineage.bin").write_bytes(public_lineage)
        (output / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print("Signing maintenance completed; only public metadata/proof retained")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--operation", choices=("prepare", "inspect"), required=True)
    parser.add_argument("--nonce", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--android-jar", type=Path, required=True)
    args = parser.parse_args()
    maintain(args.operation, args.nonce, args.output, args.build_tools, args.android_jar)


if __name__ == "__main__":
    main()
