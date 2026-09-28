# KlipPlayer workflow

`main` is the default Rust implementation. Develop changes on a feature branch;
keep the Kotlin/JVM v1.1.1 project under `legacy/kotlin/` for compatibility
reference. Audio supplied by users is ignored by Git and must not be included
in commits or release archives.

Before merging a change to the KLIP parser, compiler, Lua API, renderer, or
audio clock, review the full diff and run:

```sh
git diff --check main...HEAD
cargo fmt --check
cargo clippy --all-targets --locked -- -D warnings
cargo test --locked
cargo build --release --locked
```

For changes that affect the legacy implementation, also run:

```sh
cd legacy/kotlin
./gradlew cleanTest test
./gradlew build
```

The GitHub CI workflow runs Rust checks on Linux, macOS, and Windows and a
legacy Kotlin build on Linux. Real audio output, terminal appearance, and
synchronization require target-machine checks; source tests cannot replace
those checks. Preserve `compat/v1.1.1/` as the released behavior baseline and
record any intentional difference before changing its fixtures.

Do not force-push, rewrite published history, delete user files, or merge
without the user's approval. The original KLIPlayer workflow is preserved in
`legacy/kotlin/WORKFLOW.md`.
