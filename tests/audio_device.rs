use std::fs;
use std::process::Command;
use std::time::{Duration, Instant};

fn silence_wav() -> Vec<u8> {
    let frames = 4_410u32; // 100 ms of mono PCM at 44.1 kHz.
    let data_size = frames * 2;
    let mut wav = Vec::with_capacity(44 + data_size as usize);
    wav.extend_from_slice(b"RIFF");
    wav.extend_from_slice(&(36 + data_size).to_le_bytes());
    wav.extend_from_slice(b"WAVEfmt ");
    wav.extend_from_slice(&16u32.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes());
    wav.extend_from_slice(&1u16.to_le_bytes());
    wav.extend_from_slice(&44_100u32.to_le_bytes());
    wav.extend_from_slice(&88_200u32.to_le_bytes());
    wav.extend_from_slice(&2u16.to_le_bytes());
    wav.extend_from_slice(&16u16.to_le_bytes());
    wav.extend_from_slice(b"data");
    wav.extend_from_slice(&data_size.to_le_bytes());
    wav.resize(44 + data_size as usize, 0);
    wav
}

#[test]
#[ignore = "requires a real audio output device; run explicitly on a target machine"]
fn audio_can_finish_before_the_last_script_event() {
    let dir = tempfile::tempdir().unwrap();
    fs::write(dir.path().join("silence.wav"), silence_wav()).unwrap();
    let script = dir.path().join("probe.klip");
    fs::write(
        &script,
        "[meta music=\"silence.wav\"]\n[track x]\n[00:00.000]A\n[00:00.150]B\n[endtrack]\n",
    )
    .unwrap();
    let report = dir.path().join("sync.tsv");
    let start = Instant::now();
    let output = Command::new(env!("CARGO_BIN_EXE_klip"))
        .args([
            "play",
            "--sync-report",
            report.to_str().unwrap(),
            script.to_str().unwrap(),
        ])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(!String::from_utf8_lossy(&output.stderr).contains("using monotonic no-audio clock"));
    assert!(output.stdout.contains(&b'A'));
    assert!(output.stdout.contains(&b'B'));
    assert!(start.elapsed() >= Duration::from_millis(100));
    assert!(start.elapsed() < Duration::from_secs(5));
    let report = fs::read_to_string(report).unwrap();
    assert!(report.contains("# clock=audio\n"));
    assert!(report.contains("# timed_events=2\n"));
}
