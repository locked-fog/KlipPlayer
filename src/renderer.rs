use std::collections::HashMap;
use std::io::{self, Write};

use crate::model::{Event, Op};

const SYNC_START: &str = "\x1b[?2026h";
const SYNC_END: &str = "\x1b[?2026l";
const UNPROTECTED: i32 = -1;

#[derive(Clone, Default, PartialEq, Eq)]
struct Style {
    foreground: Option<String>,
    background: Option<String>,
    bold: bool,
    italic: bool,
    underline: bool,
    strikeline: bool,
}

#[derive(Clone)]
struct Cursor {
    row: usize,
    col: usize,
    style: Style,
}

impl Default for Cursor {
    fn default() -> Self {
        Self {
            row: 1,
            col: 1,
            style: Style::default(),
        }
    }
}

struct ProtectionMask {
    width: usize,
    height: usize,
    cells: Vec<i32>,
}

impl ProtectionMask {
    fn new(width: usize, height: usize, len: usize) -> Self {
        Self {
            width,
            height,
            cells: vec![UNPROTECTED; len],
        }
    }

    fn index(&self, row: usize, col: usize) -> Option<usize> {
        if row == 0 || row > self.height || col == 0 || col > self.width {
            return None;
        }
        Some((row - 1) * self.width + col - 1)
    }

    fn can_write(&self, row: usize, col: usize, z: i32) -> bool {
        self.index(row, col)
            .is_some_and(|index| self.cells[index] == UNPROTECTED || z >= self.cells[index])
    }

    fn can_write_cells(&self, row: usize, col: usize, display_width: usize, z: i32) -> bool {
        (0..display_width).all(|offset| {
            col.checked_add(offset)
                .is_some_and(|col| self.can_write(row, col, z))
        })
    }

    fn mark(&mut self, row: usize, col: usize, display_width: usize, z: i32) {
        for offset in 0..display_width {
            if let Some(index) = col.checked_add(offset).and_then(|col| self.index(row, col)) {
                self.cells[index] = z;
            }
        }
    }

    fn clear(&mut self, row: usize, col: usize, z: i32) -> bool {
        if !self.can_write(row, col, z) {
            return false;
        }
        if let Some(index) = self.index(row, col) {
            self.cells[index] = UNPROTECTED;
        }
        true
    }
}

fn display_width(code_point: char) -> usize {
    let c = code_point as u32;
    if c == 0 || c < 32 || (0x7f..=0x9f).contains(&c) {
        return 0;
    }
    if (0x0300..=0x036f).contains(&c)
        || (0x1ab0..=0x1aff).contains(&c)
        || (0x1dc0..=0x1dff).contains(&c)
        || (0x20d0..=0x20ff).contains(&c)
        || (0xfe20..=0xfe2f).contains(&c)
    {
        return 0;
    }
    if (0x1100..=0x115f).contains(&c)
        || (0x2329..=0x232a).contains(&c)
        || (0x2e80..=0xa4cf).contains(&c)
        || (0xac00..=0xd7a3).contains(&c)
        || (0xf900..=0xfaff).contains(&c)
        || (0xfe10..=0xfe19).contains(&c)
        || (0xfe30..=0xfe6f).contains(&c)
        || (0xff00..=0xff60).contains(&c)
        || (0xffe0..=0xffe6).contains(&c)
        || (0x20000..=0x3fffd).contains(&c)
        || (0x1f000..=0x1faff).contains(&c)
    {
        return 2;
    }
    1
}

pub struct TerminalRenderer<W: Write> {
    width: usize,
    height: usize,
    out: W,
    pending: String,
    mask: ProtectionMask,
    touched: Vec<bool>,
    dirty: Vec<bool>,
    cursors: HashMap<String, Cursor>,
    physical_style: Option<Style>,
    min_row: usize,
    max_row: usize,
    min_col: usize,
    max_col: usize,
    synchronized_output: bool,
}

impl<W: Write> TerminalRenderer<W> {
    pub fn new(width: usize, height: usize, out: W) -> io::Result<Self> {
        let len = width
            .checked_mul(height)
            .filter(|length| *length <= 4_000_000)
            .ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "terminal canvas is too large")
            })?;
        if width == 0 || height == 0 {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "terminal canvas must be positive",
            ));
        }
        Ok(Self {
            width,
            height,
            out,
            pending: String::new(),
            mask: ProtectionMask::new(width, height, len),
            touched: vec![true; len],
            dirty: vec![true; len],
            cursors: HashMap::new(),
            physical_style: None,
            min_row: 1,
            max_row: height,
            min_col: 1,
            max_col: width,
            synchronized_output: true,
        })
    }

    pub fn render(&mut self, event: &Event) -> io::Result<()> {
        let mut cursor = self.cursors.remove(&event.cursor).unwrap_or_default();
        for op in &event.ops {
            match op {
                Op::Move(row, col) => {
                    cursor.row = *row;
                    cursor.col = *col;
                }
                Op::Foreground(rgb) => cursor.style.foreground = rgb.clone(),
                Op::Background(rgb) => cursor.style.background = rgb.clone(),
                Op::Style(name, enabled) => match name.as_deref() {
                    None => {
                        cursor.style.bold = false;
                        cursor.style.italic = false;
                        cursor.style.underline = false;
                        cursor.style.strikeline = false;
                    }
                    Some("bold") => cursor.style.bold = enabled == &Some(true),
                    Some("italic") => cursor.style.italic = enabled == &Some(true),
                    Some("underline") => cursor.style.underline = enabled == &Some(true),
                    Some("strikeline") => cursor.style.strikeline = enabled == &Some(true),
                    _ => (),
                },
                Op::Text(text) => {
                    for ch in text.chars() {
                        let width = display_width(ch);
                        if width > 0 {
                            self.write_cells(&mut cursor, &ch.to_string(), width, event);
                        }
                    }
                }
                Op::Space(count) => {
                    for _ in 0..*count {
                        self.write_cells(&mut cursor, " ", 1, event);
                    }
                }
                Op::Newline => {
                    cursor.row = cursor.row.saturating_add(1);
                    cursor.col = 1;
                }
                Op::CleanLine => self.clean_line(cursor.row, event.z),
                Op::Clear => self.clear(event.z),
                Op::Hide => self.pending.push_str("\x1b[?25l"),
                Op::Show => self.pending.push_str("\x1b[?25h"),
                Op::FunctionCall(..) => {
                    return Err(io::Error::other(
                        "FunctionCall must be expanded before rendering",
                    ));
                }
            }
        }
        self.cursors.insert(event.cursor.clone(), cursor);
        Ok(())
    }

    fn write_cells(
        &mut self,
        cursor: &mut Cursor,
        text: &str,
        display_width: usize,
        event: &Event,
    ) {
        if self
            .mask
            .can_write_cells(cursor.row, cursor.col, display_width, event.z)
        {
            self.ensure_style(&cursor.style);
            self.move_physical(cursor.row, cursor.col);
            self.pending.push_str(text);
            self.mark_touched(cursor.row, cursor.col, display_width, text, &cursor.style);
            if event.protect {
                self.mask
                    .mark(cursor.row, cursor.col, display_width, event.z);
            }
        }
        cursor.col = cursor.col.saturating_add(display_width);
    }

    fn mark_touched(
        &mut self,
        row: usize,
        col: usize,
        display_width: usize,
        text: &str,
        style: &Style,
    ) {
        let dirty = text != " " || style != &Style::default();
        for offset in 0..display_width {
            let cell_col = col.saturating_add(offset);
            if let Some(index) = self.mask.index(row, cell_col) {
                self.touched[index] = true;
                self.dirty[index] = dirty;
                self.min_row = self.min_row.min(row);
                self.max_row = self.max_row.max(row);
                self.min_col = self.min_col.min(cell_col);
                self.max_col = self.max_col.max(cell_col);
            }
        }
    }

    fn clean_line(&mut self, row: usize, z: i32) {
        if row == 0 || row > self.height || self.max_row == 0 {
            return;
        }
        self.clear_touched(row, row, 1, self.width, z);
    }

    fn clear(&mut self, z: i32) {
        if self.max_row == 0 {
            return;
        }
        self.clear_touched(self.min_row, self.max_row, self.min_col, self.max_col, z);
    }

    fn clear_touched(
        &mut self,
        row_start: usize,
        row_end: usize,
        col_start: usize,
        col_end: usize,
        z: i32,
    ) {
        let mut changed = false;
        for row in row_start..=row_end {
            let mut run_start = 0;
            let mut last_dirty = 0;
            for col in col_start..=col_end {
                let Some(index) = self.mask.index(row, col) else {
                    continue;
                };
                if !self.touched[index] {
                    self.flush_run(row, &mut run_start, &mut last_dirty);
                    continue;
                }
                if self.mask.clear(row, col, z) {
                    let was_dirty = self.dirty[index];
                    self.touched[index] = false;
                    self.dirty[index] = false;
                    changed = true;
                    if was_dirty {
                        if run_start == 0 {
                            run_start = col;
                        }
                        last_dirty = col;
                    }
                } else {
                    self.flush_run(row, &mut run_start, &mut last_dirty);
                }
            }
            self.flush_run(row, &mut run_start, &mut last_dirty);
        }
        if changed {
            self.recompute_bounds();
        }
    }

    fn flush_run(&mut self, row: usize, run_start: &mut usize, last_dirty: &mut usize) {
        if *run_start != 0 && *last_dirty != 0 {
            self.erase_run(row, *run_start, *last_dirty - *run_start + 1);
        }
        *run_start = 0;
        *last_dirty = 0;
    }

    fn erase_run(&mut self, row: usize, col: usize, count: usize) {
        self.ensure_style(&Style::default());
        self.move_physical(row, col);
        self.pending.extend(std::iter::repeat_n(' ', count));
    }

    fn recompute_bounds(&mut self) {
        self.min_row = self.height + 1;
        self.max_row = 0;
        self.min_col = self.width + 1;
        self.max_col = 0;
        for row in 1..=self.height {
            for col in 1..=self.width {
                let index = (row - 1) * self.width + col - 1;
                if self.touched[index] {
                    self.min_row = self.min_row.min(row);
                    self.max_row = self.max_row.max(row);
                    self.min_col = self.min_col.min(col);
                    self.max_col = self.max_col.max(col);
                }
            }
        }
    }

    fn move_physical(&mut self, row: usize, col: usize) {
        if row > 0 && row <= self.height && col > 0 && col <= self.width {
            self.pending.push_str(&format!("\x1b[{row};{col}H"));
        }
    }

    fn rgb_ansi(prefix: u8, value: &str) -> String {
        let r = u8::from_str_radix(&value[0..2], 16).unwrap_or(0);
        let g = u8::from_str_radix(&value[2..4], 16).unwrap_or(0);
        let b = u8::from_str_radix(&value[4..6], 16).unwrap_or(0);
        format!("\x1b[{prefix};2;{r};{g};{b}m")
    }

    fn ensure_style(&mut self, target: &Style) {
        if self.physical_style.as_ref() == Some(target) {
            return;
        }
        let current = self.physical_style.as_ref();
        if current.is_none_or(|s| s.foreground != target.foreground) {
            self.pending.push_str(
                &target
                    .foreground
                    .as_deref()
                    .map(|v| Self::rgb_ansi(38, v))
                    .unwrap_or_else(|| "\x1b[39m".into()),
            );
        }
        if current.is_none_or(|s| s.background != target.background) {
            self.pending.push_str(
                &target
                    .background
                    .as_deref()
                    .map(|v| Self::rgb_ansi(48, v))
                    .unwrap_or_else(|| "\x1b[49m".into()),
            );
        }
        for (old, new, on, off) in [
            (current.is_some_and(|s| s.bold), target.bold, 1, 22),
            (current.is_some_and(|s| s.italic), target.italic, 3, 23),
            (
                current.is_some_and(|s| s.underline),
                target.underline,
                4,
                24,
            ),
            (
                current.is_some_and(|s| s.strikeline),
                target.strikeline,
                9,
                29,
            ),
        ] {
            if current.is_none() || old != new {
                self.pending
                    .push_str(&format!("\x1b[{}m", if new { on } else { off }));
            }
        }
        self.physical_style = Some(target.clone());
    }

    pub fn flush(&mut self) -> io::Result<()> {
        if self.pending.is_empty() {
            return Ok(());
        }
        if self.synchronized_output {
            self.out.write_all(SYNC_START.as_bytes())?;
        }
        self.out.write_all(self.pending.as_bytes())?;
        if self.synchronized_output {
            self.out.write_all(SYNC_END.as_bytes())?;
        }
        self.pending.clear();
        self.out.flush()
    }

    pub fn restore(&mut self) -> io::Result<()> {
        let first = self.flush();
        self.pending.push_str("\x1b[0m\x1b[39m\x1b[49m\x1b[?25h\n");
        self.physical_style = Some(Style::default());
        let second = self.flush();
        first.and(second)
    }
}
