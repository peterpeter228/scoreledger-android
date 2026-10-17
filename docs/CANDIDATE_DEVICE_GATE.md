# Candidate device gate (T16 / T17)

Status: IMPLEMENTED_UNVERIFIED. No candidate Android execution has occurred in this workspace.

## Exact artifact execution

Use the signed artifact workflow from the same source SHA as both candidate APKs. Supply candidate app SHA-256, candidate app/test build run, prior app run 38991766567, prior test run 38992644559, and prior app SHA-256 d2bb25d9b192bc12853148fbf3b0ec208c4842b874f77a2e3b08e356844f1a92. Workflow does not rebuild, re-sign, inspect signing secrets, publish, or change remote permissions. KVM opt-in applies only to the disposable hosted runner.

Run separately on API26 / 30 / 36 using the SAME candidate APK/hash. Each run verifies certificate, package/version, downloaded bytes, source commit, test source commit, and acceptance commit. Old APK must differ and have a strictly smaller versionCode, with the same fixed certificate. Seed uses the compatible older instrumentation APK; install -r then candidate instrumentation verify compares the deterministic full business snapshot and exact persisted drafts. Same-APK reinstall is separately labelled and cannot satisfy upgrade.

## Fail-closed result

The committed tools/required_device_acceptance.json names every required class#method, not a minimum count. Only the allowlist runs in the full suite; opt-in upgrade seed/verify run separately. Each required method must start and finish once, with no assumptions, ignored/skipped tests, failures, duplicates, unexpected methods, or truncated final result. New required methods must be explicitly added to the manifest.

Mandatory files include all named UI screenshots, rendered native PDF and its first page, launch screenshot, upgrade and external process-death evidence. Missing files fail the success path. PNG/PDF structure and SHA-256 are checked; file existence is NOT visual or semantic correctness. Inspect screenshots/PDF independently and attach visual findings to the case mapping. Exit-trap collection is diagnostic only and cannot rescue a failed gate.

## Actual scope and limitations

- SyntheticJourneyTest: production UI with isolated Room and managers; real click/type/IME-action flow creates first child and three subjects without settings, zero/fullwidth/missing/absent states, explicit grade, edit identity, child isolation, next-score focus, last-subject missing, save detail, title filter and detail return. It does not measure parent speed or certify OEM IMEs.
- DraftRetryUiTest: SQLite trigger fault, visible actionable error after ten seconds, actual retry button, exact persisted payload and no committed exam.
- RealDocumentsUiTest: actual AOSP DocumentsUI and Downloads provider, external JSON export with readback status, cancelled picker, actual reselect/preview/replace and complete synthetic snapshot equality. Not a fake result callback, but does not certify OEM/cloud providers or uninstall/second-device transfer.
- PdfSaveRecreationTest and BackupSafRecreationTest: controlled activity-result dispatch and activity recreation only. They are NOT real SAF or process-death proof.
- External process death: shell launches then force-stops the actual target process, verifies PID absence, runs persisted snapshot/draft verification on restart. It does not claim editor navigation or mid-I/O atomicity. Those remain separate outstanding tests.
- No user studies, real transactions, store publication, physical/OEM claims, TalkBack certification, or two-reader/A4 printing claims.

## Business-case decision

The 70-case docs/CANDIDATE_ACCEPTANCE_MAPPING.json starts every candidate case NOT_RUN, preserving historical evidence only as references. Fill candidate binding from artifact-provenance.json and write specific candidate assertion/file links, result and remaining gaps after execution. A 40-method suite is not 40 or 70 business-case passes. The historical evidence/final-acceptance-mapping.json remains historical and must not be presented as v0.2 acceptance.

Local checks: python3 tools/test_acceptance_gate.py; bash -n tools/signed_emulator_acceptance.sh. These prove host parser behavior/syntax only, not Android compilation or runtime.

## Bounded automatic current round

The android-ci.yml current-round-device job runs the same signed-artifact gate after internal-apk succeeds, on API30/26/36 with fail-fast disabled. It has only contents:read and actions:read, receives no signing secrets, and downloads the current run's immutable artifacts. The expected candidate hash comes from build-generated signed metadata and is checked against downloaded bytes before the ordinary signature/hash gate.

ONE_ROUND_ENABLED is the final literal true in that job's if expression. Change it to false after final acceptance. This enables only the authorized ScoreLedger 0.2.0 correction/test round, not future general testing. A UTC expiry guard runs at job entry and again immediately before any KVM access grant; it rejects execution on or after 2026-10-18T00:00:00Z. Only trusted main push/manual build events are accepted. Existing manual acceptance workflow remains intact.

Because an enclosing workflow cannot finish before its device jobs, same-run automatic acceptance verifies GitHub's internal-apk job is completed/success and has the exact current run ID/head SHA, rather than incorrectly requiring that enclosing run already have concluded. External source runs still must be successful. Candidate app/test/acceptance commits must remain identical in both paths. The successful prerequisite job metadata is retained in artifact-provenance.json.
