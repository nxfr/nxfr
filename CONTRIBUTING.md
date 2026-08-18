# Contributing to NXFR

This guide outlines our development process, standards, and expectations to ensure a smooth collaboration.

## Dev Environment

### Prerequisites
- Stable Rust toolchain (via [rustup](https://rustup.rs/)).
- `cargo` (comes with rustup) and `cargo-ndk`.
- Android SDK & NDK (26.1+).
- JDK 17+.

### Setup
1. Clone the repository: `git clone https://github.com/nxfr/nxfr.git`
2. Change into the project directory: `cd nxfr`
3. Build the entire workspace: `cargo build --workspace`
4. Run all tests to ensure your environment is working: `cargo test --workspace`

You can also run tests for individual crates:
```bash
cargo test -p nxfr-core      # 107 tests — protocol state machines, codec, framing
cargo test -p nxfr-web        # 13 tests — web server, chunked streaming, timeouts
cargo test -p nxfr-ffi        # 44 tests — FFI bridge, JNI safety, transfer lifecycle
cargo test -p nxfr-storage    # 19 tests — history, paired device DB
cargo test -p nxfr-crypto     # 8 tests — key generation, SAS derivation
cargo test -p nxfr-transport  # 7 tests — framing codec, TLS config
```
5. For Android: `cd apps/android && ./gradlew assembleDebug`

## Code Style

We maintain a strict code style and quality standard. Before submitting a PR, you must run:

- `cargo fmt --all` to format the codebase.
- `cargo clippy --workspace -- -D warnings` to catch common mistakes.

**We have a Zero Warnings policy.** Your PR will be rejected if Clippy generates any warnings.

## Testing

The project has an extensive test suite covering unit, integration, and E2E tests.
- All new features and bug fixes **must** include tests.
- Fuzz targets are available in the repository. Please run them if you are modifying core parsing or cryptographic code.

## Pull Request Process

1. **Fork** the repository and create your branch from `main`. Use descriptive branch names (e.g., `feat/add-windows-discovery`, `fix/tls-handshake-timeout`).
2. **Commit** your changes following our Commit Convention (see below).
3. **Push** your branch to your fork.
4. **Open a PR** against the `main` branch.
5. Your PR must pass all CI checks (tests, format, clippy).
6. At least **one maintainer review** is required before merging.

## Commit Convention

We require the use of [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) for all commit messages.

Examples:
- `feat(transport): add chunk-level resume`
- `fix(discovery): swallow mdns unregister errors`
- `docs: update protocol spec`
- `chore(ci): add clippy cache`

## What Makes a Good PR?

- **Focused Scope:** Solves one specific problem or adds one feature. Do not bundle unrelated changes.
- **Tested:** Includes new tests and passes existing ones.
- **Documented:** Updates `README.md` or other docs if behavior has changed.

## Spec Changes

The NXFR protocol is strictly specified. Any changes to `docs/PROTOCOL.md` or `docs/WIRE_FORMAT.md` require an RFC discussion in an issue **before** you start coding or open a PR. We value protocol stability and cross-platform compatibility.
