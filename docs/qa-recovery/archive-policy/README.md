# Proposed new evidence archive (local only; no runtime artifacts yet)

Status: NOT_RUN. The old95 original PNG files and their raw XML/logs are missing.
No replacement PNG, XML, PASS receipt, signing certificate or result is invented.
This proposal describes how to preserve **new** source-bound evidence after the
next authorized freeze/build/run. It does not authorize publication or Library
upload. Root must review an explicit local file list and separately decide whether
any approved subset may be committed to the test branch.

## Separate stores

- `private-runtime/`: original unfiltered execution workspace, APKs and toolchain
  outputs. Never place this directory or its descendants in the public archive
  automatically. Private keys/ADB keys/account state are never collected.
- `review-candidate/`: explicit copies of reviewed non-sensitive artifacts only,
  with relative paths, hashes, source/target/test APK hash labels and new signer
  fingerprint. No APK bytes, keystore, adbkey, userdata, databases, DataStore,
  emulator image, user attachment, environment dump or whole workspace archive.
- `approved-test-evidence/`: only after root's explicit publication decision. A
  safe local candidate is not itself approval to push or upload.

## Allowlisted categories (each file still needs content review)

1. Untouched full compositor PNGs of clearly synthetic test pages or task OS
   readiness. No user screenshot, real account content or real feed narrative.
2. Filtered AndroidJUnitRunner transcript for the expected synthetic classes;
   recorded starts/terminal statuses and primary failure text. Review for secrets
   before including. No blanket logcat, system environment or shell history dump.
3. Explicit task accessibility/window checkpoint limited to the expected window;
   preserve the private raw original if a safe separately labeled derivative is
   needed. A derivative is never presented as the original screenshot/evidence.
4. Request ledger from the strict synthetic transport with safe fixture URLs,
   method/status and denied count; never headers, cookies, request bodies or IDs
   belonging to real accounts. Observed source DISK/NETWORK remains literal.
5. Hash-only source/APK/testAPK/signer and DEX-overlap receipt; no binary APK/key.
   Runtime-source manifest includes only relative repository runtime/build paths.
6. Scenario/control JSON (new14 unique/17 invocations +6 controls only if actually
   executed), capture trace, timing/ROI coordinates, artifact provenance, cleanup
   and independent review verdict. Any failed/incomplete run is retained as such.

## Candidate manifest contract

Each entry needs relative path, byte count, SHA256, category, synthetic-data scope,
reviewer decision and source/test-run binding. The root object records:
- new production commit/tree/runtime manifest hash;
- new exact production and test APK hashes/sizes/new signer fingerprints;
- actual fresh installed pull hashes and DEX shadowing result;
- run IDs, physical/logical dimensions, effective font scale, Android image;
- raw status accounting (start present; PASS/FAIL/ERROR/ASSUMPTION/INCOMPLETE);
- original versus derivative classification; no derivative promoted to raw;
- historical evidence missing, remoteCDN NOT_APPROVED, actual phone NOT_TESTED;
- task-only cleanup outcome, including uncertainty rather than fabricated success;
- root's publication authorization, initially `null`.

No screenshot can be qualified solely by its filename or semantics. The reviewer
must reopen originals; GIF artwork must visibly change, not merely its broad ROI
hash. An accepted old source label does not qualify reconstructed source/APKs.
