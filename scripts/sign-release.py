"""Sign an unsigned release outside Gradle; secrets are read only from the environment."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def tool(directory, name):
    path = Path(directory) / (name + (".exe" if os.name == "nt" else ""))
    if not path.is_file():
        raise ValueError(f"Missing SDK/JDK tool: {name}")
    return str(path)


def java_tools():
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        return tool(Path(java_home) / "bin", "java"), tool(Path(java_home) / "bin", "javac")
    java, javac = shutil.which("java"), shutil.which("javac")
    if not java or not javac:
        raise ValueError("Set JAVA_HOME to a JDK with java and javac")
    return java, javac


def signing_environment(environment=None):
    environment = os.environ if environment is None else environment
    legacy = ("RELEASE_KEYSTORE_BASE64", "RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS",
              "RELEASE_KEY_PASSWORD", "RELEASE_SIGNING_LINEAGE_BASE64", "RELEASE_SIGNING_LINEAGE_FILE")
    bundle = environment.get("RELEASE_SIGNING_BUNDLE")
    if not bundle:
        return {name: environment[name] for name in legacy if environment.get(name)}
    if any(environment.get(name) for name in legacy):
        raise ValueError("RELEASE_SIGNING_BUNDLE cannot be mixed with legacy or file signing inputs")
    if len(bundle) > 64 * 1024:
        raise ValueError("Signing bundle is unexpectedly large")
    try:
        data = json.loads(bundle)
    except (ValueError, TypeError):
        raise ValueError("Invalid signing bundle JSON") from None
    required = {"schema", "keystore_base64", "store_password", "key_alias", "key_password"}
    if not isinstance(data, dict) or not required <= data.keys() or data.keys() - (required | {"lineage_base64"}):
        raise ValueError("Signing bundle has missing or unsupported fields")
    if type(data["schema"]) is not int or data["schema"] != 1:
        raise ValueError("Unsupported signing bundle schema")
    for name in required - {"schema"}:
        if not isinstance(data[name], str) or not data[name]:
            raise ValueError("Signing bundle fields must be nonempty strings")
    if not isinstance(data.get("lineage_base64", ""), str):
        raise ValueError("Signing bundle lineage must be a string")
    return {"RELEASE_KEYSTORE_BASE64": data["keystore_base64"], "RELEASE_STORE_PASSWORD": data["store_password"],
            "RELEASE_KEY_ALIAS": data["key_alias"], "RELEASE_KEY_PASSWORD": data["key_password"],
            "RELEASE_SIGNING_LINEAGE_BASE64": data.get("lineage_base64", "")}


def run(command, *, signing=False, secrets=None):
    # Only apksigner receives passwords. Never inherit key blobs or a GitHub token.
    env = {key: value for key, value in os.environ.items()
           if not key.startswith("RELEASE_") and key not in {"GH_TOKEN", "GITHUB_TOKEN"}}
    if signing:
        secrets = os.environ if secrets is None else secrets
        for key in ("RELEASE_STORE_PASSWORD", "RELEASE_KEY_PASSWORD"):
            value = secrets.get(key)
            if not value:
                raise ValueError(f"Missing {key}")
            env[key] = value
    result = subprocess.run(command, env=env, stdin=subprocess.DEVNULL, capture_output=True, text=True)
    if result.returncode:
        # A provider/tool may echo its configuration on failure; never print its output or argv.
        raise ValueError("Signing or verification tool failed; check key, lineage and SDK configuration")
    return result.stdout


def material(directory, stem, base64_name, file_name, *, required=False, environment=None):
    environment = os.environ if environment is None else environment
    encoded, source = environment.get(base64_name), environment.get(file_name)
    if encoded and source:
        raise ValueError(f"Set only one of {base64_name} and {file_name}")
    if not encoded and not source:
        if required:
            raise ValueError(f"Missing {base64_name} or {file_name}")
        return None
    if encoded:
        if len(encoded) > 2 * 1024 * 1024:
            raise ValueError("Signing material is unexpectedly large")
        content = base64.b64decode(encoded, validate=True)
    else:
        source_path = Path(source)
        if source_path.stat().st_size > 1024 * 1024:
            raise ValueError("Signing material is unexpectedly large")
        content = source_path.read_bytes()
    if not content or len(content) > 1024 * 1024:
        raise ValueError("Signing material is empty or unexpectedly large")
    path = Path(directory) / stem
    # TemporaryDirectory is private; restrict each material file before writing as well.
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(content)
    return path


def sign_command(java, jar, keystore, alias, source, output, lineage=None):
    command = [java, "-jar", str(jar), "sign", "--ks", str(keystore),
               "--ks-key-alias", alias, "--ks-pass", "env:RELEASE_STORE_PASSWORD",
               "--key-pass", "env:RELEASE_KEY_PASSWORD", "--min-sdk-version", "35",
               # All supported devices understand v3. Avoid v3.1 fallback needing an old private key.
               "--rotation-min-sdk-version", "28", "--v1-signing-enabled", "false",
               "--v2-signing-enabled", "false", "--v3-signing-enabled", "true",
               "--v4-signing-enabled", "false", "--debuggable-apk-permitted", "false"]
    if lineage is not None:
        command += ["--lineage", str(lineage)]
    return command + ["--in", str(source), "--out", str(output)]


def sign_release(source, output, build_tools, previous=None, checksum=None):
    source, output, build_tools = Path(source), Path(output), Path(build_tools)
    if not source.is_file() or source.resolve() == output.resolve():
        raise ValueError("Provide an unsigned APK and a distinct output path")
    if previous is not None and not Path(previous).is_file():
        raise ValueError("Previously published APK is required for signing continuity")
    secrets = signing_environment()
    alias = secrets.get("RELEASE_KEY_ALIAS")
    if not alias:
        raise ValueError("Missing RELEASE_KEY_ALIAS")
    java, javac = java_tools()
    jar = build_tools / "lib" / "apksigner.jar"
    if not jar.is_file():
        raise ValueError("Missing apksigner.jar")
    zipalign = tool(build_tools, "zipalign")
    with tempfile.TemporaryDirectory(prefix="fileaccess-signing-", dir=os.environ.get("RUNNER_TEMP")) as temporary:
        directory = Path(temporary)
        key = material(directory, "current.jks", "RELEASE_KEYSTORE_BASE64", "RELEASE_STORE_FILE", required=True, environment=secrets)
        lineage = material(directory, "lineage.bin", "RELEASE_SIGNING_LINEAGE_BASE64", "RELEASE_SIGNING_LINEAGE_FILE", environment=secrets)
        aligned, signed = directory / "aligned.apk", directory / "signed.apk"
        run([zipalign, "-P", "16", "-f", "4", str(source), str(aligned)])
        run(sign_command(java, jar, key, alias, aligned, signed, lineage), signing=True, secrets=secrets)
        run([java, "-jar", str(jar), "verify", "--verbose", "--print-certs", str(signed)])
        run([zipalign, "-c", "-P", "16", "4", str(signed)])
        helper = Path(__file__).with_name("VerifySigningLineage.java")
        run([javac, "-classpath", str(jar), "-d", str(directory), str(helper)])
        command = [java, "-classpath", os.pathsep.join((str(jar), str(directory))),
                   "VerifySigningLineage", str(signed), str(previous) if previous is not None else "-"]
        if lineage is not None:
            command.append(str(lineage))
        run(command)
        # Only verified public APKs leave the private temporary directory.
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(signed, output)
    if checksum is not None:
        with output.open("rb") as package:
            digest = hashlib.file_digest(package, "sha256").hexdigest()
        Path(checksum).write_text(f"{digest}  {output.name}\n", encoding="utf-8")
    print("Release APK signed and verified; private signing material removed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--previous-apk", type=Path)
    parser.add_argument("--checksum-file", type=Path)
    args = parser.parse_args()
    sign_release(args.input, args.output, args.build_tools, args.previous_apk, args.checksum_file)


if __name__ == "__main__":
    main()
