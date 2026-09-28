# KLIPlayer Workflow

## Branching

`main` is the stable branch. Development must happen on feature branches such as:

- `feat/initial-core`
- `feat/audio-renderer-core`
- `feat/parser-hardening`
- `docs/spec-sync`
- `feat/klip-parser`
- `feat/protection-mask`
- `fix/audio-sync`

Do not develop directly on `main`. Do not merge into `main` without explicit user approval.

## Required Checks

Before requesting merge approval:

```sh
git status
git diff main...HEAD
./gradlew cleanTest test
./gradlew build
```

If the repository does not yet have a `main` branch, report that explicitly and use the available baseline for review.

## Review

Before merge, an independent sub-agent must review the full diff. The review must check:

- No direct development on `main`.
- No unrelated file changes.
- No KTS, TUI, plugin system, dependency injection framework, runtime coroutine semantics, full virtual screen, image output, sixel, or kitty image protocol.
- KLIP syntax behavior follows `docs/KLIP_SPEC.md`.
- `track`, `cue`, `emit`, and `loop` are compile-time expanded.
- `ProtectionMask` blocks lower Z writes without storing a full screen.
- CJK width handling covers Han characters, Hiragana, Katakana, fullwidth punctuation, and combining marks.
- Tests and build pass.

The sub-agent reports findings to the user. Only after the user explicitly agrees may the branch be merged.

## Merge

Suggested merge command after approval:

```sh
git checkout main
git merge --no-ff <branch-name>
```

Never force push, rewrite history, delete branches, delete user files, or merge without approval.
