"""Release-gate tests with no GitHub writes or real credentials."""
import importlib.util
import io
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("prepare_release", Path(__file__).with_name("prepare-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseGateTest(unittest.TestCase):
    def run_gate(self, releases=None, private=False, ref="refs/heads/master", tag_commit=None, name="1.10.0", code=12):
        output = io.StringIO()
        # Keep the captured output open after the script's context manager.
        class Output:
            def __enter__(self): return output
            def __exit__(self, *args): pass
        with patch.dict(release.os.environ, {"GITHUB_REF": ref, "GITHUB_REPOSITORY": "owner/repo", "GITHUB_SHA": "abc", "GITHUB_OUTPUT": "unused"}), \
                patch.object(release, "gh_json", side_effect=[{"private": private}, [releases or []]]), \
                patch.object(release.Path, "read_text", return_value=f"versionCode={code}\nversionName={name}\n"), \
                patch.object(release.subprocess, "run", return_value=subprocess.CompletedProcess([], 0 if tag_commit else 1, tag_commit or "")), \
                patch("builtins.open", return_value=Output()):
            release.main()
        return output.getvalue()

    def previous(self, tag="v1.9.0", code=11, draft=False):
        return {"tag_name": tag, "draft": draft, "prerelease": False, "assets": [{"name": f"fileaccess-{code}.apk"}]}

    def test_first_release_and_numeric_semver_increase(self):
        self.assertIn("tag=v1.10.0", self.run_gate())
        self.assertIn("code=12", self.run_gate([self.previous()]))

    def test_private_repository_or_wrong_branch_cannot_publish(self):
        with self.assertRaises(ValueError): self.run_gate(private=True)
        with self.assertRaises(ValueError): self.run_gate(ref="refs/heads/staging")

    def test_published_versions_are_immutable(self):
        with self.assertRaises(ValueError): self.run_gate([self.previous(tag="v1.10.0")])

    def test_both_semver_and_android_code_must_increase(self):
        with self.assertRaises(ValueError): self.run_gate([self.previous(tag="v2.0.0")])
        with self.assertRaises(ValueError): self.run_gate([self.previous(code=12)])

    def test_draft_can_only_retry_on_original_commit(self):
        self.assertIn("exists=true", self.run_gate([self.previous(tag="v1.10.0", draft=True)], tag_commit="abc"))
        with self.assertRaises(ValueError): self.run_gate([self.previous(tag="v1.10.0", draft=True)], tag_commit="different")

    def test_invalid_version_is_rejected(self):
        for name in ["01.0.0", "1.0", "1.0.0-rc.1", "1.0.0+build"]:
            with self.assertRaises(ValueError): self.run_gate(name=name)


if __name__ == "__main__":
    unittest.main()
