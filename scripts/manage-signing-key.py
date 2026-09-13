"""Provision or rotate GitHub-only APK signing keys without persistent local key files.

Use an authenticated gh CLI. Never prints credentials or stores an administrator token
in Actions. Rotation prepares and verifies the public lineage on the release branch
before replacing the active signing bundle with one atomic Secrets update.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time


ACTIVE = "RELEASE_SIGNING_BUNDLE"
NEXT = "RELEASE_NEXT_SIGNING_BUNDLE"
LOCK = "heads/signing/maintenance-lock"
LEGACY = {"RELEASE_KEYSTORE_BASE64", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS",
          "RELEASE_KEY_PASSWORD", "RELEASE_SIGNING_LINEAGE_BASE64"}


def command(args, *, data=None, env=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, env=env, check=False)
    if result.returncode:
        # Arguments, stdout and stderr can contain signing material; never echo them.
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode}); secret values withheld")
    return result.stdout


def create_bundle(keytool):
    password = secrets.token_urlsafe(48)
    env = {name: value for name, value in os.environ.items()
           if not name.startswith("RELEASE_") and name not in {"GH_TOKEN", "GITHUB_TOKEN"}}
    env["FILEACCESS_PROVISION_PASSWORD"] = password
    directory = Path(tempfile.mkdtemp(prefix="fileaccess-signing-")).resolve()
    key_file = directory / "signing.jks"
    try:
        command([keytool, "-genkeypair", "-noprompt", "-storetype", "JKS",
                 "-keystore", str(key_file), "-alias", "fileaccess", "-keyalg", "RSA",
                 "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10950",
                 "-dname", "CN=FileAccess, O=FileAccess",
                 "-storepass:env", "FILEACCESS_PROVISION_PASSWORD",
                 "-keypass:env", "FILEACCESS_PROVISION_PASSWORD"], env=env)
        certificate = command([keytool, "-exportcert", "-keystore", str(key_file),
                               "-alias", "fileaccess", "-storepass:env",
                               "FILEACCESS_PROVISION_PASSWORD"], env=env)
        bundle = {"schema": 1, "keystore_base64": base64.b64encode(key_file.read_bytes()).decode("ascii"),
                  "store_password": password, "key_alias": "fileaccess", "key_password": password,
                  "lineage_base64": ""}
        return bundle, hashlib.sha256(certificate).hexdigest()
    finally:
        # Exact files only; no recursive cleanup and no retained local signing backup.
        if key_file.parent.resolve() != directory:
            raise RuntimeError("Unexpected temporary signing path")
        key_file.unlink(missing_ok=True)
        directory.rmdir()
        env.pop("FILEACCESS_PROVISION_PASSWORD", None)


class GitHub:
    def __init__(self, executable, repo, environment):
        self.executable, self.repo, self.environment = executable, repo, environment
        self.write_guard = None

    def run(self, *args, data=None):
        return command([self.executable, *args], data=data)

    def api(self, endpoint):
        return json.loads(self.run("api", f"repos/{self.repo}/{endpoint}"))

    def mutate(self, endpoint, body=None, method="POST"):
        args = ["api", "--method", method, f"repos/{self.repo}/{endpoint}"]
        if body is not None:
            args += ["--input", "-"]
        result = self.run(*args, data=json.dumps(body).encode("utf-8") if body is not None else None)
        return json.loads(result) if result.strip() else None

    def secret_names(self):
        return {item["name"] for item in json.loads(self.run(
            "secret", "list", "--repo", self.repo, "--env", self.environment, "--json", "name"))}

    def set_secret(self, name, bundle):
        if self.write_guard is not None:
            self.write_guard()
        self.run("secret", "set", name, "--repo", self.repo, "--env", self.environment,
                 data=json.dumps(bundle, separators=(",", ":")).encode("utf-8"))

    def delete_secret(self, name):
        if self.write_guard is not None:
            self.write_guard()
        self.run("secret", "delete", name, "--repo", self.repo, "--env", self.environment)


class SigningLock:
    """Atomic Git ref creation serializes cooperating clients across machines."""

    def __init__(self, github, operation, nonce):
        self.github, self.operation, self.nonce = github, operation, nonce
        self.sha = None

    def acquire(self):
        base = self.github.api("git/ref/heads/master")["object"]["sha"]
        tree = self.github.api(f"git/commits/{base}")["tree"]["sha"]
        commit = self.github.mutate("git/commits", {
            "message": "FileAccess signing maintenance\n" + json.dumps({
                "operation": self.operation, "nonce": self.nonce}),
            "tree": tree, "parents": [base]})
        self.sha = commit["sha"]
        try:
            self.github.mutate("git/refs", {"ref": f"refs/{LOCK}", "sha": self.sha})
        except RuntimeError:
            # A lost HTTP response may still mean this exact lock was created.
            existing = self.github.api(f"git/ref/{LOCK}")["object"]["sha"]
            if existing != self.sha:
                self.sha = None
                raise RuntimeError(f"Signing maintenance is locked at {existing}; inspect/recover it first") from None
        print(f"Acquired signing maintenance lock: {self.sha}", flush=True)
        self.github.write_guard = self.assert_owned

    def assert_owned(self):
        if self.github.api(f"git/ref/{LOCK}")["object"]["sha"] != self.sha:
            raise RuntimeError("Signing lock owner changed; refusing further writes or cleanup")

    def release(self):
        self.assert_owned()
        self.github.mutate(f"git/refs/{LOCK}", method="DELETE")
        self.github.write_guard = None

    def claim_recovery(self, old_sha):
        if self.github.api(f"git/ref/{LOCK}")["object"]["sha"] != old_sha:
            raise ValueError("Recovery lock does not match the retained operation")
        tree = self.github.api(f"git/commits/{old_sha}")["tree"]["sha"]
        commit = self.github.mutate("git/commits", {
            "message": "FileAccess signing recovery\n" + json.dumps({"nonce": self.nonce}),
            "tree": tree, "parents": [old_sha]})
        self.sha = commit["sha"]
        # Competing recovery commits are siblings: only one can fast-forward the ref.
        try:
            self.github.mutate(f"git/refs/{LOCK}", {"sha": self.sha, "force": False}, method="PATCH")
        except RuntimeError:
            if self.github.api(f"git/ref/{LOCK}")["object"]["sha"] != self.sha:
                raise RuntimeError("Another operation acquired recovery ownership") from None
        self.github.write_guard = self.assert_owned
        print(f"Recovery owns signing lock: {self.sha}", flush=True)


def check_state(names, operation):
    if names & LEGACY:
        raise ValueError("Legacy signing secrets exist; migrate deliberately before managing a bundle")
    if NEXT in names:
        raise ValueError("A pending rotation exists; inspect it before creating or replacing any key")
    if operation == "initialize" and ACTIVE in names:
        raise ValueError("An active signing key already exists; initialize will never overwrite it")
    if operation == "rotate" and ACTIVE not in names:
        raise ValueError("No active signing bundle exists")


def validate_rotation_result(metadata, lineage, *, nonce, fingerprint, sha):
    if metadata.get("operation") != "prepare":
        raise ValueError("Unexpected signing maintenance operation")
    if metadata.get("nonce") != nonce or metadata.get("new_certificate_sha256") != fingerprint:
        raise ValueError("Rotation result does not match this request and new certificate")
    if metadata.get("source_sha") != sha or metadata.get("lineage_sha256") != hashlib.sha256(lineage).hexdigest():
        raise ValueError("Rotation result does not match the verified workflow commit or lineage")
    if metadata.get("verified") is not True or not lineage:
        raise ValueError("Rotation result has not passed signing continuity verification")


def workflow_result(github, operation, nonce, sha):
    workflow = "prepare-signing-rotation.yml"
    github.api(f"contents/.github/workflows/{workflow}?ref={sha}")
    github.run("workflow", "run", workflow, "--repo", github.repo, "--ref", "release",
               "-f", f"operation={operation}", "-f", f"nonce={nonce}")
    run = None
    deadline = time.monotonic() + 1800
    while time.monotonic() < deadline:
        runs = json.loads(github.run("run", "list", "--repo", github.repo, "--workflow", workflow,
                                   "--branch", "release", "--event", "workflow_dispatch", "--limit", "30",
                                   "--json", "databaseId,displayTitle,headSha,status,conclusion"))
        matches = [item for item in runs if item["displayTitle"] == f"Signing keys {operation} {nonce}"
                   and item["headSha"] == sha]
        if len(matches) > 1:
            raise RuntimeError("Ambiguous rotation runs; active key was not replaced")
        if matches:
            run = matches[0]
            if run["status"] == "completed":
                break
        print(f"Waiting for GitHub signing {operation} verification", flush=True)
        time.sleep(20)
    if not run or run["status"] != "completed" or run["conclusion"] != "success":
        raise RuntimeError("Signing verification did not succeed; inspect the retained maintenance state")
    # These are public proof/certificate metadata, never private keys or passwords.
    directory = Path(tempfile.mkdtemp(prefix="fileaccess-public-lineage-")).resolve()
    try:
        github.run("run", "download", str(run["databaseId"]), "--repo", github.repo,
                   "--name", f"signing-rotation-{nonce}", "--dir", str(directory))
        lineage = (directory / "lineage.bin").read_bytes() if operation == "prepare" else b""
        metadata = json.loads((directory / "metadata.json").read_text(encoding="utf-8"))
    finally:
        # The artifact contract permits these two public files only. No recursive deletion.
        for name in ("lineage.bin", "metadata.json"):
            (directory / name).unlink(missing_ok=True)
        directory.rmdir()
    if metadata.get("nonce") != nonce or metadata.get("source_sha") != sha or metadata.get("operation") != operation:
        raise ValueError("Signing result does not match the requested operation, nonce and workflow commit")
    return metadata, lineage


def rotate(github, bundle, fingerprint, nonce):
    # Only this public branch's checked-in workflow may access the release environment.
    sha = github.api("git/ref/heads/release")["object"]["sha"]
    github.api(f"contents/.github/workflows/prepare-signing-rotation.yml?ref={sha}")
    request = {"nonce": nonce, "source_sha": sha, "new_certificate_sha256": fingerprint}
    pending = {"schema": 1, "bundle": bundle, "request": request}
    github.set_secret(NEXT, pending)
    # On failure leave NEXT in GitHub for deliberate recovery; do not overwrite ACTIVE.
    metadata, lineage = workflow_result(github, "prepare", nonce, sha)
    validate_rotation_result(metadata, lineage, nonce=nonce, fingerprint=fingerprint, sha=sha)
    if github.api("git/ref/heads/release")["object"]["sha"] != sha:
        raise RuntimeError("release advanced during rotation; active key unchanged, inspect pending key")
    bundle["lineage_base64"] = base64.b64encode(lineage).decode("ascii")
    request["old_certificate_sha256"] = metadata["old_certificate_sha256"]
    # First keep the fully verified bundle in NEXT. One update then atomically promotes
    # the key, both passwords and lineage; a network failure cannot create a mixed bundle.
    github.set_secret(NEXT, pending)
    github.set_secret(ACTIVE, bundle)
    github.delete_secret(NEXT)
    print(f"Promoted verified signing key; public certificate SHA-256: {fingerprint}")


def recover(github, lock_sha, expected_certificate, discard_pending):
    if not lock_sha or not expected_certificate:
        raise ValueError("Recovery requires --lock-sha and --expected-active-certificate")
    # The original manager must be stopped before recovery; lock checks cannot turn
    # the GitHub Secrets API into a cross-resource compare-and-swap transaction.
    lock = SigningLock(github, "recover", secrets.token_hex(16))
    lock.claim_recovery(lock_sha)
    if expected_certificate == "absent":
        if {ACTIVE, NEXT} & github.secret_names():
            raise ValueError("A signing key exists remotely; refusing recovery as absent")
        lock.release()
        print("Verified no signing key was stored; released initialization lock")
        return
    sha = github.api("git/ref/heads/release")["object"]["sha"]
    metadata, _ = workflow_result(github, "inspect", secrets.token_hex(16), sha)
    actual = metadata.get("active_certificate_sha256")
    if actual != expected_certificate:
        raise ValueError("Active certificate differs from expected recovery state; no secrets changed")
    pending = metadata.get("next_certificate_sha256")
    if pending is not None:
        if actual == pending:
            if not metadata.get("next_has_lineage"):
                raise ValueError("Promoted key has no verified pending lineage; inspect manually")
        elif not discard_pending:
            raise ValueError("Active key unchanged; pass --discard-pending to explicitly abandon the unused successor")
        github.delete_secret(NEXT)
    lock.release()
    print("Recovered verified signing state and released maintenance lock")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=["initialize", "rotate", "inspect", "recover"])
    parser.add_argument("--repo", required=True)
    parser.add_argument("--environment", default="release", choices=["release"])
    parser.add_argument("--gh", default="gh")
    parser.add_argument("--keytool", default="keytool")
    parser.add_argument("--lock-sha")
    parser.add_argument("--expected-active-certificate")
    parser.add_argument("--discard-pending", action="store_true")
    args = parser.parse_args()
    github = GitHub(args.gh, args.repo, args.environment)
    github.api(f"environments/{args.environment}")
    if args.operation == "inspect":
        sha = github.api("git/ref/heads/release")["object"]["sha"]
        metadata, _ = workflow_result(github, "inspect", secrets.token_hex(16), sha)
        print(json.dumps(metadata, indent=2))  # Public certificate/request metadata only.
        return
    if args.operation == "recover":
        recover(github, args.lock_sha, args.expected_active_certificate, args.discard_pending)
        return
    nonce = secrets.token_hex(16)
    lock = SigningLock(github, args.operation, nonce)
    lock.acquire()
    started_write = False
    try:
        check_state(github.secret_names(), args.operation)
        bundle, fingerprint = create_bundle(args.keytool)
        print(f"Generated public certificate SHA-256: {fingerprint}", flush=True)
        started_write = True
        if args.operation == "initialize":
            github.set_secret(ACTIVE, bundle)
            print(f"Created {ACTIVE} in {args.environment}")
        else:
            rotate(github, bundle, fingerprint, nonce)
    except BaseException:
        if started_write:
            print(f"Remote write may have completed; retained lock {lock.sha}. Inspect before recovery; do not overwrite keys.")
        else:
            lock.release()
        raise
    lock.release()
    print("Temporary local keystore removed; signing material is retained only in GitHub Secrets")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        # No traceback, subprocess output, or signing bundle is printed.
        print(f"Signing key management failed: {type(error).__name__}: {error}")
        raise SystemExit(1)
