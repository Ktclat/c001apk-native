# My unread-count projection

This bounded increment follows Flutter `1020aff`'s `GET /v6/notification/checkCount` contract. It does not claim complete Flutter parity or successful live-service/device validation.

## Contract and scope

- The request has no query parameters or body. `atme`, `atcommentme`, `feedlike`, and `contacts_follow` map to the existing four notification rows. The existing private-message/information row uses `message + notification` only when both components are known and their sum fits a signed `Long`.
- Only nonnegative JSON integer literals within `Long` are accepted. Missing, null, string, boolean, fractional, exponent, negative, structured, or overflowing fields remain unknown independently. A non-object `data` value is invalid, not an empty successful result.
- The operation captures one authenticated session, checks the bound UID/account generation and strict cookie revision before and after the read, and rejects guests, recovery state, absent strict revision, and stale completion. It does not relax the existing profile scope or change shared transport behavior.
- Counts and the existing own profile load independently. Failure or retry of one never reloads or hides the other. Counts load once on My entry, again on each resumed lifecycle/return, or after an explicit count retry. Repeated retry taps are single-flight. Pause/disposal cancels the effect; a stale/uncooperative completion cannot publish. There is no polling or automatic retry.
- Counts are memory-only within that screen/account generation. No durable cache, saved-state snapshot, list-derived substitute, read acknowledgement, clearing, or local decrement is introduced.

## Presentation

The five existing row routes stay unchanged. Positive counts occupy a separate row-end badge slot. Native displays `1` through `99`, then `99+`, deliberately favoring narrow-window readability over Flutter's `999+` cap. Accessibility receives the exact count on the row's single click node, not a duplicate badge announcement. Known zero has no badge and is announced as no unread messages; unknown also has no badge but is announced as unknown. Loading/error/partial-unknown status and explicit retry are separate from profile status.

The dock defaults, Settings, About, login implementation, and notification/message destinations are unchanged. Badge reads do not mark messages read.

## Verification status

Synthetic decoder, scope, lifecycle, stale-result, independent retry, semantics, exact My routing, and native-rendered geometry tests are prepared. The reported portrait fixture is exactly 1200 × 2670 pixels at DPR 3.25, with separate 140-pixel status and 179-pixel top-gesture insets and zero bottom inset. Light/default text and dark/2× text are covered by the prepared tests. These are synthetic fixtures, not measured physical-device or TalkBack acceptance.

The initial source/test freeze has not yet run Gradle, lint, or rendering. Results must be recorded against the exact frozen source manifest before this increment is described as verified. Local exported-tree builds intentionally report Git metadata as unavailable rather than claiming the baseline integration commit as the new build's commit; the generated source fingerprint and external source manifest identify the candidate bytes. The parent integration workflow owns remote publication and final CI evidence.
