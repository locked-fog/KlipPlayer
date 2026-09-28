use std::fs;
use std::path::Path;
use std::process::Command;

fn repository() -> &'static Path {
    Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap()
}

#[test]
fn released_examples_compile_exactly_as_v1_1_1() {
    for stem in ["demo", "lua-addon", "netsu-ijou"] {
        let output = Command::new(env!("CARGO_BIN_EXE_klip"))
            .current_dir(repository())
            .args(["compile", &format!("examples/{stem}.klip")])
            .output()
            .unwrap();
        assert!(output.status.success(), "{stem}: {}", String::from_utf8_lossy(&output.stderr));
        let expected = fs::read(repository().join(format!("compat/v1.1.1/{stem}.compile.txt"))).unwrap();
        if output.stdout != expected {
            let actual = String::from_utf8_lossy(&output.stdout);
            let expected = String::from_utf8_lossy(&expected);
            let first = expected.lines().zip(actual.lines()).position(|(a, b)| a != b)
                .map(|index| index + 1).unwrap_or_else(|| expected.lines().count().min(actual.lines().count()) + 1);
            panic!("{stem}: first differing event line {first}\nexpected: {:?}\nactual: {:?}",
                expected.lines().nth(first - 1), actual.lines().nth(first - 1));
        }
    }
}

#[test]
fn completed_netsu_script_reports_expected_shape() {
    let output = Command::new(env!("CARGO_BIN_EXE_klip"))
        .current_dir(repository())
        .args(["check", "examples/netsu-ijou.klip"])
        .output().unwrap();
    assert!(output.status.success());
    let text = String::from_utf8(output.stdout).unwrap();
    for line in ["width=160", "height=40", "anchors=5", "cues=17", "tracks=2", "events=1504", "range=0..237844ms"] {
        assert!(text.lines().any(|candidate| candidate == line), "missing {line}");
    }
}

#[test]
fn errors_keep_codes_and_source_lines() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("bad.klip");
    fs::write(&path, "[track lyrics]\n[00:01.000]A\n[+1b]B\n[endtrack]\n").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_klip"))
        .args(["check", path.to_str().unwrap()]).output().unwrap();
    assert_eq!(output.status.code(), Some(1));
    let message = String::from_utf8(output.stderr).unwrap();
    assert!(message.contains("KLP5001"));
    assert!(message.contains("line 3"));
    assert!(message.contains("相对节拍缺少 BPM 上下文"));
}
