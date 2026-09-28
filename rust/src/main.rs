mod audio;
mod compiler;
mod lua;
mod model;
mod parser;
mod renderer;

use std::env;
use std::fs;
use std::io::IsTerminal;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;

use audio::AudioClock;
use compiler::compile;
use model::{Event, Op, Timeline};
use parser::{parse_absolute, parse_document};
use renderer::TerminalRenderer;

struct Options {
    command: String,
    file: String,
    start_ms: i64,
}

fn parse_args(args: &[String]) -> Option<Options> {
    let command = args.first()?.as_str();
    match command {
        "check" | "compile" if args.len() == 2 => Some(Options {
            command: command.into(),
            file: args[1].clone(),
            start_ms: 0,
        }),
        "play" => {
            let mut file = None;
            let mut start_ms = 0;
            let mut saw_start = false;
            let mut index = 1;
            while index < args.len() {
                if args[index] == "--start-at" || args[index].starts_with("--start-at=") {
                    if saw_start {
                        return None;
                    }
                    let value = if args[index] == "--start-at" {
                        index += 1;
                        args.get(index)?.as_str()
                    } else {
                        args[index].split_once('=')?.1
                    };
                    start_ms = parse_absolute(value)?;
                    saw_start = true;
                } else if args[index].starts_with("--") || file.is_some() {
                    return None;
                } else {
                    file = Some(args[index].clone());
                }
                index += 1;
            }
            Some(Options {
                command: command.into(),
                file: file?,
                start_ms,
            })
        }
        _ => None,
    }
}

fn usage() {
    println!(
        "usage:\n  klip check <file.klip>\n  klip compile <file.klip>\n  klip play [--start-at MM:SS.mmm] <file.klip>"
    );
}

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
        match options.command.as_str() {
            "check" => check(&timeline),
            "compile" => {
                for event in &timeline.events {
                    println!("{}", event.describe());
                }
            }
            "play" => play(&timeline, options.start_ms)?,
            _ => unreachable!(),
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

fn play(timeline: &Timeline, start_ms: i64) -> std::result::Result<(), Box<dyn std::error::Error>> {
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
    let stop = Arc::new(AtomicBool::new(false));
    let stop_signal = Arc::clone(&stop);
    ctrlc::set_handler(move || stop_signal.store(true, Ordering::SeqCst))?;
    let stdout = std::io::stdout();
    let mut renderer = TerminalRenderer::new(width, height, stdout.lock())?;
    let playback = (|| -> std::result::Result<(), Box<dyn std::error::Error>> {
        renderer.render(&Event {
            time_ms: 0,
            order: i64::MIN,
            cursor: "__startup__".into(),
            z: i32::MAX,
            protect: false,
            ops: vec![Op::Clear],
            line: 0,
            source: "runtime:startup".into(),
        })?;
        renderer.flush()?;
        let mut index = 0;
        let mut rendered = false;
        while index < timeline.events.len() && timeline.events[index].time_ms < start_ms {
            renderer.render(&timeline.events[index])?;
            rendered = true;
            index += 1;
        }
        if rendered {
            renderer.flush()?;
        }
        let mut audio = AudioClock::start(&timeline.document, timeline.end_ms(), start_ms);
        if let Some(warning) = audio.warning() {
            eprintln!("{warning}");
        }
        while (index < timeline.events.len() || !audio.is_finished())
            && !stop.load(Ordering::SeqCst)
        {
            let now = audio.current_ms();
            rendered = false;
            while index < timeline.events.len() && timeline.events[index].time_ms <= now {
                renderer.render(&timeline.events[index])?;
                rendered = true;
                index += 1;
            }
            if rendered {
                renderer.flush()?;
            }
            if index >= timeline.events.len() && audio.is_finished() {
                break;
            }
            thread::sleep(Duration::from_millis(5));
        }
        Ok(())
    })();
    let restore = renderer.restore();
    playback?;
    restore?;
    Ok(())
}

fn main() -> ExitCode {
    ExitCode::from(run(&env::args().skip(1).collect::<Vec<_>>()) as u8)
}
