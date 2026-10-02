# Security policy

## Reporting

Please use GitHub's **Report a vulnerability** feature on this repository if it is available. If private reporting is unavailable, open an issue asking for a private reporting channel without disclosing the vulnerability. Do not include private documents, credentials or exploit details in a public issue.

Provide the affected SDK/sample version, Android version, impact and a reproducible synthetic example. Do not test against another person's documents or device.

## Scope and limitations

The SDK processes documents locally and adds no INTERNET permission. Host apps control storage, sharing, uploads and the final manifest; they need their own security review. Treat imported files and image metadata as untrusted, and preserve bounded processing and cancellation.

Completed exports belong to the host. Unexported sessions do not have a recovery journal; see [ownership and interruption](docs/ARCHITECTURE.md#ownership-and-interruption). Deleting a session is not a secure-erasure guarantee for flash storage or external copies.

See [quality evidence](docs/QUALITY-REPORT.md), [device acceptance](docs/DEVICE-ACCEPTANCE.md) and [third-party/model notices](THIRD-PARTY-NOTICES.md) for current limits. Development checks are not an independent security audit or certification.

I'm happy to help where I can, but I can't promise a response time or support period.
