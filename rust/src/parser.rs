use std::collections::HashMap;

use crate::model::{Anchor, Cue, CueEntry, Document, KlipError, Meta, Op, RawEvent, Result, Track};

fn error(file: &str, line: usize, detail: impl Into<String>) -> KlipError {
    KlipError::new("KLP1001", file, line, detail)
}

fn identifier(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some('a'..='z' | 'A'..='Z' | '_'))
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

fn validate_identifier(file: &str, line: usize, value: &str) -> Result<()> {
    if identifier(value) {
        Ok(())
    } else {
        Err(error(file, line, format!("非法标识符: {value}")))
    }
}

pub fn parse_absolute(value: &str) -> Option<i64> {
    let (minutes, rest) = value.split_once(':')?;
    let (seconds, millis) = rest.split_once('.')?;
    if minutes.is_empty()
        || !minutes.bytes().all(|b| b.is_ascii_digit())
        || seconds.len() != 2
        || !seconds.bytes().all(|b| b.is_ascii_digit())
        || millis.len() != 3
        || !millis.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    let seconds: i64 = seconds.parse().ok()?;
    if seconds > 59 {
        return None;
    }
    minutes
        .parse::<i64>()
        .ok()?
        .checked_mul(60_000)?
        .checked_add(seconds * 1_000)?
        .checked_add(millis.parse::<i64>().ok()?)
}

fn read_tag<'a>(file: &str, line: usize, input: &'a str, start: usize) -> Result<(&'a str, usize)> {
    let end = input[start + 1..]
        .find(']')
        .map(|x| start + 1 + x)
        .ok_or_else(|| error(file, line, "标签缺少右方括号"))?;
    Ok((&input[start + 1..end], end + 1))
}

fn first_tag<'a>(file: &str, line: usize, input: &'a str) -> Result<(&'a str, &'a str)> {
    if !input.starts_with('[') {
        return Err(error(file, line, "事件行必须以标签开头"));
    }
    let (tag, end) = read_tag(file, line, input, 0)?;
    Ok((tag, &input[end..]))
}

fn split_fields(file: &str, line: usize, input: &str) -> Result<Vec<String>> {
    let mut out = Vec::new();
    let mut current = String::new();
    let mut quoted = false;
    let mut escaped = false;
    for c in input.chars() {
        if escaped {
            current.push(match c {
                'n' => '\n',
                't' => '\t',
                x => x,
            });
            escaped = false;
        } else if c == '\\' && quoted {
            escaped = true;
        } else if c == '"' {
            quoted = !quoted;
        } else if c.is_whitespace() && !quoted {
            if !current.is_empty() {
                out.push(std::mem::take(&mut current));
            }
        } else {
            current.push(c);
        }
    }
    if escaped {
        return Err(error(file, line, "字符串转义不完整"));
    }
    if quoted {
        return Err(error(file, line, "字符串缺少右引号"));
    }
    if !current.is_empty() {
        out.push(current);
    }
    Ok(out)
}

fn ensure_no_rest(file: &str, line: usize, rest: &str) -> Result<()> {
    if rest.trim().is_empty() {
        Ok(())
    } else {
        Err(error(file, line, "顶层/块标签后不允许额外文本"))
    }
}

fn parse_meta(file: &str, line: usize, tag: &str, meta: &mut Meta) -> Result<()> {
    let fields = split_fields(file, line, tag)?;
    if fields.len() < 2 {
        return Err(error(file, line, "meta 缺少 key=value"));
    }
    for field in fields.iter().skip(1) {
        let (key, value) = field
            .split_once('=')
            .filter(|(key, _)| !key.is_empty())
            .ok_or_else(|| error(file, line, format!("meta 参数不是 key=value: {field}")))?;
        match key {
            "width" | "height" => {
                if value.parse::<usize>().ok().filter(|v| *v > 0).is_none() {
                    return Err(error(file, line, format!("meta {key} 必须是正整数")));
                }
            }
            "addon" if value.is_empty() => return Err(error(file, line, "meta addon 不能为空")),
            "music" | "title" | "addon" => (),
            _ if !identifier(key) => {
                return Err(error(file, line, format!("非法 meta key: {key}")));
            }
            _ => (),
        }
        if key == "addon" {
            meta.addons.push((value.to_owned(), line));
        } else {
            meta.values.insert(key.to_owned(), value.to_owned());
        }
    }
    Ok(())
}

fn parse_anchor(file: &str, line: usize, tag: &str) -> Result<Anchor> {
    let fields = split_fields(file, line, tag)?;
    if fields.len() != 4 {
        return Err(error(
            file,
            line,
            "anchor 语法应为 [anchor name mm:ss.mmm bpm=number]",
        ));
    }
    validate_identifier(file, line, &fields[1])?;
    let time_ms = parse_absolute(&fields[2])
        .ok_or_else(|| error(file, line, format!("绝对时间无法解析: {}", fields[2])))?;
    let raw = fields[3]
        .strip_prefix("bpm=")
        .ok_or_else(|| error(file, line, "anchor 缺少 bpm=number"))?;
    let bpm: f64 = raw
        .parse()
        .map_err(|_| error(file, line, format!("BPM 无法解析: {raw}")))?;
    if bpm <= 0.0 || !bpm.is_finite() {
        return Err(error(file, line, "BPM 必须为正数"));
    }
    Ok(Anchor {
        name: fields[1].clone(),
        time_ms,
        bpm,
        line,
    })
}

struct Block {
    name: String,
    cursor: String,
    z: i32,
    protect: bool,
    line: usize,
    is_cue: bool,
    entries: Vec<CueEntry>,
}

fn parse_block(file: &str, line: usize, tag: &str, is_cue: bool) -> Result<Block> {
    let fields = split_fields(file, line, tag)?;
    if fields.len() < 2 {
        return Err(error(file, line, "track/cue 缺少名称"));
    }
    validate_identifier(file, line, &fields[1])?;
    let mut attrs = HashMap::new();
    for field in fields.iter().skip(2) {
        let (key, value) = field
            .split_once('=')
            .filter(|(key, _)| !key.is_empty())
            .ok_or_else(|| error(file, line, format!("参数不是 key=value: {field}")))?;
        if !matches!(key, "cursor" | "z" | "protect") {
            return Err(error(file, line, format!("未知参数: {key}")));
        }
        if value.is_empty() {
            return Err(error(file, line, format!("参数值不能为空: {key}")));
        }
        if attrs.insert(key, value).is_some() {
            return Err(error(file, line, format!("重复参数: {key}")));
        }
    }
    let cursor = attrs.get("cursor").copied().unwrap_or(&fields[1]);
    validate_identifier(file, line, cursor)?;
    let z = match attrs.get("z") {
        Some(v) => v
            .parse::<i32>()
            .map_err(|_| error(file, line, "z 必须是整数"))?,
        None => 0,
    };
    if z < 0 {
        return Err(error(file, line, "z 必须是非负整数"));
    }
    let protect = match attrs.get("protect").copied().unwrap_or("off") {
        "on" => true,
        "off" => false,
        _ => return Err(error(file, line, "protect 必须是 on 或 off")),
    };
    Ok(Block {
        name: fields[1].clone(),
        cursor: cursor.to_owned(),
        z,
        protect,
        line,
        is_cue,
        entries: Vec::new(),
    })
}

fn next_tag(input: &str, start: usize) -> Option<usize> {
    input[start..].char_indices().find_map(|(offset, c)| {
        if c != '[' {
            return None;
        }
        let at = start + offset;
        let slashes = input[..at]
            .bytes()
            .rev()
            .take_while(|b| *b == b'\\')
            .count();
        if slashes % 2 == 0 { Some(at) } else { None }
    })
}

fn unescape_text(raw: &str) -> String {
    let mut out = String::new();
    let mut escaped = false;
    for c in raw.chars() {
        if escaped {
            out.push(match c {
                '[' => '[',
                ']' => ']',
                '\\' => '\\',
                'n' => '\n',
                't' => '\t',
                x => x,
            });
            escaped = false;
        } else if c == '\\' {
            escaped = true;
        } else {
            out.push(c);
        }
    }
    if escaped {
        out.push('\\');
    }
    out
}

fn add_text(raw: &str, ops: &mut Vec<Op>) {
    if !raw.trim().is_empty() {
        ops.push(Op::Text(unescape_text(raw)));
    }
}

fn color_arg(file: &str, line: usize, fields: &[String]) -> Result<Option<String>> {
    if fields.len() != 2 {
        return Err(error(
            file,
            line,
            format!("{} 语法应为 [{} rrggbb|default]", fields[0], fields[0]),
        ));
    }
    let value = &fields[1];
    if value == "default" {
        return Ok(None);
    }
    if value.len() != 6 || !value.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err(error(
            file,
            line,
            format!("颜色必须是 6 位十六进制 RGB: {value}"),
        ));
    }
    Ok(Some(value.to_ascii_lowercase()))
}

fn parse_command(file: &str, line: usize, tag: &str) -> Result<(Option<Op>, Option<String>)> {
    let fields = split_fields(file, line, tag)?;
    let command = fields.first().ok_or_else(|| error(file, line, "空标签"))?;
    let count = |expected: usize| -> Result<()> {
        if fields.len() == expected {
            Ok(())
        } else {
            Err(error(file, line, format!("{command} 不接受参数")))
        }
    };
    let op = match command.as_str() {
        "emit" => {
            if fields.len() != 2 {
                return Err(error(file, line, "emit 语法应为 [emit cueName]"));
            }
            validate_identifier(file, line, &fields[1])?;
            return Ok((None, Some(fields[1].clone())));
        }
        "func" => {
            if fields.len() < 2 {
                return Err(error(file, line, "func 语法应为 [func name key=value ...]"));
            }
            validate_identifier(file, line, &fields[1])?;
            let mut args = Vec::new();
            for field in fields.iter().skip(2) {
                let (key, value) = field
                    .split_once('=')
                    .filter(|(k, _)| !k.is_empty())
                    .ok_or_else(|| {
                        error(file, line, format!("func 参数不是 key=value: {field}"))
                    })?;
                validate_identifier(file, line, key)?;
                if args.iter().any(|(k, _): &(String, String)| k == key) {
                    return Err(error(file, line, format!("重复参数: {key}")));
                }
                if value.is_empty() {
                    return Err(error(file, line, format!("参数值不能为空: {key}")));
                }
                args.push((key.to_owned(), value.to_owned()));
            }
            Op::FunctionCall(fields[1].clone(), args)
        }
        "mv" => {
            if fields.len() != 2 {
                return Err(error(file, line, "mv 语法应为 [mv row,col]"));
            }
            let (r, c) = fields[1]
                .split_once(',')
                .ok_or_else(|| error(file, line, "mv 必须使用逗号: [mv row,col]"))?;
            let row = r
                .parse::<i64>()
                .map_err(|_| error(file, line, "mv row 不是整数"))?;
            let col = c
                .parse::<i64>()
                .map_err(|_| error(file, line, "mv col 不是整数"))?;
            if row <= 0 || col <= 0 {
                return Err(error(file, line, "mv row 和 col 必须从 1 开始"));
            }
            Op::Move(row as usize, col as usize)
        }
        "color" => Op::Foreground(color_arg(file, line, &fields)?),
        "background" => Op::Background(color_arg(file, line, &fields)?),
        "style" => {
            if fields.len() == 2 && fields[1] == "default" {
                Op::Style(None, None)
            } else {
                if fields.len() != 3 {
                    return Err(error(
                        file,
                        line,
                        "style 语法应为 [style name on|off] 或 [style default]",
                    ));
                }
                if !matches!(
                    fields[1].as_str(),
                    "bold" | "italic" | "underline" | "strikeline"
                ) {
                    return Err(error(file, line, format!("未知 style: {}", fields[1])));
                }
                let enabled = match fields[2].as_str() {
                    "on" => true,
                    "off" => false,
                    _ => return Err(error(file, line, "style 开关必须是 on 或 off")),
                };
                Op::Style(Some(fields[1].clone()), Some(enabled))
            }
        }
        "space" => {
            if fields.len() > 2 {
                return Err(error(file, line, "space 语法应为 [space] 或 [space n]"));
            }
            let amount = if fields.len() == 1 {
                1
            } else {
                fields[1]
                    .parse::<i64>()
                    .map_err(|_| error(file, line, "space 数量必须是整数"))?
            };
            if amount < 0 {
                return Err(error(file, line, "space 数量不能为负数"));
            }
            Op::Space(amount as usize)
        }
        "newline" => {
            count(1)?;
            Op::Newline
        }
        "cleanline" => {
            count(1)?;
            Op::CleanLine
        }
        "clear" => {
            count(1)?;
            Op::Clear
        }
        "hide" => {
            count(1)?;
            Op::Hide
        }
        "show" => {
            count(1)?;
            Op::Show
        }
        _ => return Err(error(file, line, format!("未知命令标签 [{command}]"))),
    };
    Ok((Some(op), None))
}

fn parse_event(file: &str, line: usize, input: &str, allow_emit: bool) -> Result<RawEvent> {
    let (time, rest) = first_tag(file, line, input)?;
    let mut ops = Vec::new();
    let mut emit = None;
    let mut index = 0;
    while index < rest.len() {
        let Some(start) = next_tag(rest, index) else {
            add_text(&rest[index..], &mut ops);
            break;
        };
        if start > index {
            add_text(&rest[index..start], &mut ops);
        }
        let (tag, end) = read_tag(file, line, rest, start)?;
        let (op, name) = parse_command(file, line, tag.trim())?;
        if let Some(name) = name {
            if emit.replace(name).is_some() {
                return Err(error(file, line, "同一事件不能包含多个 emit"));
            }
        }
        if let Some(op) = op {
            ops.push(op);
        }
        index = end;
    }
    if ops.iter().any(|op| matches!(op, Op::FunctionCall(..))) && (emit.is_some() || ops.len() != 1)
    {
        return Err(error(file, line, "func 行不能混写其它命令、文本或 emit"));
    }
    if emit.is_some() {
        if !allow_emit {
            return Err(error(file, line, "cue 内不允许使用 emit"));
        }
        if !ops.is_empty() {
            return Err(error(file, line, "emit 行不能混写其它命令或文本"));
        }
    }
    Ok(RawEvent {
        line,
        time: time.trim().to_owned(),
        ops,
        emit,
    })
}

pub fn parse_document(file: &str, text: &str) -> Result<Document> {
    let mut document = Document {
        file: file.to_owned(),
        meta: Meta::default(),
        anchors: Vec::new(),
        cues: Vec::new(),
        tracks: Vec::new(),
    };
    let normalized = text.replace("\r\n", "\n").replace('\r', "\n");
    let mut block: Option<Block> = None;
    let mut loop_entries: Option<(usize, Vec<RawEvent>)> = None;
    for (index, original) in normalized.split('\n').enumerate() {
        let line_no = index + 1;
        let line = original.trim_start();
        let control = line.trim();
        if line.is_empty() || line.starts_with("//") {
            continue;
        }
        if block.is_none() {
            let (tag, rest) = first_tag(file, line_no, line)?;
            let fields = split_fields(file, line_no, tag)?;
            match fields.first().map(String::as_str) {
                Some("meta") => {
                    ensure_no_rest(file, line_no, rest)?;
                    parse_meta(file, line_no, tag, &mut document.meta)?;
                }
                Some("anchor") => {
                    ensure_no_rest(file, line_no, rest)?;
                    document.anchors.push(parse_anchor(file, line_no, tag)?);
                }
                Some("cue") | Some("track") => {
                    ensure_no_rest(file, line_no, rest)?;
                    block = Some(parse_block(file, line_no, tag, fields[0] == "cue")?);
                }
                other => {
                    return Err(error(
                        file,
                        line_no,
                        format!("未知顶层标签 [{}]", other.unwrap_or("null")),
                    ));
                }
            }
            continue;
        }
        let current = block.as_mut().expect("checked above");
        if current.is_cue {
            if control == "[endcue]" {
                if loop_entries.is_some() {
                    return Err(error(file, line_no, "cue 结束前缺少 [endloop]"));
                }
                let done = block.take().expect("active block");
                document.cues.push(Cue {
                    name: done.name,
                    cursor: done.cursor,
                    z: done.z,
                    protect: done.protect,
                    line: done.line,
                    entries: done.entries,
                });
            } else if control == "[endtrack]" {
                return Err(error(file, line_no, "[endtrack] 出现在 cue 内"));
            } else if control.starts_with("[loop ") {
                if loop_entries.is_some() {
                    return Err(error(file, line_no, "不允许嵌套 loop"));
                }
                let (tag, rest) = first_tag(file, line_no, control)?;
                ensure_no_rest(file, line_no, rest)?;
                let fields = split_fields(file, line_no, tag)?;
                if fields.len() != 2 || fields[0] != "loop" {
                    return Err(error(file, line_no, "loop 语法应为 [loop n]"));
                }
                let count = fields[1]
                    .parse::<usize>()
                    .map_err(|_| error(file, line_no, "loop 次数必须是正整数"))?;
                if count == 0 {
                    return Err(error(file, line_no, "loop 次数必须是正整数"));
                }
                loop_entries = Some((count, Vec::new()));
            } else if control == "[endloop]" {
                let (count, entries) = loop_entries
                    .take()
                    .ok_or_else(|| error(file, line_no, "[endloop] 没有对应的 [loop]"))?;
                current.entries.push(CueEntry::Loop { count, entries });
            } else {
                let event = parse_event(file, line_no, line, false)?;
                if let Some((_, entries)) = loop_entries.as_mut() {
                    entries.push(event);
                } else {
                    current.entries.push(CueEntry::Event(event));
                }
            }
        } else if control == "[endtrack]" {
            let done = block.take().expect("active block");
            document.tracks.push(Track {
                name: done.name,
                cursor: done.cursor,
                z: done.z,
                protect: done.protect,
                entries: done
                    .entries
                    .into_iter()
                    .filter_map(|entry| match entry {
                        CueEntry::Event(e) => Some(e),
                        _ => None,
                    })
                    .collect(),
            });
        } else if control.starts_with("[loop ") || control == "[endloop]" {
            return Err(error(file, line_no, "loop 只允许出现在 cue 内"));
        } else if control == "[endcue]" {
            return Err(error(file, line_no, "[endcue] 出现在 track 内"));
        } else {
            current
                .entries
                .push(CueEntry::Event(parse_event(file, line_no, line, true)?));
        }
    }
    if let Some(done) = block {
        return Err(error(
            file,
            done.line,
            format!(
                "块 [{} {}] 未关闭",
                if done.is_cue { "cue" } else { "track" },
                done.name
            ),
        ));
    }
    Ok(document)
}
