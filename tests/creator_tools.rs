use std::fs;
use std::path::Path;
use std::process::Command;

fn run(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_klip"))
        .args(args)
        .output()
        .unwrap()
}

fn script(dir: &Path) -> String {
    let path = dir.join("song.klip");
    fs::write(
        &path,
        "[meta width=20 height=5 music=\"absent.wav\"]\n[track words cursor=main z=10 protect=on]\n[00:00.000][mv 1,1]A\n[00:00.050][mv 1,2]B\n[00:00.100][mv 1,3]C\n[endtrack]\n",
    )
    .unwrap();
    path.to_str().unwrap().to_owned()
}

#[test]
fn inspect_selects_inclusive_window_and_keeps_source_lines() {
    let dir = tempfile::tempdir().unwrap();
    let file = script(dir.path());
    let result = run(&["inspect", "--at=00:00.050", "--window", "0ms", &file]);
    assert!(result.status.success(), "{:?}", result.stderr);
    let output = String::from_utf8(result.stdout).unwrap();
    assert!(output.contains("at=50ms window=0ms range=50..50ms"));
    assert!(output.contains("events_in_window=1 events_through_at=2 events_total=3"));
    assert!(output.contains("music=") && output.contains("exists=false"));
    assert!(output.contains("50ms order=1 z=10 cursor=main protect=true line=4"));
    assert!(!output.contains("0ms order=0 "));
    assert!(!output.contains("100ms order=2 "));
}

#[test]
fn render_includes_events_at_timestamp_without_opening_audio() {
    let dir = tempfile::tempdir().unwrap();
    let file = script(dir.path());
    let at_zero = run(&["render", "--at", "00:00.000", &file]);
    let at_fifty = run(&["render", "--at", "00:00.050", &file]);
    assert!(at_zero.status.success());
    assert!(at_fifty.status.success());
    assert!(at_zero.stdout.contains(&b'A'));
    assert!(!at_zero.stdout.contains(&b'B'));
    assert!(at_fifty.stdout.contains(&b'B'));
    assert!(!at_fifty.stdout.contains(&b'C'));
    assert!(at_fifty.stdout.ends_with(b"\x1b[?2026l"));
    assert!(at_fifty.stderr.is_empty(), "{:?}", at_fifty.stderr);
}

#[test]
fn render_after_last_event_preserves_legacy_fast_play_bytes() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR"));
    let result = Command::new(env!("CARGO_BIN_EXE_klip"))
        .current_dir(root)
        .args(["render", "--at", "00:20.000", "examples/demo.klip"])
        .output()
        .unwrap();
    assert!(result.status.success(), "{:?}", result.stderr);
    let expected = fs::read(root.join("compat/v1.1.1/demo.fast-play.ansi")).unwrap();
    assert_eq!(result.stdout, expected);
}

#[test]
fn sync_report_separates_preload_from_timed_dispatch() {
    let dir = tempfile::tempdir().unwrap();
    let file = script(dir.path());
    let report = dir.path().join("sync.tsv");
    let result = run(&[
        "play",
        "--start-at",
        "00:00.050",
        "--sync-report",
        report.to_str().unwrap(),
        &file,
    ]);
    assert!(result.status.success(), "{:?}", result.stderr);
    let output = fs::read_to_string(report).unwrap();
    assert!(output.contains("# clock=fallback\n"));
    assert!(output.contains("# start_at_ms=50\n"));
    assert!(output.contains("# timed_events=2\n"));
    assert!(output.contains("preload\t0\t\t\t3\ttrack:words"));
    assert!(output.contains("timed\t50\t"));
    assert!(output.contains("timed\t100\t"));
}

#[test]
fn sync_report_never_overwrites_an_existing_file() {
    let dir = tempfile::tempdir().unwrap();
    let file = script(dir.path());
    let report = dir.path().join("sync.tsv");
    fs::write(&report, "keep me").unwrap();
    let result = run(&["play", "--sync-report", report.to_str().unwrap(), &file]);
    assert_eq!(result.status.code(), Some(1));
    assert_eq!(fs::read_to_string(report).unwrap(), "keep me");
}

#[test]
fn creator_commands_reject_missing_or_invalid_times() {
    for args in [
        vec!["inspect", "examples/demo.klip"],
        vec!["render", "--at", "invalid", "examples/demo.klip"],
        vec![
            "inspect",
            "--at",
            "00:00.000",
            "--window",
            "-1s",
            "examples/demo.klip",
        ],
    ] {
        assert_eq!(run(&args).status.code(), Some(2), "{args:?}");
    }
}
