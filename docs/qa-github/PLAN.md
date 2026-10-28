# Proposed GitHub-hosted actual-UI gate — NOT RUN

This is a new source-only proposal based on local `5126ccb`, not recovery of lost runtime evidence. No workflow dispatch, Gradle, emulator, ADB server, host configuration, or network probe was executed while writing it. Existing local session 83430 and its lock/lease remain outside this proposal; neither is queried or reused.

## Required approvals and blocking conditions

1. Parent and independent reviewer approve the exact source revision and network boundary.
2. A fresh normal `Native Android checks` run for **that same Git commit** must finish `success`. A manual job checks the GitHub run API; a successful older commit, diagnostic rerun, or elapsed time is insufficient.
3. Parent explicitly authorizes the optional `actual_ui=true` manual dispatch through the already-existing `android.yml` workflow path. No UI job runs on push/PR, and no third-party emulator action is used. A separate new workflow path on the test branch alone cannot receive dispatch until it exists on the default branch, so this proposal does not rely on that and does not change main.
4. Build production and instrumentation APKs sequentially before boot. These are a new same-source-commit build after source CI, not the source-CI job’s APK bytes; the new installed APK identity is recorded exactly. Evidence stays outside checkout and Python bytecode writes are disabled so build provenance stays clean. Verify source manifest, APK hashes, matching public signer fingerprints, and zero native DEX declaration overlap. No old signer is assumed.
5. Runtime preflight must positively demonstrate the Android default HTTPS stack and the production public image client's TCP connection reach the task reject-only proxy. A mere proxy argument/settings value or DNS failure is not enough. If any probe fails, **BLOCKED_NETWORK_BOUNDARY**, no MainActivity or app scenario runs.
6. Emulator launch, readiness, test status, evidence, and normal owned cleanup all need terminal receipts. No runner-OK shortcut; raw statuses require matching starts and a separate whole-runner qualifier requires exactly one final normal INSTRUMENTATION_CODE=-1 with no FAILED/crash/shortMsg/late-status marker. Restored historical parser bytes remain unchanged. A timeout, assumption, missing terminal, failed precondition, or forced cleanup cannot become PASS.

## Network scope (not a host sandbox)

Official documentation says the emulator `-http-proxy` redirects all guest TCP at a lower network layer, but **does not redirect UDP**. The task listener binds only host loopback, never opens an outbound socket, never resolves names, never tunnels CONNECT, never intercepts TLS, and always writes the same HTTP 502 then closes. Only bounded closed counters persist; no URL, path, headers, body, credentials, or arbitrary error messages.

The emulator also uses `-dns-server 127.0.0.1` (an emulator-only launch option). A read-only check must show no host listener that covers the chosen 127.0.0.1 port53 before boot (wildcard, exact address, or IPv4-mapped binding; an unrelated 127.0.0.53 resolver is only counted, not altered); no privileged bind, DNS daemon, system resolver change, firewall, sudo, udev, KVM permission, or network namespace is introduced. This stops the normal guest DNS path from consulting real resolvers. It does not prove general UDP egress isolation. The accepted scope, if independently approved, is this app's reviewed HTTPS/TCP stacks and synthetic fixtures, not every possible emulator/host network protocol.

Source audit at the proposal baseline: no DatagramSocket/Cronet transport in production. API and Coil use OkHttp. ArticlePublicDns delegates to the system resolver and rejects nonpublic addresses; ArticleProxySelector rejects configured non-DIRECT Java proxies, but DIRECT Java routing is still beneath the emulator's TCP redirect. The lower-level emulator proxy is not a Java proxy override. No production DNS/proxy/UA/header/TLS implementation is changed.

The default HTTPS probe uses a literal TEST-NET address (no DNS), then requires the proxy's matching **closed probe counter** to increase. An HTTPS client may receive an I/O failure rather than the proxy's 502 as an application response: do not claim the HTTPS stack observed status 502. The listener's fixed response is separately checked over host loopback. No restricted CDN is contacted or retried. If the reviewer requires complete UDP isolation, this nonprivileged design is insufficient and must remain blocked rather than add a privileged workaround.

## Execution outline and budget

- Official `ubuntu-24.04`, existing pinned checkout/setup-java/upload-artifact action SHAs, JDK 17.
- Official SDK manager: API26 `default;x86_64` revision 1, emulator 37.2.12 (version mismatch blocks pending review), compile/build-tools36. Record package metadata and hashes. Do not update an existing user's AVD.
- Unique task HOME/ANDROID_USER_HOME/AVD/ADB server port/private runtime directory. New ephemeral debug signer; only public certificate digest may be archived.
- Software CPU (`-accel off`, 2 cores), SwiftShader, 1536 MiB, 720×1280/320 dpi, no audio/window/snapshot. No KVM setup or host security changes.
- 90-minute workflow ceiling: setup/build ≤25 minutes, boot ≤8, scenario budget ≤35 (previous software run was roughly tens of minutes), cleanup ≤5 plus reserve. The first failing scenario is preserved and stops the tour; all remaining cases stay NOT_RUN, not a 35-minute cascade. Minimum preflight 6 GiB available memory and 12 GiB free disk; fail rather than overcommit. No Gradle process overlaps the emulator.
- Existing owned-process supervisor gets a **new job-local** lock/lease. It owns only this job's subtree. Never inspect or signal local session 83430 or any existing lease.
- OS drawer→Home original screenshots and foreground/window checkpoints precede install. Install exact production APK and exact test APK, separately pull both installed packages and byte-compare/hash. Re-pull both at end. Per-case required original PNG/trace/ledger names and Copy/GIF receipts are checked by evidence_contract.py; partial pulls do not qualify.
- Order: fixed formatted97/raw117 Copy; six measurement controls; GIF; remaining app cases; three narrow repeats. The plan remains 15 unique /18 app invocations +6 measurement controls +2 separately classified network-boundary preflights. Personal-follow is newly added actual-Android evidence; its same15s initial readiness bound does not repair or erase the CI62 timeout. Back may refetch its first page.
- Logical narrow size640×1200 is a disposable AVD display override, not native physical dimensions or OS font setting. Preserve and restore prior settings; effective2.4× remains the existing LocalDensity fixture.
- Restore task settings, guest sync, normal emulator quit, owned ADB/proxy shutdown, exact final installed hash, no remaining owned descendants. If a forced stop is needed, retain failure/cleanup status and do not declare the gate qualified.

## Evidence and independent gate

Save untouched compositor PNGs, targeted hierarchy/window receipts, synthetic request ledgers, raw AndroidJUnitRunner logs, parsed statuses, per-stage timing, source/APK/testAPK hashes, native DEX no-shadow receipt, public signer fingerprints, and cleanup receipts. Preserve failures and rejected originals. GIF still requires the independent two-frame fixture metadata, continuously qualified loaded origin +1500ms, ≥3 post-settle original frames, ≥2 image hashes, unchanged outside region, visibly changing artwork, and all six original measurement controls. Do not pump Compose frames during sampling.

Only explicitly allowlisted non-sensitive files may be copied to an upload staging directory. Never upload APK/AAB/DEX binaries, private keys, ADB keys, userdata, whole environment/logcat, real account data, or the user's uploaded image. Missing original captures are never reconstructed. The independent reviewer must open the actual new PNGs before any UI approval; CI success alone is not that approval.

Remote image-host recovery remains **NOT APPROVED / UNVERIFIED**. Synthetic production decoder GIF, local emoji art, closed errors/retry, and blocked anonymous Home are not live-CDN, live-account, provider, or physical-phone proof.

## Primary references

- [Official emulator proxy boundary](https://developer.android.com/studio/run/emulator-networking-proxy)
- [Official emulator command-line options](https://developer.android.com/studio/run/emulator-commandline) (`-http-proxy`, `-dns-server`, `-accel`, `-gpu`)
- [Official Ubuntu24.04 runner image inventory](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)

The exact workflow/scripts remain subject to source review and explicit dispatch authorization. This proposal itself confers neither.
