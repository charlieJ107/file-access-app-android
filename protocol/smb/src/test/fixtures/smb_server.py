"""Isolated loopback-only SMB2 fixture. Never serves a user directory or binds the LAN.

Requires the test-only dependency impacket==0.13.1 in a local virtual environment.
The JUnit fixture supplies a newly created temporary directory and one-use credentials.
"""
import argparse
import shutil
import sys
import tempfile
import threading
from pathlib import Path
from impacket import smbserver
from impacket.ntlm import compute_lmhash, compute_nthash

parser = argparse.ArgumentParser()
parser.add_argument("--root")
parser.add_argument("--port", required=True, type=int)
parser.add_argument("--password", required=True)
args = parser.parse_args()
owned_root = args.root is None
root = Path(args.root or tempfile.mkdtemp(prefix="fileaccess-smb-test-")).resolve(strict=True)
if not root.is_dir() or not root.name.startswith("fileaccess-smb-test-"):
    raise SystemExit("The fixture only accepts its dedicated temporary test directory")
if not 1024 <= args.port <= 65535:
    raise SystemExit("The fixture requires an unprivileged port")

server = smbserver.SimpleSMBServer(listenAddress="127.0.0.1", listenPort=args.port)
server.setSMB2Support(True)
server.addShare("test", str(root), "FileAccess isolated contract tests")
server.addCredential("fileaccess-test", 0, compute_lmhash(args.password), compute_nthash(args.password))
print("READY", flush=True)
thread = threading.Thread(target=server.start, daemon=True)
thread.start()
try:
    # The parent closes stdin after tests, including on WSL. This prevents an orphan listener.
    sys.stdin.read()
finally:
    server.getServer().shutdown()
    server.stop()
    if owned_root and root.parent == Path(tempfile.gettempdir()).resolve() and root.name.startswith("fileaccess-smb-test-"):
        shutil.rmtree(root)
