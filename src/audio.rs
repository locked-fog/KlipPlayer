use std::fs::File;
use std::io::BufReader;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use rodio::{Decoder, DeviceSinkBuilder, MixerDeviceSink, Player, Source};

use crate::model::Document;

pub struct AudioClock {
    mode: Mode,
    warning: Option<String>,
}

enum Mode {
    Fallback {
        start: Instant,
        offset_ms: i64,
        end_ms: i64,
    },
    Playing {
        _device: MixerDeviceSink,
        player: Player,
        duration_ms: Option<i64>,
        last_ms: i64,
        tail: Option<(Instant, i64)>,
    },
}

impl AudioClock {
    pub fn start(doc: &Document, timeline_end_ms: i64, start_at_ms: i64) -> Self {
        let end_ms = timeline_end_ms.saturating_add(250);
        let fallback = |warning: String| Self {
            mode: Mode::Fallback {
                start: Instant::now(),
                offset_ms: start_at_ms,
                end_ms,
            },
            warning: Some(warning),
        };
        let Some(path) = music_path(doc) else {
            return fallback(
                "warning: music meta is missing; using monotonic no-audio clock".into(),
            );
        };
        if !path.exists() {
            return fallback(format!(
                "warning: audio file not found: {}; using monotonic no-audio clock",
                path.display()
            ));
        }
        match open_player(&path, start_at_ms) {
            Ok((device, player, duration_ms)) => Self {
                mode: Mode::Playing {
                    _device: device,
                    player,
                    duration_ms,
                    last_ms: start_at_ms.min(duration_ms.unwrap_or(start_at_ms)),
                    tail: None,
                },
                warning: None,
            },
            Err(reason) => fallback(format!(
                "warning: audio could not be started for {} ({reason}); using monotonic no-audio clock",
                path.display()
            )),
        }
    }

    pub fn warning(&self) -> Option<&str> {
        self.warning.as_deref()
    }

    pub fn mode_name(&self) -> &'static str {
        match &self.mode {
            Mode::Fallback { .. } => "fallback",
            Mode::Playing { .. } => "audio",
        }
    }

    pub fn current_ms(&mut self) -> i64 {
        match &mut self.mode {
            Mode::Fallback {
                start, offset_ms, ..
            } => offset_ms.saturating_add(start.elapsed().as_millis() as i64),
            Mode::Playing {
                player,
                duration_ms,
                last_ms,
                tail,
                ..
            } => {
                if player.empty() {
                    let (end, position) = tail.get_or_insert_with(|| {
                        (
                            Instant::now(),
                            duration_ms.unwrap_or(*last_ms).max(*last_ms),
                        )
                    });
                    position.saturating_add(end.elapsed().as_millis() as i64)
                } else {
                    let position = player.get_pos().as_millis() as i64;
                    *last_ms = (*last_ms).max(position);
                    *last_ms
                }
            }
        }
    }

    pub fn is_finished(&mut self) -> bool {
        match &mut self.mode {
            Mode::Fallback {
                start,
                offset_ms,
                end_ms,
            } => offset_ms.saturating_add(start.elapsed().as_millis() as i64) >= *end_ms,
            Mode::Playing { player, .. } => player.empty(),
        }
    }

    pub fn stop(&self) {
        if let Mode::Playing { player, .. } = &self.mode {
            player.stop();
        }
    }
}

pub fn music_path(doc: &Document) -> Option<PathBuf> {
    let raw = Path::new(doc.meta.music()?);
    Some(if raw.is_absolute() {
        raw.to_path_buf()
    } else {
        Path::new(&doc.file)
            .parent()
            .unwrap_or_else(|| Path::new(""))
            .join(raw)
    })
}

impl Drop for AudioClock {
    fn drop(&mut self) {
        self.stop();
    }
}

fn open_player(
    path: &PathBuf,
    start_at_ms: i64,
) -> std::result::Result<(MixerDeviceSink, Player, Option<i64>), String> {
    let file = File::open(path).map_err(|e| e.to_string())?;
    let decoder = Decoder::try_from(BufReader::new(file)).map_err(|e| e.to_string())?;
    let duration_ms = decoder
        .total_duration()
        .map(|duration| duration.as_millis() as i64);
    let mut device = DeviceSinkBuilder::open_default_sink().map_err(|e| e.to_string())?;
    device.log_on_drop(false);
    let player = Player::connect_new(device.mixer());
    player.pause();
    player.append(decoder);
    if start_at_ms > 0 {
        player
            .try_seek(Duration::from_millis(start_at_ms as u64))
            .map_err(|e| e.to_string())?;
    }
    player.play();
    Ok((device, player, duration_ms))
}
