use std::fs::{File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::Path;

use crate::audio::music_path;
use crate::model::{Event, Timeline};

pub fn inspect(timeline: &Timeline, at_ms: i64, window_ms: i64) {
    let from_ms = at_ms.saturating_sub(window_ms).max(0);
    let to_ms = at_ms.saturating_add(window_ms);
    let first = timeline.events.partition_point(|e| e.time_ms < from_ms);
    let last = timeline.events.partition_point(|e| e.time_ms <= to_ms);
    let through_at = timeline.events.partition_point(|e| e.time_ms <= at_ms);
    println!("file={}", timeline.document.file);
    println!("at={at_ms}ms window={window_ms}ms range={from_ms}..{to_ms}ms");
    println!(
        "canvas={}x{}",
        timeline.document.meta.width(),
        timeline.document.meta.height()
    );
    match music_path(&timeline.document) {
        Some(path) => println!("music={} exists={}", path.display(), path.is_file()),
        None => println!("music=<none> exists=false"),
    }
    println!(
        "events_in_window={} events_through_at={} events_total={}",
        last - first,
        through_at,
        timeline.events.len()
    );
    for event in &timeline.events[first..last] {
        println!("{}", event.describe());
    }
}

#[derive(Clone, Copy)]
enum Phase {
    Preload,
    Timed { observed_ms: i64 },
}

struct Sample {
    phase: Phase,
    scheduled_ms: i64,
    line: usize,
    source: String,
}

pub struct SyncReport {
    out: BufWriter<File>,
    start_ms: i64,
    mode: &'static str,
    samples: Vec<Sample>,
}

impl SyncReport {
    pub fn create(path: &Path, start_ms: i64) -> io::Result<Self> {
        let file = OpenOptions::new().write(true).create_new(true).open(path)?;
        Ok(Self {
            out: BufWriter::new(file),
            start_ms,
            mode: "not-started",
            samples: Vec::new(),
        })
    }

    pub fn set_mode(&mut self, mode: &'static str) {
        self.mode = mode;
    }

    pub fn preload(&mut self, event: &Event) {
        self.samples.push(Sample {
            phase: Phase::Preload,
            scheduled_ms: event.time_ms,
            line: event.line,
            source: event.source.clone(),
        });
    }

    pub fn timed(&mut self, events: &[Event], observed_ms: i64) {
        self.samples.extend(events.iter().map(|event| Sample {
            phase: Phase::Timed { observed_ms },
            scheduled_ms: event.time_ms,
            line: event.line,
            source: event.source.clone(),
        }));
    }

    pub fn finish(mut self) -> io::Result<()> {
        let mut delays: Vec<_> = self
            .samples
            .iter()
            .filter_map(|sample| match sample.phase {
                Phase::Preload => None,
                Phase::Timed { observed_ms } => {
                    Some(observed_ms.saturating_sub(sample.scheduled_ms).max(0))
                }
            })
            .collect();
        delays.sort_unstable();
        let p95 = if delays.is_empty() {
            0
        } else {
            delays[(delays.len() * 95).div_ceil(100) - 1]
        };
        writeln!(self.out, "# KlipPlayer sync report v1")?;
        writeln!(self.out, "# clock={}", self.mode)?;
        writeln!(self.out, "# start_at_ms={}", self.start_ms)?;
        writeln!(self.out, "# timed_events={}", delays.len())?;
        writeln!(
            self.out,
            "# max_dispatch_delay_ms={}",
            delays.last().unwrap_or(&0)
        )?;
        writeln!(self.out, "# p95_dispatch_delay_ms={p95}")?;
        writeln!(
            self.out,
            "# observed_ms is the playback clock after terminal flush (monotonic tail after audio ends); speaker and display latency are not measured"
        )?;
        writeln!(
            self.out,
            "phase\tscheduled_ms\tobserved_ms\tdispatch_delay_ms\tline\tsource"
        )?;
        for sample in &self.samples {
            match sample.phase {
                Phase::Preload => writeln!(
                    self.out,
                    "preload\t{}\t\t\t{}\t{}",
                    sample.scheduled_ms, sample.line, sample.source
                )?,
                Phase::Timed { observed_ms } => writeln!(
                    self.out,
                    "timed\t{}\t{}\t{}\t{}\t{}",
                    sample.scheduled_ms,
                    observed_ms,
                    observed_ms.saturating_sub(sample.scheduled_ms),
                    sample.line,
                    sample.source
                )?,
            }
        }
        self.out.flush()
    }
}
