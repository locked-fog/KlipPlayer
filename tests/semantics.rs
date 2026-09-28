use std::fs;
use std::process::Command;

fn compile(script: &str, addon: Option<&str>) -> (i32, String, String) {
    let dir = tempfile::tempdir().unwrap();
    if let Some(addon) = addon {
        fs::write(dir.path().join("addon.lua"), addon).unwrap();
    }
    let path = dir.path().join("song.klip");
    fs::write(&path, script).unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_klip"))
        .args(["compile", path.to_str().unwrap()])
        .output()
        .unwrap();
    (
        output.status.code().unwrap_or(-1),
        String::from_utf8(output.stdout).unwrap(),
        String::from_utf8(output.stderr).unwrap(),
    )
}

#[test]
fn cue_loop_and_fractional_beats_are_expanded_before_playback() {
    let script = "[anchor intro 00:01.000 bpm=120]\n[cue blink cursor=fx z=20 protect=off]\n[loop 2]\n[+100]A\n[+200]B\n[endloop]\n[endcue]\n[track fx cursor=fx z=20 protect=off]\n[intro+1/2b][emit blink]\n[endtrack]\n";
    let (code, output, stderr) = compile(script, None);
    assert_eq!(code, 0, "{stderr}");
    let times: Vec<_> = output
        .lines()
        .map(|line| line.split_once("ms ").unwrap().0)
        .collect();
    assert_eq!(times, ["1350", "1550", "1650", "1850"]);
    assert!(output.lines().all(|line| line.contains("source=cue:blink")));
}

#[test]
fn syntax_and_compile_failures_keep_their_codes() {
    for (script, code, detail) in [
        ("[foo]\n", "KLP1001", "未知顶层标签"),
        (
            "[track x]\n[00:00.000][mv 0,1]x\n[endtrack]\n",
            "KLP1001",
            "mv row 和 col",
        ),
        (
            "[cue x]\n[+0][emit y]\n[endcue]\n",
            "KLP1001",
            "cue 内不允许使用 emit",
        ),
        (
            "[track x]\n[00:00.000][emit missing]\n[endtrack]\n",
            "KLP4001",
            "未定义 cue",
        ),
        (
            "[track x]\n[00:00.000][func missing]\n[endtrack]\n",
            "KLP6003",
            "未定义 function",
        ),
    ] {
        let (status, _, stderr) = compile(script, None);
        assert_eq!(status, 1, "{script}");
        assert!(
            stderr.contains(code) && stderr.contains(detail),
            "{script}: {stderr}"
        );
    }
}

#[test]
fn lua_addon_sees_only_the_compilation_api() {
    let addon = r#"return { functions = { sandbox = function(ctx)
      if os ~= nil or io ~= nil or package ~= nil or require ~= nil or luajava ~= nil then
        error('unsafe global is available')
      end
      return {{ offset = ctx.duration('delay', '80ms'), ops = {{ op = 'text', value = ctx.string('word') }} }}
    end } }"#;
    let script = "[meta addon=\"addon.lua\"]\n[track lyrics]\n[00:00.000][func sandbox word=ok]\n[endtrack]\n";
    let (code, output, stderr) = compile(script, Some(addon));
    assert_eq!(code, 0, "{stderr}");
    assert!(output.contains("80ms order=0"));
    assert!(output.contains(":: text ok"));
}

#[test]
fn invalid_lua_events_keep_the_addon_error_code() {
    let addon =
        "return { functions = { bad = function(ctx) return {{ ops = {{ op = 'nope' }} }} end } }";
    let script = "[meta addon=\"addon.lua\"]\n[track x]\n[00:00.000][func bad]\n[endtrack]\n";
    let (code, _, stderr) = compile(script, Some(addon));
    assert_eq!(code, 1);
    assert!(
        stderr.contains("KLP6004") && stderr.contains("未知 Lua op"),
        "{stderr}"
    );
}
