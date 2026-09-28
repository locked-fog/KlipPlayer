# KLIP v1.1.1 compatibility corpus

The files in `v1.1.1/` are the exact stdout of `compile` from the tagged
KLIPlayer v1.1.1 runnable JAR. Run each command from the repository root:

```sh
java -jar build/libs/KLIPlayer-1.1.1.jar compile examples/demo.klip
java -jar build/libs/KLIPlayer-1.1.1.jar compile examples/lua-addon.klip
java -jar build/libs/KLIPlayer-1.1.1.jar compile examples/netsu-ijou.klip
```

The fixtures include source line numbers, same-time event order, z, cursor,
protection, and operation descriptions. A replacement compiler must match
them unless a documented compatibility correction is approved. In particular,
`netsu-ijou.klip` expands to 1,504 events from 0 through 237,844 ms.

These fixtures prove compilation behavior only. The repository does not ship
`netsu-ijou.mp3`; playback synchronization requires an authorized copy of the
audio and actual device tests. Terminal appearance also needs snapshots and
real terminal checks.
