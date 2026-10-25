# Account-only read verification increment

Scope: My personal lists (including follow tabs and collection contents) and `v6/notification/checkCount`. Fresh actual NativeApp BEFORE frames on frozen `0d3fd3b` showed a supported v2 CAPTCHA with only Retry on both routes (`native-account-risk-before-v2`). The fixtures use synthetic accounts and loopback HTTP, with external DNS denied. Test-only cookie callback origin mapping preserves the captured official identity while asserting the exact loopback server address; it is not a production trust-policy change.

Flutter source: `FFFListController.customGetData` and notification/checkCount helpers use global `Request.get` verification. `NetworkRepo.getProfile` explicitly sets `skipCaptcha:true`, so this increment leaves the profile repository unchanged. Notification/chat history, feed picker/image resolver, and generic object-detail continuation remain outside this increment; this is not a global parity claim.

- Personal-list factories bind the expected UID before dispatch. Guest, mismatched UID, changed account/generation/raw-cookie revision and stale continuations cannot authorize a read. Exact request kind, target, cursor and refresh/append mode are retained.
- New typed `personalRead`/`countsRead` capabilities use the existing human-operated host and isolated read session. No new login or social-action policy, automatic solving, acknowledgement request or arbitrary challenge URL is introduced.
- Verification completion and one explicit retry remain separate actions. Accepted blank-field validate/SESSID may serve later reads within the existing owner/device/revision/expiry-bound memory-only cache; never login/social writes, the vault or a WebView cookie jar.
- Personal route/tab/account/repository replacement and counts lifecycle pause/disposal invalidate pending capability ownership. Discarded late failures close their capabilities. Counts-only retry does not refresh profile; aggregate refresh cancels the old challenge instead of replaying it.

Production source, focused tests and screenshots are separate acceptance gates. ShadowWebView tests do not run the vendor SDK or establish real-service completion; the pre-existing browser-isolation runtime limitation remains.

The read proof-field gate also now follows Flutter underscore syntax (`^_[A-Za-z0-9_]{1,63}$`), rather than rejecting every field other than `_v2_post_token`. Original-key collisions and explicit protected request/identity/auth/device/session/signature/header aliases remain rejected after case/separator normalization. This bounded compatibility correction does not widen authenticated write replay policy.
