# KLIPlayer to KlipPlayer migration

The project is now named **KlipPlayer**. Rust is the default implementation at
the repository root, with Cargo package `klip-player` and executable `klip`.
The `.klip` format, example scripts, and Apache-2.0 license remain in place.
The Kotlin/JVM v1.1.1 project is preserved intact under `legacy/kotlin/`,
including its Gradle wrapper, source, tests, and historical TODO. Existing Git
tags remain available. The GitHub repository is renamed only after the source
transition has been committed and verified.

## Compatibility contract

- Parse and compile `track`, `cue`, `emit`, `loop`, absolute/relative/beat time,
  and compile-time Lua addons before playback.
- Preserve event time, same-time order, cursor, Z/protection, source line, and
  terminal output for released scripts.
- Resolve music and Lua addon paths relative to the `.klip` file.
- Preserve warning and monotonic no-audio behavior when audio is unavailable.

The corpus in `compat/v1.1.1/` holds exact output from the tagged Kotlin
release. Rust tests compare all compiled events and fast-play ANSI bytes for
`demo`, `lua-addon`, and `netsu-ijou`. The latter compiles to 1,504 events from
0 through 237,844 ms. Compatibility tests copy scripts into a temporary
directory before playback, so user-supplied audio never affects their timing.

## Architecture

The parser and compiler emit a sorted event table. Lua 5.2 addons run only
during compilation in a restricted `mlua` environment. The renderer uses
logical cursors, style isolation, synchronized ANSI output, CJK display width,
and the original protection mask semantics. Rodio streams decoded audio; when
audio ends before the final event, a monotonic clock completes the timeline.

## Verification

From the repository root:

```sh
cargo fmt --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked
cargo build --release --locked
cargo test --test audio_device --locked -- --ignored
./target/release/klip check examples/netsu-ijou.klip
```

For the preserved implementation:

```sh
cd legacy/kotlin
./gradlew cleanTest test --offline
./gradlew build --offline
```

Local Linux checks passed for the Rust and Kotlin builds, the explicit audio
device test, and manual seeking with temporary WAV, MP3, and FLAC files. The
user subsequently reported that testing with the real audio on their machine
passed. That report authorizes the migration; it is distinct from a captured
audio-to-text latency measurement. The GitHub CI matrix covers source checks
on Linux, macOS, and Windows after it runs.

## Release boundaries

Cross-platform CI results, distributable package generation, dependency
license review, and measured synchronization latency remain separate release
checks. Neither moving the source nor renaming the repository alone proves
those properties. Audio files supplied for testing stay outside Git.
