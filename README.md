# KLIPlayer

KLIPlayer is a Kotlin/JVM rewrite of CLIPlayer. It reads `.klip` scripts, expands track/cue/emit/loop blocks into a compile-time timeline, and renders ANSI terminal performances with Z-axis protection.

An in-progress Rust successor lives in [`rust/`](rust/). It preserves the
current `.klip` format and is being checked against v1.1.1 compiler and
terminal-output fixtures. See [`docs/RUST_REWRITE.md`](docs/RUST_REWRITE.md)
for build commands, migration decisions, and remaining release acceptance.

Lua addons can generate events during compilation via `[meta addon=...]` and `[func ...]`; playback still runs only the flattened event table.

This project is intentionally small for the initial core:

- Kotlin/JVM, target JVM 21.
- Single Gradle module.
- Runnable JAR output.
- ANSI terminal renderer.
- Compile-time timeline expansion.
- `ProtectionMask` without a full virtual screen.
- CJK-aware display width.
- Structured parser/compiler errors with file and line information.
- Audio playback behind `AudioClock`, with explicit no-audio fallback warnings.

## Build

```sh
./gradlew cleanTest test
./gradlew build
```

The runnable JAR is produced under `build/libs/`.

```sh
java -jar build/libs/KLIPlayer-1.1.1.jar check examples/demo.klip
```

## CLI

```sh
./gradlew run --args="check examples/demo.klip"
./gradlew run --args="compile examples/demo.klip"
./gradlew run --args="play examples/demo.klip"
./gradlew run --args="play --start-at 00:30.000 examples/demo.klip"
```

Commands:

- `check <file.klip>` parses and compiles a script, then prints summary information.
- `compile <file.klip>` prints the expanded event table.
- `play <file.klip>` plays the expanded timeline through the ANSI renderer.
- `play --start-at MM:SS.mmm <file.klip>` fast-renders commands before the requested time, then starts audio and timed command playback from that position.

`examples/demo.klip` references `demo.mp3`, but this repository does not ship an audio file. `play examples/demo.klip` therefore prints a warning and uses a monotonic no-audio clock.

## Current Scope

Implemented in v1.1.1:

- `meta`, `anchor`, `track`, `cue`, `emit`, and cue-local `loop`.
- Absolute time, anchor time, relative time, decimal beats, and fraction beats.
- ANSI operations: move, foreground/background color, style, space, newline, cleanline, clear, hide/show cursor.
- Compile-time expansion of `track/cue/emit/loop` into a sorted event table.
- Compile-time Lua addons for event generation; no Lua or plugin runtime runs during playback.
- Z/protect behavior through `ProtectionMask`.
- CJK-aware display width for lyrics and mask placement.
- CLI commands: `check`, `compile`, `play`.

Not implemented in v1.1.1:

- KTS, variables, macro parameters, random values, conditions, runtime functions, runtime plugins, dependency injection, TUI, runtime coroutine semantics, full virtual screen, image output, sixel, kitty image protocol, networking.

Audio playback is encapsulated behind `AudioClock`. The implementation uses Java Sound with bundled MP3 and FLAC service providers, and falls back to a monotonic clock when music is missing, unsupported, or not configured.

## License

KLIPlayer is licensed under the Apache License 2.0. See `LICENSE`.
