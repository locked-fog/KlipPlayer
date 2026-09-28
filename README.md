# KlipPlayer

KlipPlayer compiles and plays `.klip` terminal performances. Scripts expand
tracks, cues, loops, and compile-time Lua addons into one ordered event table;
playback renders ANSI text and effects alongside audio. The default
implementation is Rust. The original Kotlin/JVM KLIPlayer v1.1.1 remains in
[`legacy/kotlin/`](legacy/kotlin/) for reference and regression checks.

The KLIP format and existing scripts are unchanged. The completed
[`netsu-ijou.klip`](examples/netsu-ijou.klip) compiles to 1,504 events, and its
compiler and terminal output match saved v1.1.1 compatibility fixtures.

## Build and run

```sh
cargo build --release --locked
./target/release/klip check examples/netsu-ijou.klip
./target/release/klip compile examples/netsu-ijou.klip
./target/release/klip play examples/netsu-ijou.klip
./target/release/klip play --start-at 01:27.564 examples/netsu-ijou.klip
```

`play` looks up `[meta music="..."]` relative to the script file. For this
example, supply your own authorized audio as `examples/netsu-ijou.mp3` or use a
local symbolic link at that path. Local audio files are ignored by Git. If
music is missing or cannot start, `play` prints a warning and uses a monotonic
no-audio clock. A terminal at least 160 columns by 40 rows is recommended for
this example.

## Checks

```sh
cargo fmt --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked
cargo test --test audio_device --locked -- --ignored  # needs audio output
```

The ordinary test suite compares compiler and ANSI playback output against the
Kotlin v1.1.1 corpus in [`compat/`](compat/). The explicit audio test needs an
output device. Linux builds of the current audio backend need ALSA development
libraries; see the [CI workflow](.github/workflows/ci.yml).

The [KLIP specification](docs/KLIP_SPEC.md), [Lua addon guide](docs/LUA_ADDONS.md),
and [migration record](docs/MIGRATION.md) describe the format and transition.
The command is now `klip`; `kliplayer` belongs to the archived Kotlin version.

KlipPlayer is licensed under the Apache License 2.0. See [LICENSE](LICENSE).
