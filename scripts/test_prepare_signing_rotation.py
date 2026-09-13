"""Request binding, public output and private-material cleanup without a real keystore."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_signing_rotation", Path(__file__).with_name("prepare-signing-rotation.py"))
rotation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rotation)

NONCE, SHA, OLD, NEW = "1" * 32, "a" * 40, "b" * 64, "c" * 64


def fixture_bundle(lineage=""):
    return {"schema": 1, "keystore_base64": "YWJj", "key_alias": "fixture-alias",
            "store_password": "fixture-store-secret", "key_password": "fixture-key-secret", "lineage_base64": lineage}


def fixture_envelope():
    return {"schema": 1, "bundle": fixture_bundle(),
            "request": {"nonce": NONCE, "source_sha": SHA, "new_certificate_sha256": NEW}}


class PrepareSigningRotationTest(unittest.TestCase):
    def test_prepare_requires_nonce_sha_and_release_branch_binding(self):
        envelope = rotation.pending(json.dumps(fixture_envelope()))
        rotation.validate_request("prepare", NONCE, SHA, "refs/heads/release", envelope)
        for nonce, sha, ref, pending in [("2" * 32, SHA, "refs/heads/release", envelope),
                                        (NONCE, "d" * 40, "refs/heads/release", envelope),
                                        (NONCE, SHA, "refs/heads/master", envelope),
                                        (NONCE, SHA, "refs/heads/release", None)]:
            with self.subTest(nonce=nonce, sha=sha, ref=ref), self.assertRaises(ValueError):
                rotation.validate_request("prepare", nonce, sha, ref, pending)
        # Status/recovery uses a new run nonce to inspect an existing pending request.
        rotation.validate_request("inspect", "2" * 32, SHA, "refs/heads/release", envelope)

    def test_envelope_rejects_unsupported_fields_and_untrusted_request_values(self):
        envelope = fixture_envelope()
        cases = [{**envelope, "schema": True}, {**envelope, "other": "field"},
                 {**envelope, "request": {**envelope["request"], "nonce": "unsafe\noutput=value"}},
                 {**envelope, "request": {**envelope["request"], "source_sha": None}},
                 {**envelope, "request": {**envelope["request"], "password": "not-public"}},
                 {**envelope, "bundle": {"schema": 1}}]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                rotation.pending(json.dumps(case))

    def test_optional_previous_certificate_binding_is_strictly_validated(self):
        envelope = fixture_envelope()
        envelope["request"]["old_certificate_sha256"] = OLD
        self.assertEqual(OLD, rotation.pending(json.dumps(envelope))["request"]["old_certificate_sha256"])
        for value in [None, "", "a" * 63, "A" * 64, True, {"unexpected": "value"}]:
            envelope["request"]["old_certificate_sha256"] = value
            with self.subTest(value=value), self.assertRaises(ValueError):
                rotation.pending(json.dumps(envelope))

    def test_rotation_extends_old_lineage_and_uses_password_environment_references(self):
        command = rotation.rotation_command("java", "apksigner.jar", "a.jks", "a", "b.jks", "b", "new.bin", "old.bin")
        self.assertEqual("old.bin", command[command.index("--in") + 1])
        self.assertIn("env:FILEACCESS_ROTATION_OLD_STORE", command)
        self.assertIn("env:FILEACCESS_ROTATION_NEW_KEY", command)
        self.assertEqual(2, command.count("--set-installed-data"))
        self.assertEqual(2, command.count("--set-rollback"))
        for index, value in enumerate(command):
            if value == "--set-rollback":
                self.assertEqual("false", command[index + 1])

    def test_native_tool_environment_and_failures_do_not_disclose_bundles(self):
        with patch.dict(os.environ, {"RELEASE_SIGNING_BUNDLE": "active-secret", "RELEASE_NEXT_SIGNING_BUNDLE": "next-secret",
                                     "GH_TOKEN": "github-secret", "FILEACCESS_ROTATION_OLD_KEY": "old-secret"}), \
                patch.object(rotation.subprocess, "run") as execute:
            execute.return_value = subprocess.CompletedProcess([], 0, b"", b"")
            rotation.execute(["keytool"], passwords={"FILEACCESS_ROTATION_STORE_PASSWORD": "only-this-password"})
            environment = execute.call_args.kwargs["env"]
            self.assertEqual("only-this-password", environment["FILEACCESS_ROTATION_STORE_PASSWORD"])
            for name in ["RELEASE_SIGNING_BUNDLE", "RELEASE_NEXT_SIGNING_BUNDLE", "GH_TOKEN", "FILEACCESS_ROTATION_OLD_KEY"]:
                self.assertNotIn(name, environment)
            execute.return_value = subprocess.CompletedProcess([], 1, b"active-secret", b"next-secret")
            with self.assertRaises(ValueError) as failure:
                rotation.execute(["keytool"])
            self.assertNotIn("active-secret", str(failure.exception))
            self.assertNotIn("next-secret", str(failure.exception))

    def exercise(self, operation, *, has_next=True, reject=False, wrong_certificate=False,
                 prepared=False, promoted=False, request_override=None, expected_failure=False):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "public"
            active = fixture_bundle(base64.b64encode(b"old-public-lineage").decode())
            environment = {"RELEASE_SIGNING_BUNDLE": json.dumps(active), "GITHUB_REF": "refs/heads/release",
                           "GITHUB_SHA": SHA, "RUNNER_TEMP": temporary}
            envelope = fixture_envelope()
            if prepared:
                envelope["bundle"]["lineage_base64"] = base64.b64encode(b"next-public-lineage").decode()
                envelope["request"]["old_certificate_sha256"] = OLD
            if request_override:
                envelope["request"].update(request_override)
            if has_next:
                environment["RELEASE_NEXT_SIGNING_BUNDLE"] = json.dumps(envelope)
            private_directories = []

            def unpack(directory, name, data, keytool):
                private_directories.append(Path(directory))
                secrets = rotation.signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(data)})
                fingerprint = (NEW if promoted else OLD) if name == "active" else ("d" * 64 if wrong_certificate else NEW)
                return Path(directory) / (name + ".jks"), secrets, fingerprint

            def execute(command, **kwargs):
                if "rotate" in command:
                    Path(command[command.index("--out") + 1]).write_bytes(b"verified-public-lineage")

            with patch.dict(os.environ, environment, clear=True), \
                    patch.object(rotation.signing, "java_tools", return_value=("java", "javac")), \
                    patch.object(rotation, "keytool_path", return_value="keytool"), \
                    patch.object(rotation, "unpack", side_effect=unpack), patch.object(rotation, "execute", side_effect=execute), \
                    patch.object(rotation, "verify_rotation", side_effect=ValueError("Invalid proof") if reject else None) as verify, \
                    patch.object(rotation, "verify_rotation_binding") as verify_binding:
                if reject or wrong_certificate or expected_failure:
                    with self.assertRaises(ValueError):
                        rotation.maintain(operation, NONCE, output, Path("sdk"), Path("android.jar"))
                    self.assertFalse(output.exists())
                else:
                    metadata = rotation.maintain(operation, NONCE, output, Path("sdk"), Path("android.jar"))
                    expected_files = {"metadata.json", "lineage.bin"} if operation == "prepare" else {"metadata.json"}
                    self.assertEqual(expected_files, {file.name for file in output.iterdir()})
                    serialized = (output / "metadata.json").read_text()
                    for secret in ["fixture-alias", "fixture-key-secret", "fixture-store-secret", "keystore_base64", "bundle"]:
                        self.assertNotIn(secret, serialized)
                    self.assertEqual(json.loads(serialized), metadata)
                    if operation == "prepare":
                        verify.assert_called_once()
                        self.assertTrue(metadata["verified"])
                        self.assertEqual(OLD, metadata["old_certificate_sha256"])
                        self.assertEqual(NEW, metadata["new_certificate_sha256"])
                    else:
                        if prepared:
                            verify.assert_called_once()
                            verify_binding.assert_called_once()
                            self.assertEqual((OLD, NEW), verify_binding.call_args.args[2:4])
                        else:
                            verify.assert_not_called()
                            verify_binding.assert_not_called()
                        self.assertEqual(NEW if has_next else None, metadata["next_certificate_sha256"])
                        self.assertEqual(envelope["request"] if has_next else None, metadata["next_request"])
                        self.assertEqual(prepared, metadata["next_has_lineage"])
            self.assertTrue(private_directories)
            self.assertTrue(all(not directory.exists() for directory in private_directories))

    def test_prepare_exports_only_verified_public_lineage_and_metadata(self):
        self.exercise("prepare")

    def test_bad_certificate_or_proof_leaves_no_artifact_and_cleans_private_directory(self):
        self.exercise("prepare", wrong_certificate=True)
        self.exercise("prepare", reject=True)

    def test_inspect_only_exports_public_metadata_with_or_without_pending_request(self):
        self.exercise("inspect")
        self.exercise("inspect", has_next=False)

    def test_inspect_verifies_prepared_history_before_and_after_promotion(self):
        self.exercise("inspect", prepared=True)
        self.exercise("inspect", prepared=True, promoted=True)
        self.exercise("inspect", prepared=True, reject=True)

    def test_previous_certificate_binding_must_match_active_key_before_preparation(self):
        self.exercise("prepare", request_override={"old_certificate_sha256": OLD})
        self.exercise("prepare", request_override={"old_certificate_sha256": "d" * 64}, expected_failure=True)

    def test_inspect_does_not_trust_a_nonempty_lineage_without_identity_binding(self):
        self.exercise("inspect", prepared=True, request_override={"old_certificate_sha256": NEW}, expected_failure=True)
        self.exercise("inspect", prepared=True, request_override={"old_certificate_sha256": "d" * 64}, expected_failure=True)
        self.exercise("inspect", prepared=True, wrong_certificate=True)


if __name__ == "__main__":
    unittest.main()
