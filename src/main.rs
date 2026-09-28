mod audio;
mod cli;
mod compiler;
mod diagnostics;
mod lua;
mod model;
mod parser;
mod renderer;

use std::env;
use std::fs;
use std::io::{IsTerminal, Write};
use std::path::Path;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use audio::AudioClock;
use cli::{Action, parse_args, usage};
use compiler::compile;
use diagnostics::SyncReport;
use model::{Event, Op, Timeline};
use parser::parse_document;
use renderer::TerminalRenderer;

fn check(timeline: &Timeline) {
    let doc = &timeline.document;
    println!("file={}", doc.file);
    println!("music={}", doc.meta.music().unwrap_or("<none>"));
    println!("width={}", doc.meta.width());
    println!("height={}", doc.meta.height());
    println!("anchors={}", doc.anchors.len());
    println!("cues={}", doc.cues.len());
    println!("tracks={}", doc.tracks.len());
    println!("events={}", timeline.events.len());
    println!("range={}..{}ms", timeline.start_ms(), timeline.end_ms());
    if doc.meta.music().is_none() {
        println!("warning: music meta is missing; play will use monotonic no-audio mode");
    }
}

fn run(args: &[String]) -> i32 {
    let Some(options) = parse_args(args) else {
        usage();
        return 2;
    };
    let outcome = (|| -> std::result::Result<(), Box<dyn std::error::Error>> {
        let text = fs::read_to_string(&options.file)?;
        let doc = parse_document(&options.file, &text)?;
        let timeline = compile(doc)?;
        match options.action {
            Action::Check => check(&timeline),
            Action::Compile => {
                for event in &timeline.events {
                    println!("{}", event.describe());
                }
            }
            Action::Inspect { at_ms, window_ms } => {
                diagnostics::inspect(&timeline, at_ms, window_ms)
            }
            Action::Render { at_ms } => render_at(&timeline, at_ms)?,
            Action::Play { start_ms, report } => play(&timeline, start_ms, report.as_deref())?,
        }
        Ok(())
    })();
    match outcome {
        Ok(()) => 0,
        Err(error) => {
            if error.downcast_ref::<model::KlipError>().is_some() {
                eprintln!("{error}");
            } else {
                eprintln!("KLP9001 runtime: {error}");
            }
            1
        }
    }
}

fn warn_terminal_size(timeline: &Timeline) {
    let (width, height) = (
        timeline.document.meta.width(),
        timeline.document.meta.height(),
    );
    if std::io::stdout().is_terminal()
        && let Ok((columns, rows)) = crossterm::terminal::size()
        && (usize::from(columns) < width || usize::from(rows) < height)
    {
        eprintln!(
            "warning: terminal is {}x{}, script canvas is {}x{}",
            columns, rows, width, height
        );
    }
}

fn startup_clear() -> Event {
    Event {
        time_ms: 0,
        order: i64::MIN,
        cursor: "__startup__".into(),
        z: i32::MAX,
        protect: false,
        ops: vec![Op::Clear],
        line: 0,
        source: "runtime:startup".into(),
    }
}

fn render_prefix<W: Write>(
    renderer: &mut TerminalRenderer<W>,
    timeline: &Timeline,
    at_ms: i64,
    inclusive: bool,
) -> std::io::Result<usize> {
    let index = timeline
        .events
        .partition_point(|event| event.time_ms < at_ms || (inclusive && event.time_ms == at_ms));
    if index > 0 {
        for event in &timeline.events[..index] {
            renderer.render(event)?;
        }
        renderer.flush()?;
    }
    Ok(index)
}

fn render_at(
    timeline: &Timeline,
    at_ms: i64,
) -> std::result::Result<(), Box<dyn std::error::Error>> {
    warn_terminal_size(timeline);
    let stdout = std::io::stdout();
    let mut renderer = TerminalRenderer::new(
        timeline.document.meta.width(),
        timeline.document.meta.height(),
        stdout.lock(),
    )?;
    let rendering = (|| -> std::io::Result<()> {
        renderer.render(&startup_clear())?;
        renderer.flush()?;
        render_prefix(&mut renderer, timeline, at_ms, true)?;
        Ok(())
    })();
    let restore = renderer.restore();
    rendering?;
    restore?;
    Ok(())
}

fn play(
    timeline: &Timeline,
    start_ms: i64,
    report_path: Option<&Path>,
) -> std::result::Result<(), Box<dyn std::error::Error>> {
    warn_terminal_size(timeline);
    let mut report = report_path
        .map(|path| SyncReport::create(path, start_ms))
        .transpose()?;
    let stop = Arc::new(AtomicBool::new(false));
    let stop_signal = Arc::clone(&stop);
    ctrlc::set_handler(move || stop_signal.store(true, Ordering::SeqCst))?;
    let stdout = std::io::stdout();
    let mut renderer = TerminalRenderer::new(
        timeline.document.meta.width(),
        timeline.document.meta.height(),
        stdout.lock(),
    )?;
    let playback = (|| -> std::result::Result<(), Box<dyn std::error::Error>> {
        renderer.render(&startup_clear())?;
        renderer.flush()?;
        let mut index = render_prefix(&mut renderer, timeline, start_ms, false)?;
        if let Some(report) = &mut report {
            for event in &timeline.events[..index] {
                report.preload(event);
            }
        }
        if stop.load(Ordering::SeqCst) {
            return Ok(());
        }
        let mut audio = AudioClock::start(&timeline.document, timeline.end_ms(), start_ms);
        if let Some(report) = &mut report {
            report.set_mode(audio.mode_name());
        }
        if let Some(warning) = audio.warning() {
            eprintln!("{warning}");
        }
        while (index < timeline.events.len() || !audio.is_finished())
            && !stop.load(Ordering::SeqCst)
        {
            let now = audio.current_ms();
            let first = index;
            while index < timeline.events.len() && timeline.events[index].time_ms <= now {
                renderer.render(&timeline.events[index])?;
                index += 1;
            }
            if index > first {
                renderer.flush()?;
                if let Some(report) = &mut report {
                    report.timed(&timeline.events[first..index], audio.current_ms());
                }
            }
            if index >= timeline.events.len() && audio.is_finished() {
                break;
            }
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    })();
    let restore = renderer.restore();
    let save_report = report.map(SyncReport::finish).transpose();
    playback?;
    restore?;
    save_report?;
    Ok(())
}

fn main() -> ExitCode {
    ExitCode::from(run(&env::args().skip(1).collect::<Vec<_>>()) as u8)
}
