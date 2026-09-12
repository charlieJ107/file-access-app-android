# SMB adapter

`SmbStorageProvider` implements the shared storage, upload and mutation contracts using SMBJ 0.15.0. It has no Android UI or credential-store dependency. The application may inject a socket factory per connection to select a local Wi-Fi network or respect a VPN without binding the entire process.

The client offers SMB 2.0.2 through 3.1.1, requires authenticated signing, disables SMB1 negotiation and DFS, and rejects guest fallback. Selecting required encryption offers only SMB3 and checks the established session before returning. The public transport status distinguishes signing from encryption. This is transport protection, not end-to-end encryption.

Paths are confined to the configured share/root, with separate connection identifiers, no traversal or alternate streams, no URL decoding, and bounded names/depth. The adapter uses SMBJ's public local path resolver instead of its implicit symlink resolver, requests no-follow handles and holds verified ancestor handles during descendant operations. A server-side filesystem jail and correct server ACLs remain the server's responsibility.

Reads support a 64-bit offset and keep a handle that denies concurrent SMB writes/deletes. Revision strings are optimistic size/write-time/create-time metadata, **not cryptographic versions**; files with unchanged metadata cannot always be distinguished before opening. Directory iteration emits a non-transactional listing; any failure means the emitted listing is incomplete. Capability flags indicate implemented operations, not proven per-directory write permission.

Uploads create a unique temporary file and commit by handle rename with `replaceIfExists=false`. A fixed-size, checksummed hidden receipt stores the operation token, phase, file identity, content digest and hashed source version. Retrying an identified unfinished operation restarts from zero. Retrying a possibly committed operation compares the original remote identity and full remote/local digests before returning success. Unproven ownership or corrupted receipts return `OUTCOME_UNKNOWN` and retain remote data. The `onCommit` callback runs before final rename or a reconciled successful result so the task engine can enforce its lease.

Receipts (`.fileaccess-upload-*.receipt`, 1 KiB each) are retained beside uploaded files for durable reconciliation and hidden from application listings. Interrupted `.part` files are retained for a safe retry. Explicit deletion of a logically empty directory verifies every remaining entry and removes only intact committed receipts before deleting the directory. Ordinary files, unfinished uploads, corrupted receipts and unknown entries produce a conflict and are retained. There is no automatic receipt-retention cleanup or resumable upload yet. A later retention capability can remove old receipts after the corresponding task history expires.

Source versions are checked before and after transfer, and declared lengths must match. Sources without a version are reopened and hashed before commit. Metadata fingerprints supplied by SAF do not constitute an immutable byte snapshot; callers should use stable content when possible and report observable changes. The normal upload validates SMB write acknowledgements and remote length; full remote SHA-256 is read back during uncertain-result reconciliation.

## Tests

Run the pure contract/security tests with:

```powershell
.\scripts\build.ps1 :protocol:smb:testDebugUnitTest
```

Actual wire-protocol tests require an isolated Python environment with `impacket==0.13.1`. Set `FILEACCESS_SMB_TEST_PYTHON` to that interpreter. On Windows use WSL/Linux because Impacket's Windows filesystem implementation cannot rename its open file handles. For this workspace's prepared WSL environment:

```powershell
$env:FILEACCESS_SMB_TEST_PYTHON = '/home/charlie/.cache/fileaccess-smb-test-venv/bin/python'
$env:FILEACCESS_SMB_TEST_WSL_DISTRO = 'Ubuntu'
.\scripts\build.ps1 :protocol:smb:testDebugUnitTest --rerun-tasks
```

The test fixture serves only a newly created temporary directory on `127.0.0.1` using a random high port and one-use credentials. Closing the parent input pipe shuts it down and removes its own test directory. When the environment variable is absent, integration tests explicitly skip; they are not counted as NAS validation. Impacket exercises actual signed SMB2 requests and failure of an encryption-required downgrade. It does not establish SMB3 encryption, implement every Windows share-lock/reparse behavior, or replace Android 16 physical-device/NAS validation.

Primary SDK reference: [SMBJ](https://github.com/hierynomus/smbj), with the exact 0.15.0 source artifact checked against Maven Central.
