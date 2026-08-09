# Fuzzing

## Status

The `fuzz/` directory contains `cargo-fuzz` / libfuzzer targets for:
- `fuzz_frame_parser` — FrameHeader parse + round-trip invariant
- `fuzz_cbor_decoder` — CBOR decode + encode round-trip invariant
- `fuzz_path_sanitizer` — arbitrary UTF-8 path sanitization

These require `cargo +nightly fuzz run` with a C++ compiler (for ASAN).
If unavailable, use the in-tree property tests in `nxfr-core` under
`#[cfg(test)] mod fuzz_property_tests` which exercise the same invariants
with randomized inputs (see below).

## Running cargo-fuzz targets

```bash
# Requires: nightly toolchain + C++ compiler (g++)
cargo +nightly fuzz run fuzz_frame_parser -- -max_total_time=60
cargo +nightly fuzz run fuzz_cbor_decoder -- -max_total_time=60
cargo +nightly fuzz run fuzz_path_sanitizer -- -max_total_time=60
```

## In-tree property tests (no nightly/C++ required)

```bash
cargo test -p nxfr-core -- fuzz_property
```

These run 100,000 random inputs per surface, checking the same
no-panic + round-trip invariants.
