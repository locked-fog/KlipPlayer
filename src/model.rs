use std::collections::HashMap;
use std::fmt;

#[derive(Debug, Clone)]
pub struct KlipError {
    pub code: &'static str,
    pub file: String,
    pub line: usize,
    pub detail: String,
}

impl KlipError {
    pub fn new(code: &'static str, file: &str, line: usize, detail: impl Into<String>) -> Self {
        Self {
            code,
            file: file.to_owned(),
            line,
            detail: detail.into(),
        }
    }
}

impl fmt::Display for KlipError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{} {} line {}: {}",
            self.code, self.file, self.line, self.detail
        )
    }
}

impl std::error::Error for KlipError {}

pub type Result<T> = std::result::Result<T, KlipError>;

#[derive(Debug, Clone, Default)]
pub struct Meta {
    pub values: HashMap<String, String>,
    pub addons: Vec<(String, usize)>,
}

impl Meta {
    pub fn music(&self) -> Option<&str> {
        self.values.get("music").map(String::as_str)
    }
    pub fn width(&self) -> usize {
        self.values
            .get("width")
            .and_then(|x| x.parse().ok())
            .unwrap_or(160)
    }
    pub fn height(&self) -> usize {
        self.values
            .get("height")
            .and_then(|x| x.parse().ok())
            .unwrap_or(40)
    }
}

#[derive(Debug, Clone)]
pub struct Anchor {
    pub name: String,
    pub time_ms: i64,
    pub bpm: f64,
    pub line: usize,
}

#[derive(Debug, Clone)]
pub struct Document {
    pub file: String,
    pub meta: Meta,
    pub anchors: Vec<Anchor>,
    pub cues: Vec<Cue>,
    pub tracks: Vec<Track>,
}

#[derive(Debug, Clone)]
pub struct Track {
    pub name: String,
    pub cursor: String,
    pub z: i32,
    pub protect: bool,
    pub entries: Vec<RawEvent>,
}

#[derive(Debug, Clone)]
pub struct Cue {
    pub name: String,
    pub cursor: String,
    pub z: i32,
    pub protect: bool,
    pub line: usize,
    pub entries: Vec<CueEntry>,
}

#[derive(Debug, Clone)]
pub enum CueEntry {
    Event(RawEvent),
    Loop {
        count: usize,
        entries: Vec<RawEvent>,
    },
}

#[derive(Debug, Clone)]
pub struct RawEvent {
    pub line: usize,
    pub time: String,
    pub ops: Vec<Op>,
    pub emit: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Op {
    Move(usize, usize),
    Foreground(Option<String>),
    Background(Option<String>),
    Style(Option<String>, Option<bool>),
    Text(String),
    Space(usize),
    FunctionCall(String, Vec<(String, String)>),
    Newline,
    CleanLine,
    Clear,
    Hide,
    Show,
}

impl Op {
    pub fn describe(&self) -> String {
        match self {
            Self::Move(r, c) => format!("mv {r},{c}"),
            Self::Foreground(v) => format!("color {}", v.as_deref().unwrap_or("default")),
            Self::Background(v) => format!("background {}", v.as_deref().unwrap_or("default")),
            Self::Style(None, _) => "style default".into(),
            Self::Style(Some(n), e) => {
                format!("style {n} {}", if *e == Some(true) { "on" } else { "off" })
            }
            Self::Text(t) => format!("text {}", t.replace('\n', "\\n")),
            Self::Space(n) => format!("space {n}"),
            Self::FunctionCall(n, _) => format!("func {n}"),
            Self::Newline => "newline".into(),
            Self::CleanLine => "cleanline".into(),
            Self::Clear => "clear".into(),
            Self::Hide => "hide".into(),
            Self::Show => "show".into(),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Event {
    pub time_ms: i64,
    pub order: i64,
    pub cursor: String,
    pub z: i32,
    pub protect: bool,
    pub ops: Vec<Op>,
    pub line: usize,
    pub source: String,
}

impl Event {
    pub fn describe(&self) -> String {
        format!(
            "{}ms order={} z={} cursor={} protect={} line={} source={} :: {}",
            self.time_ms,
            self.order,
            self.z,
            self.cursor,
            self.protect,
            self.line,
            self.source,
            self.ops
                .iter()
                .map(Op::describe)
                .collect::<Vec<_>>()
                .join(" | ")
        )
    }
}

pub struct Timeline {
    pub document: Document,
    pub events: Vec<Event>,
}

impl Timeline {
    pub fn start_ms(&self) -> i64 {
        self.events.iter().map(|e| e.time_ms).min().unwrap_or(0)
    }
    pub fn end_ms(&self) -> i64 {
        self.events.iter().map(|e| e.time_ms).max().unwrap_or(0)
    }
}
