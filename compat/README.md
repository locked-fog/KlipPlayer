# KLIP v1.1.1 compatibility corpus

The files in `v1.1.1/` are the exact stdout of `compile` from the tagged
KLIPlayer v1.1.1 runnable JAR. Build the preserved application in
`legacy/kotlin/`, then run each command from the repository root:

```sh
java -jar legacy/kotlin/build/libs/KLIPlayer-1.1.1.jar compile examples/demo.klip
java -jar legacy/kotlin/build/libs/KLIPlayer-1.1.1.jar compile examples/lua-addon.klip
java -jar legacy/kotlin/build/libs/KLIPlayer-1.1.1.jar compile examples/netsu-ijou.klip
```

The fixtures include source line numbers, same-time event order, z, cursor,
protection, and operation descriptions. A replacement compiler must match
them unless a documented compatibility correction is approved. In particular,
`netsu-ijou.klip` expands to 1,504 events from 0 through 237,844 ms.

`*.fast-play.ansi` contains the exact stdout of `play --start-at` beyond the
last event (00:20.000 for `demo`, 00:03.000 for `lua-addon`, and 04:00.000 for
`netsu-ijou`). This exercises terminal output and state restoration without
waiting for the song. It is a byte-level compatibility check, not a visual or
timing acceptance test.

The compiler and ANSI fixtures do not prove playback synchronization. The
repository does not ship `netsu-ijou.mp3`; synchronization requires an
authorized copy of the audio and actual device tests. Terminal appearance also
needs intermediate-frame inspection in a real terminal.
