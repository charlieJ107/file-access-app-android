"""Signing-key lifecycle tests use only fake keys, fake GitHub state and mocked tools."""
import base64
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("manage_signing_key", Path(__file__).with_name("manage-signing-key.py"))
manager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(manager)

FINGERPRINT = "ab" * 32
OLD_FINGERPRINT = "cd" * 32
SOURCE_SHA = "1" * 40
NONCE = "2" * 32
LINEAGE = b"public-test-lineage"


def fake_bundle():
    return {"schema": 1, "keystore_base64": "ZmFrZS1rZXk=", "store_password": "test-only-password",
            "key_password": "test-only-password", "key_alias": "fixture", "lineage_base64": ""}


def rotation_metadata(**changes):
    return {"operation": "prepare", "nonce": NONCE, "source_sha": SOURCE_SHA,
            "new_certificate_sha256": FINGERPRINT, "old_certificate_sha256": OLD_FINGERPRINT,
            "lineage_sha256": hashlib.sha256(LINEAGE).hexdigest(), "verified": True, **changes}


class MemoryGitHub(manager.GitHub):
    def __init__(self):
        super().__init__("fixture-gh", "fixture/repository", "release")
        self.refs = {"heads/master": "0" * 40, "heads/release": SOURCE_SHA}
        self.parents = {}
        self.secrets = {}
        self.events = []
        self.sequence = 3
        self.lose_ref_response = False
        self.lose_active_response = False
        self.fail_next_delete = False

    def api(self, endpoint):
        if endpoint.startswith("git/ref/"):
            return {"object": {"sha": self.refs[endpoint.removeprefix("git/ref/")]}}
        if endpoint.startswith("git/commits/"):
            return {"tree": {"sha": "tree"}}
        if endpoint.startswith(("contents/", "environments/")):
            return {}
        raise AssertionError("Unexpected read: " + endpoint)

    def mutate(self, endpoint, body=None, method="POST"):
        self.events.append((method, endpoint, copy.deepcopy(body)))
        if endpoint == "git/commits":
            self.sequence += 1
            sha = f"{self.sequence:040x}"
            self.parents[sha] = body["parents"]
            return {"sha": sha}
        if endpoint == "git/refs":
            ref = body["ref"].removeprefix("refs/")
            if ref in self.refs:
                raise RuntimeError("Reference already exists")
            self.refs[ref] = body["sha"]
            if self.lose_ref_response:
                self.lose_ref_response = False
                raise RuntimeError("Lost response after accepting reference")
            return {}
        if method == "PATCH" and endpoint.startswith("git/refs/"):
            ref = endpoint.removeprefix("git/refs/")
            if body.get("force") is not False or self.refs[ref] not in self.parents[body["sha"]]:
                raise RuntimeError("Reference update is not a fast-forward")
            self.refs[ref] = body["sha"]
            if self.lose_ref_response:
                self.lose_ref_response = False
                raise RuntimeError("Lost response after accepting reference")
            return {}
        if method == "DELETE" and endpoint.startswith("git/refs/"):
            del self.refs[endpoint.removeprefix("git/refs/")]
            return None
        raise AssertionError("Unexpected mutation: " + endpoint)

    def secret_names(self):
        self.events.append(("read-secrets",))
        return set(self.secrets)

    def run(self, *args, data=None):
        if args[:2] == ("secret", "set"):
            name, value = args[2], json.loads(data)
            self.events.append(("set-secret", name, copy.deepcopy(value)))
            self.secrets[name] = value
            if name == manager.ACTIVE and self.lose_active_response:
                raise RuntimeError("Lost response after accepting active bundle")
            return b""
        if args[:2] == ("secret", "delete"):
            name = args[2]
            self.events.append(("delete-secret", name))
            if self.fail_next_delete:
                raise RuntimeError("Could not delete pending bundle")
            del self.secrets[name]
            return b""
        raise AssertionError("Unexpected command: " + str(args))


class SigningManagementTest(unittest.TestCase):
    def test_competing_clients_cannot_acquire_the_same_maintenance_lock(self):
        github = MemoryGitHub()
        owner = manager.SigningLock(github, "initialize", "first")
        contender = manager.SigningLock(github, "initialize", "second")
        with contextlib.redirect_stdout(io.StringIO()):
            owner.acquire()
            with self.assertRaisesRegex(RuntimeError, "locked"):
                contender.acquire()
        self.assertEqual(owner.sha, github.refs[manager.LOCK])
        self.assertIsNone(contender.sha)
        self.assertFalse(github.secrets)
        self.assertTrue(all(event[0] != "PATCH" for event in github.events))

    def test_lost_lock_creation_response_is_reconciled_using_exact_owner_sha(self):
        github = MemoryGitHub()
        github.lose_ref_response = True
        lock = manager.SigningLock(github, "initialize", NONCE)
        with contextlib.redirect_stdout(io.StringIO()):
            lock.acquire()
        self.assertEqual(lock.sha, github.refs[manager.LOCK])
        lock.release()
        self.assertNotIn(manager.LOCK, github.refs)

    def test_lock_release_never_deletes_a_different_owners_ref(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "another-owner"
        lock = manager.SigningLock(github, "rotate", NONCE)
        lock.sha = "old-owner"
        with self.assertRaisesRegex(RuntimeError, "owner changed"):
            lock.release()
        self.assertEqual("another-owner", github.refs[manager.LOCK])

    def peer(self, github):
        peer = MemoryGitHub()
        peer.refs, peer.parents = github.refs, github.parents
        peer.secrets, peer.events = github.secrets, github.events
        peer.sequence = github.sequence + 100
        return peer

    def test_recovery_claim_blocks_the_previous_clients_secret_writes_and_deletes(self):
        github = MemoryGitHub()
        owner = manager.SigningLock(github, "rotate", NONCE)
        with contextlib.redirect_stdout(io.StringIO()):
            owner.acquire()
            recovery = manager.SigningLock(self.peer(github), "recover", "recovery")
            recovery.claim_recovery(owner.sha)
        github.secrets[manager.NEXT] = {"preserved": True}
        with self.assertRaisesRegex(RuntimeError, "owner changed"):
            github.set_secret(manager.ACTIVE, fake_bundle())
        with self.assertRaisesRegex(RuntimeError, "owner changed"):
            github.delete_secret(manager.NEXT)
        with self.assertRaisesRegex(RuntimeError, "owner changed"):
            owner.release()
        self.assertEqual({manager.NEXT: {"preserved": True}}, github.secrets)
        self.assertEqual(recovery.sha, github.refs[manager.LOCK])

    def test_competing_recovery_claims_only_allow_one_sibling_to_fast_forward(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        first = manager.SigningLock(github, "recover", "first")
        second = manager.SigningLock(self.peer(github), "recover", "second")
        original_mutate = github.mutate

        def race(endpoint, body=None, method="POST"):
            if method == "PATCH":
                second.claim_recovery("retained-owner")
            return original_mutate(endpoint, body, method)

        with patch.object(github, "mutate", side_effect=race), contextlib.redirect_stdout(io.StringIO()), \
                self.assertRaisesRegex(RuntimeError, "Another operation"):
            first.claim_recovery("retained-owner")
        self.assertEqual(second.sha, github.refs[manager.LOCK])
        self.assertIsNone(github.write_guard)
        self.assertTrue(all(event[2]["force"] is False for event in github.events if event[0] == "PATCH"))

    def test_lost_recovery_claim_response_reconciles_exact_new_owner(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        github.lose_ref_response = True
        recovery = manager.SigningLock(github, "recover", NONCE)
        with contextlib.redirect_stdout(io.StringIO()):
            recovery.claim_recovery("retained-owner")
        self.assertEqual(recovery.sha, github.refs[manager.LOCK])
        github.set_secret(manager.ACTIVE, fake_bundle())
        recovery.release()
        self.assertIsNone(github.write_guard)

    def test_state_checks_reject_overwrite_pending_rotation_and_legacy_keys(self):
        manager.check_state(set(), "initialize")
        manager.check_state({manager.ACTIVE}, "rotate")
        cases = [({manager.ACTIVE}, "initialize"), (set(), "rotate"),
                 ({manager.NEXT}, "initialize"), ({manager.ACTIVE, manager.NEXT}, "rotate")]
        cases += [({legacy}, "initialize") for legacy in manager.LEGACY]
        for names, operation in cases:
            with self.subTest(names=names, operation=operation), self.assertRaises(ValueError):
                manager.check_state(names, operation)

    def run_main(self, github, operation="initialize", *, generate=None):
        output = io.StringIO()
        with patch.object(manager, "GitHub", return_value=github), \
                patch.object(manager, "create_bundle", side_effect=generate, return_value=(fake_bundle(), FINGERPRINT)), \
                patch.object(manager.secrets, "token_hex", return_value=NONCE), \
                patch("sys.argv", ["manage-signing-key.py", operation, "--repo", github.repo]), \
                contextlib.redirect_stdout(output):
            manager.main()
        return output.getvalue()

    def test_initialization_acquires_lock_before_checking_state_and_atomically_sets_only_active(self):
        github = MemoryGitHub()
        output = self.run_main(github)
        lock_index = next(i for i, event in enumerate(github.events) if event[:2] == ("POST", "git/refs"))
        check_index = github.events.index(("read-secrets",))
        self.assertLess(lock_index, check_index)
        writes = [event for event in github.events if event[0] == "set-secret"]
        self.assertEqual([manager.ACTIVE], [event[1] for event in writes])
        self.assertEqual(fake_bundle(), github.secrets[manager.ACTIVE])
        self.assertNotIn(manager.LOCK, github.refs)
        self.assertIn(FINGERPRINT, output)
        self.assertNotIn("test-only-password", output)
        self.assertNotIn(fake_bundle()["keystore_base64"], output)

    def test_existing_active_key_is_not_overwritten_and_unused_lock_is_released(self):
        github = MemoryGitHub()
        github.secrets[manager.ACTIVE] = {"preserved": True}
        with self.assertRaisesRegex(ValueError, "never overwrite"):
            self.run_main(github)
        self.assertEqual({"preserved": True}, github.secrets[manager.ACTIVE])
        self.assertNotIn(manager.LOCK, github.refs)
        self.assertFalse(any(event[0] == "set-secret" for event in github.events))

    def test_local_generation_failure_releases_lock_without_any_secret_mutation(self):
        github = MemoryGitHub()
        with self.assertRaisesRegex(RuntimeError, "generator failed"):
            self.run_main(github, generate=RuntimeError("generator failed"))
        self.assertNotIn(manager.LOCK, github.refs)
        self.assertFalse(github.secrets)

    def test_uncertain_initialization_write_preserves_lock_and_never_retries_overwrite(self):
        github = MemoryGitHub()
        github.lose_active_response = True
        with self.assertRaisesRegex(RuntimeError, "Lost response"):
            self.run_main(github)
        self.assertEqual(fake_bundle(), github.secrets[manager.ACTIVE])
        self.assertIn(manager.LOCK, github.refs)
        with self.assertRaisesRegex(RuntimeError, "locked"):
            self.run_main(github)
        self.assertEqual(1, sum(event[0] == "set-secret" for event in github.events))

    def test_nonce_commit_digest_certificate_and_verification_flag_are_all_required(self):
        manager.validate_rotation_result(rotation_metadata(), LINEAGE, nonce=NONCE, fingerprint=FINGERPRINT, sha=SOURCE_SHA)
        invalid = [{"operation": "inspect"}, {"nonce": "wrong"}, {"source_sha": "wrong"},
                   {"new_certificate_sha256": OLD_FINGERPRINT}, {"lineage_sha256": "wrong"},
                   {"verified": False}, {"verified": "true"}]
        for changes in invalid:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                manager.validate_rotation_result(rotation_metadata(**changes), LINEAGE,
                                                 nonce=NONCE, fingerprint=FINGERPRINT, sha=SOURCE_SHA)
        with self.assertRaises(ValueError):
            manager.validate_rotation_result(rotation_metadata(lineage_sha256=hashlib.sha256(b"").hexdigest()), b"",
                                             nonce=NONCE, fingerprint=FINGERPRINT, sha=SOURCE_SHA)

    def rotate(self, github, *, metadata=None, error=None):
        with patch.object(manager, "workflow_result", return_value=(metadata or rotation_metadata(), LINEAGE), side_effect=error), \
                contextlib.redirect_stdout(io.StringIO()):
            manager.rotate(github, fake_bundle(), FINGERPRINT, NONCE)

    def test_rotation_promotes_only_a_complete_verified_bundle_and_then_removes_pending(self):
        github = MemoryGitHub()
        github.secrets[manager.ACTIVE] = {"old": True}
        self.rotate(github)
        events = [event for event in github.events if event[0] in {"set-secret", "delete-secret"}]
        self.assertEqual([("set-secret", manager.NEXT), ("set-secret", manager.NEXT),
                          ("set-secret", manager.ACTIVE), ("delete-secret", manager.NEXT)],
                         [event[:2] for event in events])
        initial_pending, verified_pending = events[0][2], events[1][2]
        self.assertEqual("", initial_pending["bundle"]["lineage_base64"])
        self.assertEqual(NONCE, initial_pending["request"]["nonce"])
        self.assertEqual(SOURCE_SHA, initial_pending["request"]["source_sha"])
        self.assertEqual(FINGERPRINT, initial_pending["request"]["new_certificate_sha256"])
        self.assertEqual(OLD_FINGERPRINT, verified_pending["request"]["old_certificate_sha256"])
        self.assertEqual(base64.b64encode(LINEAGE).decode(), github.secrets[manager.ACTIVE]["lineage_base64"])
        self.assertEqual(verified_pending["bundle"], github.secrets[manager.ACTIVE])
        self.assertNotIn(manager.NEXT, github.secrets)

    def test_verification_failure_or_wrong_metadata_retains_pending_and_preserves_active(self):
        for failure in [RuntimeError("CI failed"), "wrong-metadata"]:
            github = MemoryGitHub()
            github.secrets[manager.ACTIVE] = {"old": True}
            with self.subTest(failure=failure), self.assertRaises((RuntimeError, ValueError)):
                self.rotate(github, error=failure if isinstance(failure, RuntimeError) else None,
                            metadata=rotation_metadata(nonce="wrong") if isinstance(failure, str) else None)
            self.assertEqual({"old": True}, github.secrets[manager.ACTIVE])
            self.assertEqual(NONCE, github.secrets[manager.NEXT]["request"]["nonce"])
            self.assertFalse(any(event[:2] == ("delete-secret", manager.NEXT) for event in github.events))

    def test_release_advance_after_verification_never_promotes(self):
        github = MemoryGitHub()
        github.secrets[manager.ACTIVE] = {"old": True}

        def verified(*args):
            github.refs["heads/release"] = "advanced"
            return rotation_metadata(), LINEAGE

        with patch.object(manager, "workflow_result", side_effect=verified), self.assertRaisesRegex(RuntimeError, "release advanced"):
            manager.rotate(github, fake_bundle(), FINGERPRINT, NONCE)
        self.assertEqual({"old": True}, github.secrets[manager.ACTIVE])
        self.assertIn(manager.NEXT, github.secrets)

    def test_rotation_resuming_after_recovery_cannot_promote_or_remove_pending(self):
        github = MemoryGitHub()
        github.secrets[manager.ACTIVE] = {"old": True}
        owner = manager.SigningLock(github, "rotate", NONCE)

        def verified(*args):
            recovery = manager.SigningLock(self.peer(github), "recover", "recovery")
            recovery.claim_recovery(owner.sha)
            return rotation_metadata(), LINEAGE

        with contextlib.redirect_stdout(io.StringIO()):
            owner.acquire()
            with patch.object(manager, "workflow_result", side_effect=verified), \
                    self.assertRaisesRegex(RuntimeError, "owner changed"):
                manager.rotate(github, fake_bundle(), FINGERPRINT, NONCE)
        self.assertEqual({"old": True}, github.secrets[manager.ACTIVE])
        self.assertEqual("", github.secrets[manager.NEXT]["bundle"]["lineage_base64"])
        self.assertEqual(1, sum(event[0] == "set-secret" for event in github.events))
        self.assertFalse(any(event[0] == "delete-secret" for event in github.events))

    def test_lost_promotion_response_keeps_a_complete_recovery_copy(self):
        github = MemoryGitHub()
        github.secrets[manager.ACTIVE] = {"old": True}
        github.lose_active_response = True
        with self.assertRaisesRegex(RuntimeError, "Lost response"):
            self.rotate(github)
        self.assertEqual(github.secrets[manager.ACTIVE], github.secrets[manager.NEXT]["bundle"])
        self.assertEqual(OLD_FINGERPRINT, github.secrets[manager.NEXT]["request"]["old_certificate_sha256"])

    def test_pending_cleanup_failure_does_not_undo_successful_promotion(self):
        github = MemoryGitHub()
        github.fail_next_delete = True
        with self.assertRaisesRegex(RuntimeError, "Could not delete"):
            self.rotate(github)
        self.assertEqual(github.secrets[manager.ACTIVE], github.secrets[manager.NEXT]["bundle"])

    def test_recovery_refuses_wrong_owner_or_wrong_active_certificate(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        github.secrets[manager.NEXT] = {"preserved": True}
        with self.assertRaisesRegex(ValueError, "lock does not match"):
            manager.recover(github, "wrong-owner", FINGERPRINT, True)
        metadata = {"active_certificate_sha256": OLD_FINGERPRINT, "next_certificate_sha256": FINGERPRINT}
        with patch.object(manager, "workflow_result", return_value=(metadata, b"")), self.assertRaisesRegex(ValueError, "differs"):
            manager.recover(github, "retained-owner", FINGERPRINT, True)
        self.assertEqual({"preserved": True}, github.secrets[manager.NEXT])
        self.assertIn(manager.LOCK, github.refs)

    def test_recovery_does_not_discard_an_unpromoted_key_without_explicit_request(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        github.secrets[manager.NEXT] = {"preserved": True}
        metadata = {"active_certificate_sha256": OLD_FINGERPRINT, "next_certificate_sha256": FINGERPRINT}
        with patch.object(manager, "workflow_result", return_value=(metadata, b"")):
            with self.assertRaisesRegex(ValueError, "discard-pending"):
                manager.recover(github, "retained-owner", OLD_FINGERPRINT, False)
            self.assertIn(manager.NEXT, github.secrets)
            with contextlib.redirect_stdout(io.StringIO()):
                manager.recover(github, github.refs[manager.LOCK], OLD_FINGERPRINT, True)
        self.assertNotIn(manager.NEXT, github.secrets)
        self.assertNotIn(manager.LOCK, github.refs)

    def test_recovery_cleans_an_already_promoted_key_only_with_verified_pending_lineage(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        github.secrets[manager.NEXT] = {"preserved": True}
        metadata = {"active_certificate_sha256": FINGERPRINT, "next_certificate_sha256": FINGERPRINT,
                    "next_has_lineage": False}
        with patch.object(manager, "workflow_result", return_value=(metadata, b"")):
            with self.assertRaisesRegex(ValueError, "no verified"):
                manager.recover(github, "retained-owner", FINGERPRINT, False)
            self.assertIn(manager.NEXT, github.secrets)
            metadata["next_has_lineage"] = True
            with contextlib.redirect_stdout(io.StringIO()):
                manager.recover(github, github.refs[manager.LOCK], FINGERPRINT, False)
        self.assertNotIn(manager.NEXT, github.secrets)
        self.assertNotIn(manager.LOCK, github.refs)

    def test_absent_recovery_releases_failed_initialization_without_needing_release_workflow(self):
        github = MemoryGitHub()
        github.refs[manager.LOCK] = "retained-owner"
        with patch.object(manager, "workflow_result") as workflow, contextlib.redirect_stdout(io.StringIO()):
            manager.recover(github, "retained-owner", "absent", False)
        workflow.assert_not_called()
        self.assertNotIn(manager.LOCK, github.refs)
        self.assertFalse(github.secrets)

    def test_absent_recovery_never_removes_a_lock_if_active_or_pending_key_exists(self):
        for secret in [manager.ACTIVE, manager.NEXT]:
            github = MemoryGitHub()
            github.refs[manager.LOCK] = "retained-owner"
            github.secrets[secret] = {"preserved": True}
            with self.subTest(secret=secret), contextlib.redirect_stdout(io.StringIO()), \
                    self.assertRaisesRegex(ValueError, "key exists"):
                manager.recover(github, "retained-owner", "absent", True)
            self.assertIn(manager.LOCK, github.refs)
            self.assertEqual({secret: {"preserved": True}}, github.secrets)

    def test_generated_temporary_key_is_removed_on_success_and_tool_failure(self):
        for fail_export in [False, True]:
            with self.subTest(fail_export=fail_export), tempfile.TemporaryDirectory() as parent:
                directory = Path(parent) / "disposable-key"
                directory.mkdir()
                calls = []

                def keytool(args, **kwargs):
                    calls.append((args, kwargs))
                    key = Path(args[args.index("-keystore") + 1])
                    if "-genkeypair" in args:
                        key.write_bytes(b"fake-private-key")
                        return b""
                    if fail_export:
                        raise RuntimeError("simulated export failure")
                    return b"fake-public-certificate"

                with patch.object(manager.tempfile, "mkdtemp", return_value=str(directory)), \
                        patch.object(manager, "command", side_effect=keytool), \
                        patch.object(manager.secrets, "token_urlsafe", return_value="test-only-password"):
                    if fail_export:
                        with self.assertRaisesRegex(RuntimeError, "export failure"):
                            manager.create_bundle("fixture-keytool")
                    else:
                        bundle, fingerprint = manager.create_bundle("fixture-keytool")
                        self.assertEqual(b"fake-private-key", base64.b64decode(bundle["keystore_base64"]))
                        self.assertEqual(hashlib.sha256(b"fake-public-certificate").hexdigest(), fingerprint)
                self.assertFalse(directory.exists())
                self.assertTrue(all("test-only-password" not in args for args, _ in calls))
                self.assertTrue(all("-storepass:env" in args for args, _ in calls))

    def test_command_failure_never_reports_tool_output_arguments_or_passwords(self):
        result = subprocess.CompletedProcess([], 9, b"secret-output", b"secret-error")
        with patch.object(manager.subprocess, "run", return_value=result), self.assertRaises(RuntimeError) as failure:
            manager.command(["fixture-keytool", "secret-argument"], data=b"secret-input")
        for secret in ["secret-output", "secret-error", "secret-argument", "secret-input"]:
            self.assertNotIn(secret, str(failure.exception))


class WorkflowResultTest(unittest.TestCase):
    def exercise(self, *, operation="prepare", run_changes=None, metadata_changes=None, duplicate=False):
        github = MemoryGitHub()
        row = {"databaseId": 42, "displayTitle": f"Signing keys {operation} {NONCE}",
               "headSha": SOURCE_SHA, "status": "completed", "conclusion": "success", **(run_changes or {})}
        metadata = rotation_metadata(**{"operation": operation, **(metadata_changes or {})})
        downloads = []

        def run(*args, **kwargs):
            if args[:2] == ("workflow", "run"):
                return b""
            if args[:2] == ("run", "list"):
                return json.dumps([row, row] if duplicate else [row]).encode()
            if args[:2] == ("run", "download"):
                directory = Path(args[args.index("--dir") + 1])
                downloads.append(directory)
                (directory / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
                if operation == "prepare":
                    (directory / "lineage.bin").write_bytes(LINEAGE)
                return b""
            raise AssertionError("Unexpected command: " + str(args))

        try:
            with patch.object(github, "run", side_effect=run, create=True), \
                    patch.object(manager.time, "monotonic", side_effect=[0, 0, 1801]), \
                    patch.object(manager.time, "sleep"), contextlib.redirect_stdout(io.StringIO()):
                return manager.workflow_result(github, operation, NONCE, SOURCE_SHA)
        finally:
            self.assertTrue(all(not path.exists() for path in downloads), "Public artifact directories must be cleaned")

    def test_successful_prepare_and_inspect_results_are_tied_to_the_requested_run(self):
        for operation in ["prepare", "inspect"]:
            with self.subTest(operation=operation):
                metadata, lineage = self.exercise(operation=operation)
                self.assertEqual(operation, metadata["operation"])
                self.assertEqual(LINEAGE if operation == "prepare" else b"", lineage)

    def test_wrong_run_nonce_sha_or_failed_run_cannot_supply_a_result(self):
        for changes in [{"displayTitle": "Signing keys prepare another-request"}, {"headSha": "another-commit"},
                        {"conclusion": "failure"}, {"conclusion": "cancelled"}]:
            with self.subTest(changes=changes), self.assertRaises(RuntimeError):
                self.exercise(run_changes=changes)

    def test_ambiguous_runs_do_not_select_an_arbitrary_result(self):
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.exercise(duplicate=True)

    def test_downloaded_metadata_must_match_nonce_operation_and_commit(self):
        for changes in [{"nonce": "wrong"}, {"source_sha": "wrong"}, {"operation": "inspect"}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.exercise(metadata_changes=changes)


if __name__ == "__main__":
    unittest.main()
