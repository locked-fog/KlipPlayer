use std::collections::HashMap;

use crate::lua::LuaRegistry;
use crate::model::{
    Anchor, Cue, CueEntry, Document, Event, KlipError, Op, RawEvent, Result, Timeline,
};
use crate::parser::parse_absolute;

fn error(doc: &Document, line: usize, code: &'static str, detail: impl Into<String>) -> KlipError {
    KlipError::new(code, &doc.file, line, detail)
}

#[derive(Clone, Copy)]
struct ResolvedTime {
    ms: i64,
    bpm: Option<f64>,
}

pub fn parse_duration(raw: &str, bpm: Option<f64>, doc: &Document, line: usize) -> Result<i64> {
    let value = raw.trim();
    if value.is_empty() {
        return Err(error(doc, line, "KLP5001", "duration 为空"));
    }
    let number = value.strip_suffix("ms").unwrap_or(value);
    if !number.is_empty() && number.bytes().all(|b| b.is_ascii_digit()) {
        return number.parse().map_err(|_| {
            error(
                doc,
                line,
                "KLP5001",
                format!("毫秒 duration 无法解析: {raw}"),
            )
        });
    }
    if value.ends_with("ms") {
        return Err(error(
            doc,
            line,
            "KLP5001",
            format!("毫秒 duration 无法解析: {raw}"),
        ));
    }
    if let Some(beat) = value.strip_suffix('b') {
        let amount = if let Some((numerator, denominator)) = beat.split_once('/') {
            if numerator.is_empty()
                || denominator.is_empty()
                || !numerator.bytes().all(|b| b.is_ascii_digit())
                || !denominator.bytes().all(|b| b.is_ascii_digit())
            {
                return Err(error(
                    doc,
                    line,
                    "KLP5001",
                    format!("节拍 duration 无法解析: {beat}"),
                ));
            }
            let denominator = denominator.parse::<f64>().unwrap_or(0.0);
            if denominator == 0.0 {
                return Err(error(doc, line, "KLP5001", "分数节拍分母不能为 0"));
            }
            numerator.parse::<f64>().unwrap_or(f64::INFINITY) / denominator
        } else {
            if beat.is_empty()
                || beat.bytes().filter(|b| *b == b'.').count() > 1
                || !beat.bytes().all(|b| b.is_ascii_digit() || b == b'.')
                || beat.starts_with('.')
                || beat.ends_with('.')
            {
                return Err(error(
                    doc,
                    line,
                    "KLP5001",
                    format!("节拍 duration 无法解析: {beat}"),
                ));
            }
            beat.parse::<f64>().map_err(|_| {
                error(
                    doc,
                    line,
                    "KLP5001",
                    format!("节拍 duration 无法解析: {beat}"),
                )
            })?
        };
        let bpm = bpm.ok_or_else(|| error(doc, line, "KLP5001", "相对节拍缺少 BPM 上下文"))?;
        return Ok((60_000.0 / bpm * amount).round() as i64);
    }
    Err(error(
        doc,
        line,
        "KLP5001",
        format!("duration 无法解析: {raw}"),
    ))
}

fn resolve_track(
    doc: &Document,
    anchors: &HashMap<&str, &Anchor>,
    raw: &RawEvent,
    previous: Option<ResolvedTime>,
) -> Result<ResolvedTime> {
    if let Some(ms) = parse_absolute(&raw.time) {
        return Ok(ResolvedTime { ms, bpm: None });
    }
    if raw.time.chars().next().is_some_and(|c| c.is_ascii_digit())
        && (raw.time.contains(':') || raw.time.contains('.'))
    {
        return Err(error(
            doc,
            raw.line,
            "KLP5001",
            format!("绝对时间无法解析: {}", raw.time),
        ));
    }
    if let Some(duration) = raw.time.strip_prefix('+') {
        let base = previous.ok_or_else(|| {
            error(
                doc,
                raw.line,
                "KLP5001",
                format!("相对时间缺少上一事件: {}", raw.time),
            )
        })?;
        return Ok(ResolvedTime {
            ms: base.ms + parse_duration(duration, base.bpm, doc, raw.line)?,
            bpm: base.bpm,
        });
    }
    let anchor = anchors
        .values()
        .copied()
        .filter(|anchor| {
            raw.time == anchor.name
                || raw.time.starts_with(&format!("{}+", anchor.name))
                || raw.time.starts_with(&format!("{}-", anchor.name))
        })
        .max_by_key(|anchor| anchor.name.len())
        .ok_or_else(|| {
            error(
                doc,
                raw.line,
                "KLP3001",
                format!(
                    "未定义 anchor: {}",
                    raw.time.split(['+', '-']).next().unwrap_or("")
                ),
            )
        })?;
    let rest = &raw.time[anchor.name.len()..];
    let offset = if rest.is_empty() {
        0
    } else if let Some(v) = rest.strip_prefix('+') {
        parse_duration(v, Some(anchor.bpm), doc, raw.line)?
    } else if let Some(v) = rest.strip_prefix('-') {
        -parse_duration(v, Some(anchor.bpm), doc, raw.line)?
    } else {
        return Err(error(
            doc,
            raw.line,
            "KLP5001",
            format!("时间表达式无法解析: {}", raw.time),
        ));
    };
    Ok(ResolvedTime {
        ms: anchor.time_ms + offset,
        bpm: Some(anchor.bpm),
    })
}

fn resolve_cue(doc: &Document, raw: &RawEvent, previous: ResolvedTime) -> Result<ResolvedTime> {
    let duration = raw.time.strip_prefix('+').ok_or_else(|| {
        error(
            doc,
            raw.line,
            "KLP2001",
            format!("cue 内只允许使用相对时间: {}", raw.time),
        )
    })?;
    Ok(ResolvedTime {
        ms: previous.ms + parse_duration(duration, previous.bpm, doc, raw.line)?,
        bpm: previous.bpm,
    })
}

fn append_event(
    out: &mut Vec<Event>,
    order: &mut i64,
    time: i64,
    cursor: &str,
    z: i32,
    protect: bool,
    ops: Vec<Op>,
    line: usize,
    source: String,
) {
    out.push(Event {
        time_ms: time,
        order: *order,
        cursor: cursor.to_owned(),
        z,
        protect,
        ops,
        line,
        source,
    });
    *order += 1;
}

#[allow(clippy::too_many_arguments)]
fn expand_event(
    doc: &Document,
    addons: &LuaRegistry,
    raw: &RawEvent,
    base: ResolvedTime,
    cursor: &str,
    z: i32,
    protect: bool,
    source: &str,
    out: &mut Vec<Event>,
    order: &mut i64,
) -> Result<()> {
    if let [Op::FunctionCall(name, args)] = raw.ops.as_slice() {
        let generated = addons.expand(doc, name, args, raw.line, base.bpm)?;
        for event in generated {
            append_event(
                out,
                order,
                base.ms + event.offset_ms,
                event.cursor.as_deref().unwrap_or(cursor),
                event.z.unwrap_or(z),
                event.protect.unwrap_or(protect),
                event.ops,
                raw.line,
                format!("{source}/func:{name}"),
            );
        }
    } else if !raw.ops.is_empty() {
        append_event(
            out,
            order,
            base.ms,
            cursor,
            z,
            protect,
            raw.ops.clone(),
            raw.line,
            source.to_owned(),
        );
    }
    Ok(())
}

#[allow(clippy::too_many_arguments)]
fn expand_cue_entry(
    doc: &Document,
    addons: &LuaRegistry,
    cue: &Cue,
    raw: &RawEvent,
    emit_at: ResolvedTime,
    previous: ResolvedTime,
    looped: bool,
    out: &mut Vec<Event>,
    order: &mut i64,
) -> Result<ResolvedTime> {
    let local = resolve_cue(doc, raw, previous)?;
    let base = ResolvedTime {
        ms: emit_at.ms + local.ms,
        bpm: local.bpm,
    };
    let source = if looped {
        format!("cue:{}/loop", cue.name)
    } else {
        format!("cue:{}", cue.name)
    };
    expand_event(
        doc,
        addons,
        raw,
        base,
        &cue.cursor,
        cue.z,
        cue.protect,
        &source,
        out,
        order,
    )?;
    Ok(local)
}

fn expand_cue(
    doc: &Document,
    addons: &LuaRegistry,
    cue: &Cue,
    emit_at: ResolvedTime,
    out: &mut Vec<Event>,
    order: &mut i64,
) -> Result<()> {
    let mut previous = ResolvedTime {
        ms: 0,
        bpm: emit_at.bpm,
    };
    for entry in &cue.entries {
        match entry {
            CueEntry::Event(raw) => {
                previous =
                    expand_cue_entry(doc, addons, cue, raw, emit_at, previous, false, out, order)?
            }
            CueEntry::Loop { count, entries } => {
                for _ in 0..*count {
                    for raw in entries {
                        previous = expand_cue_entry(
                            doc, addons, cue, raw, emit_at, previous, true, out, order,
                        )?;
                    }
                }
            }
        }
    }
    Ok(())
}

pub fn compile(document: Document) -> Result<Timeline> {
    let addons = LuaRegistry::load(&document)?;
    let mut anchors = HashMap::new();
    for anchor in &document.anchors {
        if anchors.insert(anchor.name.as_str(), anchor).is_some() {
            return Err(error(
                &document,
                anchor.line,
                "KLP3002",
                format!("重复定义 anchor: {}", anchor.name),
            ));
        }
    }
    let mut cues = HashMap::new();
    for cue in &document.cues {
        if cues.insert(cue.name.as_str(), cue).is_some() {
            return Err(error(
                &document,
                cue.line,
                "KLP4002",
                format!("重复定义 cue: {}", cue.name),
            ));
        }
    }
    let mut events = Vec::new();
    let mut order = 0;
    for track in &document.tracks {
        let mut previous = None;
        for raw in &track.entries {
            let resolved = resolve_track(&document, &anchors, raw, previous)?;
            previous = Some(resolved);
            if let Some(name) = &raw.emit {
                let cue = cues.get(name.as_str()).ok_or_else(|| {
                    error(
                        &document,
                        raw.line,
                        "KLP4001",
                        format!("未定义 cue: {name}"),
                    )
                })?;
                expand_cue(&document, &addons, cue, resolved, &mut events, &mut order)?;
            } else {
                expand_event(
                    &document,
                    &addons,
                    raw,
                    resolved,
                    &track.cursor,
                    track.z,
                    track.protect,
                    &format!("track:{}", track.name),
                    &mut events,
                    &mut order,
                )?;
            }
        }
    }
    events.sort_by_key(|event| (event.time_ms, event.z, event.order));
    Ok(Timeline { document, events })
}
