Reconstruction TLS fixture policy (2026-10-28)

No stored certificate or private-key payload is required or included here.
PublicMediaTlsFixture uses testImplementation okhttp-tls:4.12.0 to generate a fresh
one-day HeldCertificate in memory for each fixture. HandshakeCertificates supplies
only that local certificate's test trust; the normal hostname verifier remains.
The original SAN host set is unchanged: image.coolapk.com, avatar.coolapk.com,
static.coolapk.com, outside.test. An unrelated hostname must still fail.

MockWebServer terminates connections on loopback and never forwards upstream.
External DNS is refused. No CDN request or service-denial bypass is performed.
No certificate/private key is serialized to files or logs. Nothing changes any
production trust store, TLS verifier, account client, or production dependency.

The earlier tracked rebuilt-media.p12 upload was denied and not executed. It is
removed, not renamed, encoded, or retried. Runtime generation is new test source,
not recovered original bytes or evidence of a successful former upload.
