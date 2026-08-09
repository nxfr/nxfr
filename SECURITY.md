# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| v0.1    | :white_check_mark: |

*Version 0.1 is the current development version.*

## Reporting Vulnerabilities

If you discover a security vulnerability within NXFR, please do not disclose it publicly. Instead, please report it via one of the following methods:

1. **GitHub Private Vulnerability Reporting** (Preferred)
2. **Email:** `security@nxfr.org`

**Do NOT open public issues for security bugs.**

## Process

- **Acknowledgment:** We will acknowledge receipt of your vulnerability report within 48 hours.
- **Timeline:** The timeline for fixing the issue will depend on the severity of the vulnerability.
- **Coordinated Disclosure:** We practice coordinated disclosure. We will work with you to establish a timeline for public disclosure that ensures users have time to apply a patch.

## Scope

The following types of vulnerabilities are considered **in scope**:

- Protocol implementation bugs
- Cryptographic issues (e.g., flaws in SAS derivation, TLS setup)
- Consent bypasses (e.g., forcing a file transfer without user approval)
- Path traversal vulnerabilities
- Information leaks

The following types of vulnerabilities are considered **out of scope**:

- Bugs in upstream dependencies (please report these to the respective upstream maintainers)
- Social engineering attacks
- Physical attacks (e.g., attacker has physical access to the unlocked device)

## Note on Protocol Threat Model

The NXFR protocol has a formally documented threat model in [`docs/SECURITY.md`](docs/SECURITY.md) which covers:
- TLS 1.3 mutual authentication constraints
- SAS (Short Authentication String) pairing mechanisms
- Identity pinning and TOFU (Trust On First Use) semantics
- Adversarial path rejection strategies

Please review this document before submitting architectural security reports.
