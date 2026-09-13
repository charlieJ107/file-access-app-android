"""Verify uploaded APK bytes and GitHub digest before publishing the draft."""
import hashlib
import json
from pathlib import Path
import sys

metadata = json.loads(Path(sys.argv[1]).read_text())
apk = Path(sys.argv[2])
assets = [asset for asset in metadata["assets"] if asset["name"].endswith(".apk")]
assert len(assets) == 1, "Release must contain exactly one APK"
asset = assets[0]
assert asset["name"] == apk.name and asset["state"] == "uploaded"
assert asset["size"] == apk.stat().st_size <= 256 * 1024 * 1024
with apk.open("rb") as source:
    assert asset["digest"] == "sha256:" + hashlib.file_digest(source, "sha256").hexdigest()
