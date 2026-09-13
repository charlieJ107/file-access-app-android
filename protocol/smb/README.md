# SMB adapter

`SmbStorageProvider` implements the shared storage, upload and mutation contracts using SMBJ 0.15.0. It has no Android UI or credential-store dependency. The application may inject a socket factory per connection to select a local Wi-Fi network or respect a VPN without binding the entire process.

The client offers SMB 2.0.2 through 3.1.1, requires authenticated signing, disables SMB1 negotiation and DFS, and rejects guest fallback. Selecting required encryption offers only SMB3 and checks the established session before returning. The public transport status distinguishes signing from encryption. This is transport protection, not end-to-end encryption.

Paths are confined to the configured share/root, with separate connection identifiers, no traversal or alternate streams, no URL decoding, and bounded names/depth. The adapter uses SMBJ's public local path resolver instead of its implicit symlink resolver, requests no-follow handles and holds verified ancestor handles during descendant operations. A server-side filesystem jail and correct server ACLs remain the server's responsibility.

Reads support a 64-bit offset and keep a handle that denies concurrent SMB writes/deletes. Revision strings are optimistic size/write-time/create-time metadata, **not cryptographic versions**; files with unchanged metadata cannot always be distinguished before opening. Directory iteration emits a non-transactional listing; any failure means the emitted listing is incomplete. Capability flags indicate implemented operations, not proven per-directory write permission.

Uploads create a unique temporary file and commit by handle rename with `replaceIfExists=false`. A checksummed hidden journal stores the operation token, phase, file identity, confirmed offset, prefix SHA-256, declared source length and hashed source version. Every 4 MiB, the writer flushes the payload, alternates a sequence-numbered receipt slot, flushes the receipt, then reports durable progress. Recovery verifies source metadata, payload identity/length, and full source/remote prefix digests; only an unconfirmed tail is truncated before writing at the saved 64-bit offset. A torn slot falls back to its intact predecessor. Legacy v1 receipts remain readable and migrate on write. Retrying a possibly committed operation compares original remote identity and full remote/local digests before returning success. Unproven ownership or completely corrupted journals return `OUTCOME_UNKNOWN` and retain data. The `onCommit` callback enforces the task lease before rename or a reconciled result.

Receipts (`.fileaccess-upload-*.receipt`, up to 2 KiB each) and `.part` files are hidden from application listings. Paused, interrupted and failed transfers retain them. `UploadCleanupCapability` aborts only an identified uncommitted payload or retires only a committed receipt, always retaining published targets. Cleanup holds the operation's exclusive receipt lock and refuses ambiguous ownership. The engine retries cancelled-task cleanup and retires receipts seven days after durable success, on unmetered Wi-Fi; terminal operation IDs remain in Room to prevent re-enqueueing retired operations. Cleanup failure is visible on the terminal task and retries after 30 minutes. Removal of a connection or changed configuration can prevent automatic cleanup; the old destination is never accessed with a new configuration. Explicit deletion of an empty directory still verifies and removes only committed bookkeeping.

Source versions are checked before and after transfer, and declared lengths must match. Metadata fingerprints supplied by SAF do not constitute an immutable byte snapshot; callers should use stable content when possible and report observable changes. Every completed payload is read back for full source/remote SHA-256 verification before READY/rename. Prefix checks, final verification and uncertain-result reconciliation persist their own cursor and SHA-256 states every 4 MiB, so slow verification can span automatic execution slices. Continuation is bound to unchanged source and remote metadata; a new reconciliation starts a full check. Unversioned sources restart verification. Memory remains bounded; source providers may override `open(offset)` for efficient positioning.

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

The fixture corrects Impacket 0.13.1's EOF setter with `os.ftruncate` (upstream writes a zero at EOF-1 instead of truncating). It also supports one-shot, test-directory-scoped quota/permission write faults. Enable the actual 3 GiB + 73 byte upload/retry/hash test with `FILEACCESS_SMB_LARGE_TEST=1`; it streams generated content with bounded memory and is intentionally opt-in. The regular wire suite includes actual abrupt child-JVM termination and reconnection, torn-journal recovery, tail truncation, changed prefixes and safe cleanup.

Primary SDK reference: [SMBJ](https://github.com/hierynomus/smbj), with the exact 0.15.0 source artifact checked against Maven Central.
