"""Fail closed before signing; emit validated metadata for release.yml."""
import json
import os
from pathlib import Path
import re
import subprocess


def gh_json(*args):
    return json.loads(subprocess.check_output(["gh", *args], text=True))


def stable_version(value):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise ValueError("Release versionName must be stable SemVer MAJOR.MINOR.PATCH")
    return tuple(map(int, value.split(".")))


def main():
    if os.environ["GITHUB_REF"] != "refs/heads/master":
        raise ValueError("Only master may publish")
    repo = os.environ["GITHUB_REPOSITORY"]
    if gh_json("api", f"repos/{repo}")["private"]:
        raise ValueError("Make this repository public before publishing: app updates use anonymous access")
    props = dict(line.split("=", 1) for line in Path("version.properties").read_text().splitlines() if line and not line.startswith("#"))
    name = props["versionName"]
    version = stable_version(name)
    code = int(props["versionCode"])
    if not 0 < code <= 2_100_000_000:
        raise ValueError("Invalid Android versionCode")
    tag = f"v{name}"
    pages = gh_json("api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100")
    releases = [release for page in pages for release in page]
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing and not existing["draft"]:
        raise ValueError("This version is already published. Never replace published assets; increment the version.")
    for release in releases:
        if release["draft"] or release["prerelease"]:
            continue
        if version <= stable_version(release["tag_name"].removeprefix("v")):
            raise ValueError("Semantic version must increase beyond every published stable release")
        for asset in release["assets"]:
            match = re.fullmatch(r"fileaccess-([1-9][0-9]*)\.apk", asset["name"])
            if match and code <= int(match[1]):
                raise ValueError("Android versionCode must also increase for in-place installation")
    # A failed draft can be retried only from its original, tested commit.
    tag_commit = subprocess.run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], capture_output=True, text=True)
    if tag_commit.returncode == 0 and tag_commit.stdout.strip() != os.environ["GITHUB_SHA"]:
        raise ValueError("Release tag points to another commit; never move a release tag")
    if existing and tag_commit.returncode != 0 and existing.get("target_commitish") != os.environ["GITHUB_SHA"]:
        raise ValueError("Pending draft must target the exact original commit SHA; never replace another commit's draft assets")
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        output.write(f"tag={tag}\ncode={code}\nexists={str(existing is not None).lower()}\n")


if __name__ == "__main__":
    main()
