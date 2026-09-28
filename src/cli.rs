use std::path::PathBuf;

use crate::parser::parse_absolute;

pub enum Action {
    Check,
    Compile,
    Inspect {
        at_ms: i64,
        window_ms: i64,
    },
    Render {
        at_ms: i64,
    },
    Play {
        start_ms: i64,
        report: Option<PathBuf>,
    },
}

pub struct Options {
    pub action: Action,
    pub file: String,
}

fn flag_value<'a>(args: &'a [String], index: &mut usize, name: &str) -> Option<&'a str> {
    let current = args.get(*index)?;
    if current == name {
        *index += 1;
        args.get(*index).map(String::as_str)
    } else {
        current.strip_prefix(name)?.strip_prefix('=')
    }
}

fn parse_window(value: &str) -> Option<i64> {
    if let Some(ms) = value.strip_suffix("ms") {
        ms.parse::<i64>().ok().filter(|n| *n >= 0)
    } else if let Some(seconds) = value.strip_suffix('s') {
        seconds
            .parse::<i64>()
            .ok()
            .filter(|n| *n >= 0)?
            .checked_mul(1000)
    } else {
        parse_absolute(value)
    }
}

pub fn parse_args(args: &[String]) -> Option<Options> {
    let command = args.first()?.as_str();
    if matches!(command, "check" | "compile") {
        return (args.len() == 2).then(|| Options {
            action: if command == "check" {
                Action::Check
            } else {
                Action::Compile
            },
            file: args[1].clone(),
        });
    }
    if !matches!(command, "inspect" | "render" | "play") {
        return None;
    }
    let mut file = None;
    let mut at_ms = None;
    let mut window_ms = None;
    let mut report = None;
    let mut index = 1;
    while index < args.len() {
        let arg = args[index].as_str();
        if arg == "--at" || arg.starts_with("--at=") {
            if command == "play" || at_ms.is_some() {
                return None;
            }
            at_ms = Some(parse_absolute(flag_value(args, &mut index, "--at")?)?);
        } else if arg == "--start-at" || arg.starts_with("--start-at=") {
            if command != "play" || at_ms.is_some() {
                return None;
            }
            at_ms = Some(parse_absolute(flag_value(args, &mut index, "--start-at")?)?);
        } else if arg == "--window" || arg.starts_with("--window=") {
            if command != "inspect" || window_ms.is_some() {
                return None;
            }
            window_ms = Some(parse_window(flag_value(args, &mut index, "--window")?)?);
        } else if arg == "--sync-report" || arg.starts_with("--sync-report=") {
            if command != "play" || report.is_some() {
                return None;
            }
            let value = flag_value(args, &mut index, "--sync-report")?;
            if value.is_empty() {
                return None;
            }
            report = Some(PathBuf::from(value));
        } else if arg.starts_with('-') || file.is_some() {
            return None;
        } else {
            file = Some(args[index].clone());
        }
        index += 1;
    }
    let action = match command {
        "inspect" => Action::Inspect {
            at_ms: at_ms?,
            window_ms: window_ms.unwrap_or(500),
        },
        "render" if window_ms.is_none() => Action::Render { at_ms: at_ms? },
        "play" if window_ms.is_none() => Action::Play {
            start_ms: at_ms.unwrap_or(0),
            report,
        },
        _ => return None,
    };
    Some(Options {
        action,
        file: file?,
    })
}

pub fn usage() {
    println!(
        "usage:\n  klip check <file.klip>\n  klip compile <file.klip>\n  klip inspect --at MM:SS.mmm [--window 500ms] <file.klip>\n  klip render --at MM:SS.mmm <file.klip>\n  klip play [--start-at MM:SS.mmm] [--sync-report FILE] <file.klip>"
    );
}
