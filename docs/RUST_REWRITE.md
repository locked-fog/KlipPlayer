# Rust rewrite status

The `rust/` crate is the in-progress successor to KLIPlayer v1.1.1. The
Kotlin/JVM application at the repository root remains the stable release.
Development happens on `codex/rust-rewrite`; no release or repository rename
has happened.

## Name and source layout

The proposed product name is **KlipPlayer**. `KLIP` remains the script format
name and `.klip` remains the file extension. The provisional package name is
`klip-player` and the command is `klip`; check public namespace availability
before publishing. Once the successor is accepted, move the Kotlin Gradle
project intact to `legacy/kotlin/`, move the Rust crate to the repository root,
and then rename the GitHub repository. Keep the v1.1.1 tag and compatibility
fixtures available throughout the transition.

## Current implementation

- KLIP parser, time resolver, cue/emit/loop expansion, and sorted event table.
- Compile-time Lua addons through a restricted Lua 5.2 `mlua` environment,
  matching the LuaJ generation used by the Kotlin version; Lua never runs
  during playback.
- ANSI terminal renderer with logical cursors, style isolation, synchronized
  updates, CJK display width, and the v1.1.1 protection mask semantics.
- Streamed audio decoding and output through rodio, with monotonic no-audio
  fallback. The timeline continues on a monotonic clock if the audio ends
  before the last script event.
- `check`, `compile`, and `play --start-at` CLI commands.

## Local checks

Run from the repository root:

```sh
cargo fmt --manifest-path rust/Cargo.toml --check
cargo clippy --manifest-path rust/Cargo.toml --all-targets --locked -- -D warnings
cargo test --manifest-path rust/Cargo.toml --locked
cargo run --manifest-path rust/Cargo.toml -- check examples/netsu-ijou.klip
cargo run --manifest-path rust/Cargo.toml -- play --start-at 04:00.000 examples/netsu-ijou.klip
```

The compatibility tests compare all compiled events and fast-play terminal
bytes against the tagged Kotlin v1.1.1 output in `compat/v1.1.1/`. The largest
script, `netsu-ijou.klip`, compiles to 1,504 events and ends at 237,844 ms.
The audio device test uses a short silent WAV and is explicit because CI
runners do not reliably have output devices:

```sh
cargo test --manifest-path rust/Cargo.toml --test audio_device -- --ignored
```

On the local Linux machine, the explicit audio test passed. A manual smoke
check with temporary WAV, MP3, and FLAC files also passed seeking at 100 ms,
rendering subsequent events, and playback without entering no-audio fallback.
These checks do not measure synchronization latency or establish behavior on
other operating systems.

## Release acceptance still needed

- Run the matrix on Linux, macOS, and Windows. Committed workflow configuration
  does not itself provide results from those machines.
- Inspect representative intermediate frames in a real terminal, including
  lyrics/protected effects and `--start-at` reconstruction.
- Test real MP3/FLAC playback, seeking, device failures, terminal restoration,
  and audio-to-text latency on each target system.
- Test the complete performance with an authorized copy of
  `examples/netsu-ijou.mp3`. The repository does not contain that recording.
- Audit third-party licenses and produce distributable packages before the
  Rust version becomes the default release.
