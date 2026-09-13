"""No credentials, SDK, Gradle or network needed for signing orchestration tests."""
import base64
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("sign_release", Path(__file__).with_name("sign-release.py"))
signing = importlib.util.module_from_spec(spec)
spec.loader.exec_module(signing)


class SigningTest(unittest.TestCase):
    def test_atomic_bundle_maps_all_signing_inputs_together(self):
        bundle = {"schema": 1, "keystore_base64": "YWJj", "store_password": "store-test",
                  "key_alias": "fixture", "key_password": "key-test", "lineage_base64": "ZGVm"}
        result = signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(bundle)})
        self.assertEqual("store-test", result["RELEASE_STORE_PASSWORD"])
        self.assertEqual("YWJj", result["RELEASE_KEYSTORE_BASE64"])
        self.assertEqual("ZGVm", result["RELEASE_SIGNING_LINEAGE_BASE64"])
        for legacy in ["RELEASE_STORE_FILE", "RELEASE_KEYSTORE_BASE64", "RELEASE_STORE_PASSWORD", "RELEASE_SIGNING_LINEAGE_FILE"]:
            with self.subTest(legacy=legacy), self.assertRaises(ValueError):
                signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(bundle), legacy: "ambiguous"})
        for invalid in [{**bundle, "schema": True}, {**bundle, "schema": 2}, {**bundle, "key_password": ""},
                        {**bundle, "unknown": "field"}, {**bundle, "lineage_base64": None}, [], "bad"]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                signing.signing_environment({"RELEASE_SIGNING_BUNDLE": json.dumps(invalid)})

    def test_native_v3_rotation_does_not_require_old_key_or_expose_passwords(self):
        command = signing.sign_command("java", "apksigner.jar", "current.jks", "current", "in.apk", "out.apk", "lineage")
        self.assertIn("env:RELEASE_STORE_PASSWORD", command)
        self.assertIn("env:RELEASE_KEY_PASSWORD", command)
        self.assertEqual("28", command[command.index("--rotation-min-sdk-version") + 1])
        self.assertEqual("35", command[command.index("--min-sdk-version") + 1])
        self.assertEqual("false", command[command.index("--v1-signing-enabled") + 1])
        self.assertEqual("false", command[command.index("--v2-signing-enabled") + 1])
        self.assertEqual("false", command[command.index("--debuggable-apk-permitted") + 1])
        self.assertEqual(1, command.count("--ks"))
        self.assertNotIn("--next-signer", command)

    def test_subprocesses_only_receive_required_passwords_and_errors_do_not_echo_secrets(self):
        secrets = {"RELEASE_STORE_PASSWORD": "store-test", "RELEASE_KEY_PASSWORD": "key-test",
                   "RELEASE_KEYSTORE_BASE64": "key-blob", "RELEASE_SIGNING_LINEAGE_BASE64": "lineage-blob",
                   "RELEASE_SIGNING_BUNDLE": "bundle-secret", "GH_TOKEN": "github-test", "GITHUB_TOKEN": "github-test-2"}
        with patch.dict(os.environ, secrets), patch.object(signing.subprocess, "run") as execute:
            execute.return_value = subprocess.CompletedProcess([], 0, "verified", "")
            signing.run(["java"], signing=True)
            environment = execute.call_args.kwargs["env"]
            self.assertEqual("key-test", environment["RELEASE_KEY_PASSWORD"])
            for name in ["RELEASE_KEYSTORE_BASE64", "RELEASE_SIGNING_LINEAGE_BASE64", "RELEASE_SIGNING_BUNDLE", "GH_TOKEN", "GITHUB_TOKEN"]:
                self.assertNotIn(name, environment)
            signing.run(["javac"])
            self.assertFalse(any(key.startswith("RELEASE_") for key in execute.call_args.kwargs["env"]))
            execute.return_value = subprocess.CompletedProcess([], 1, "store-test", "key-test")
            with self.assertRaises(ValueError) as failure:
                signing.run(["java"], signing=True)
            self.assertNotIn("store-test", str(failure.exception))
            self.assertNotIn("key-test", str(failure.exception))

    def test_material_rejects_ambiguous_or_malformed_input(self):
        with tempfile.TemporaryDirectory() as temporary:
            with patch.dict(os.environ, {"TEST_BLOB": "YWJj", "TEST_FILE": "unused"}):
                with self.assertRaises(ValueError):
                    signing.material(temporary, "key", "TEST_BLOB", "TEST_FILE")
            with patch.dict(os.environ, {"TEST_BLOB": "not-base64!"}, clear=True):
                with self.assertRaises(ValueError):
                    signing.material(temporary, "key", "TEST_BLOB", "TEST_FILE")
            with patch.dict(os.environ, {}, clear=True):
                with self.assertRaises(ValueError):
                    signing.material(temporary, "key", "TEST_BLOB", "TEST_FILE", required=True)

    def exercise_signing(self, *, reject=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, output = root / "unsigned.apk", root / "release" / "signed.apk"
            source.write_bytes(b"unsigned-fixture")
            (root / "lib").mkdir()
            (root / "lib" / "apksigner.jar").touch()
            seen_keys = []

            def execute(command, **kwargs):
                if "sign" in command:
                    key = Path(command[command.index("--ks") + 1])
                    self.assertEqual(b"temporary-fixture", key.read_bytes())
                    seen_keys.append(key)
                    Path(command[command.index("--out") + 1]).write_bytes(b"signed-fixture")
                if "VerifySigningLineage" in command and reject:
                    raise ValueError("Unrelated signer")
                return ""

            environment = {"RELEASE_KEY_ALIAS": "fixture", "RELEASE_KEYSTORE_BASE64": base64.b64encode(b"temporary-fixture").decode(),
                           "RUNNER_TEMP": temporary}
            with patch.dict(os.environ, environment, clear=True), patch.object(signing, "java_tools", return_value=("java", "javac")), \
                    patch.object(signing, "tool", return_value="zipalign"), patch.object(signing, "run", side_effect=execute):
                if reject:
                    with self.assertRaises(ValueError):
                        signing.sign_release(source, output, root)
                    self.assertFalse(output.exists())
                else:
                    checksum = output.parent / "SHA256SUMS"
                    signing.sign_release(source, output, root, checksum=checksum)
                    self.assertEqual(b"signed-fixture", output.read_bytes())
                    self.assertTrue(checksum.read_text().endswith("  signed.apk\n"))
            self.assertEqual(1, len(seen_keys))
            self.assertFalse(seen_keys[0].parent.exists())

    def test_only_verified_apk_is_published_and_private_material_is_removed(self):
        self.exercise_signing()

    def test_rejected_lineage_never_publishes_and_still_removes_material(self):
        self.exercise_signing(reject=True)

    def test_missing_previous_apk_cannot_be_treated_as_first_release(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "source.apk"
            source.touch()
            with self.assertRaisesRegex(ValueError, "Previously published APK"):
                signing.sign_release(source, Path(temporary) / "output.apk", temporary, previous="missing-previous.apk")


if __name__ == "__main__":
    unittest.main()
