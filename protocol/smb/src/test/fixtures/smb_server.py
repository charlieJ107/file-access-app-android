"""Isolated loopback-only SMB2 fixture. Never serves a user directory or binds the LAN.

Requires the test-only dependency impacket==0.13.1 in a local virtual environment.
The JUnit fixture supplies a newly created temporary directory and one-use credentials.
"""
import argparse
import os
import shutil
import sys
import tempfile
import threading
from pathlib import Path
from impacket import smbserver
from impacket import smb, smb3structs as smb2
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

# Impacket 0.13.1's EOF setter seeks to n-1 and writes a zero: it neither shrinks
# files nor preserves the last byte. Implement real EOF semantics in this isolated
# fixture so resume tests exercise truncation instead of that known fixture defect.
original_set_info = smbserver.SMB2Commands.smb2SetInfo


def set_info(conn_id, smb_server, packet):
    info = smb2.SMB2SetInfo(packet["Data"])
    if info["InfoType"] == smb2.SMB2_0_INFO_FILE and info["FileInfoClass"] == smb2.SMB2_FILE_END_OF_FILE_INFO:
        connection = smb_server.getConnectionData(conn_id)
        file_id = info["FileID"].getData()
        opened = connection["OpenedFiles"].get(file_id)
        if opened is not None and packet["TreeID"] in connection["ConnectedShares"]:
            length = smb.SMBSetFileEndOfFileInfo(info["Buffer"])["EndOfFile"]
            os.ftruncate(opened["FileHandle"], length)
            return [smb2.SMB2SetInfo_Response()], None, smbserver.STATUS_SUCCESS
    return original_set_info(conn_id, smb_server, packet)


server.getServer().hookSmb2Command(smb2.SMB2_SET_INFO, set_info)

original_write = smbserver.SMB2Commands.smb2Write


def write_with_fault(conn_id, smb_server, packet):
    request = smb2.SMB2Write(packet["Data"])
    connection = smb_server.getConnectionData(conn_id)
    opened = connection["OpenedFiles"].get(request["FileID"].getData())
    if opened is not None and request["Offset"] >= 4 * 1024 * 1024:
        path = Path(opened["FileName"])
        if path.suffix == ".part":
            for fault, status in (("quota", 0xC000007F), ("permission", 0xC0000022)):
                control = path.parent / (".fixture-fault-" + fault)
                if control.is_file():
                    control.unlink()
                    return [smb2.SMB2Error()], None, status
    return original_write(conn_id, smb_server, packet)


server.getServer().hookSmb2Command(smb2.SMB2_WRITE, write_with_fault)
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
