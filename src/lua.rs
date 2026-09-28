use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

use mlua::{Function, Lua, LuaOptions, StdLib, Table, Value};

use crate::compiler::parse_duration;
use crate::model::{Document, KlipError, Op, Result};

fn error(doc: &Document, line: usize, code: &'static str, detail: impl Into<String>) -> KlipError {
    KlipError::new(code, &doc.file, line, detail)
}

fn valid_identifier(value: &str) -> bool {
    let mut chars = value.chars();
    matches!(chars.next(), Some('a'..='z' | 'A'..='Z' | '_'))
        && chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

fn lua_failure(detail: impl Into<String>) -> mlua::Error {
    mlua::Error::RuntimeError(detail.into())
}

struct Registered {
    lua: Lua,
    function: Function,
}

pub struct LuaRegistry {
    functions: HashMap<String, Registered>,
}

pub struct GeneratedEvent {
    pub offset_ms: i64,
    pub cursor: Option<String>,
    pub z: Option<i32>,
    pub protect: Option<bool>,
    pub ops: Vec<Op>,
}

impl LuaRegistry {
    pub fn load(doc: &Document) -> Result<Self> {
        let mut functions = HashMap::new();
        for (addon, line) in &doc.meta.addons {
            let path = resolve_path(&doc.file, addon);
            let script = fs::read_to_string(&path).map_err(|e| {
                error(
                    doc,
                    *line,
                    "KLP6001",
                    format!("addon 加载失败: {} ({e})", path.display()),
                )
            })?;
            let lua = Lua::new_with(
                StdLib::TABLE | StdLib::STRING | StdLib::MATH,
                LuaOptions::default(),
            )
            .map_err(|e| {
                error(
                    doc,
                    *line,
                    "KLP6001",
                    format!("addon 加载失败: {} ({e})", path.display()),
                )
            })?;
            for blocked in [
                "io",
                "os",
                "package",
                "debug",
                "luajava",
                "require",
                "loadfile",
                "dofile",
                "load",
                "loadstring",
            ] {
                lua.globals()
                    .set(blocked, Value::Nil)
                    .map_err(|e| error(doc, *line, "KLP6001", e.to_string()))?;
            }
            let result: Value = lua
                .load(&script)
                .set_name(format!("@{}", path.display()))
                .eval()
                .map_err(|e| {
                    error(
                        doc,
                        *line,
                        "KLP6001",
                        format!("addon 加载失败: {} ({e})", path.display()),
                    )
                })?;
            let Value::Table(table) = result else {
                return Err(error(
                    doc,
                    *line,
                    "KLP6001",
                    format!("addon 必须 return table: {}", path.display()),
                ));
            };
            let values: Value = table
                .get("functions")
                .map_err(|e| error(doc, *line, "KLP6001", e.to_string()))?;
            let Value::Table(registry) = values else {
                return Err(error(
                    doc,
                    *line,
                    "KLP6001",
                    format!("addon 缺少 functions 表: {addon}"),
                ));
            };
            for pair in registry.pairs::<Value, Value>() {
                let (key, value) = pair.map_err(|e| error(doc, *line, "KLP6001", e.to_string()))?;
                let Value::String(key) = key else {
                    return Err(error(
                        doc,
                        *line,
                        "KLP6001",
                        format!("addon function 名称必须是字符串: {addon}"),
                    ));
                };
                let name = key
                    .to_str()
                    .map_err(|e| error(doc, *line, "KLP6001", e.to_string()))?
                    .to_string();
                if !valid_identifier(&name) {
                    return Err(error(
                        doc,
                        *line,
                        "KLP6001",
                        format!("非法 addon function 名称: {name}"),
                    ));
                }
                let Value::Function(function) = value else {
                    return Err(error(
                        doc,
                        *line,
                        "KLP6001",
                        format!("addon function 必须是函数: {name}"),
                    ));
                };
                if functions.contains_key(&name) {
                    return Err(error(
                        doc,
                        *line,
                        "KLP6002",
                        format!("重复注册 function: {name}"),
                    ));
                }
                functions.insert(
                    name,
                    Registered {
                        lua: lua.clone(),
                        function,
                    },
                );
            }
        }
        Ok(Self { functions })
    }

    pub fn expand(
        &self,
        doc: &Document,
        name: &str,
        args: &[(String, String)],
        line: usize,
        bpm: Option<f64>,
    ) -> Result<Vec<GeneratedEvent>> {
        let registered = self
            .functions
            .get(name)
            .ok_or_else(|| error(doc, line, "KLP6003", format!("未定义 function: {name}")))?;
        let ctx = make_context(&registered.lua, doc, args, line, bpm).map_err(|e| {
            error(
                doc,
                line,
                "KLP6004",
                format!("Lua function {name} 执行失败: {e}"),
            )
        })?;
        let result: Value = registered.function.call(ctx).map_err(|e| {
            error(
                doc,
                line,
                "KLP6004",
                format!("Lua function {name} 执行失败: {e}"),
            )
        })?;
        parse_events(result, name).map_err(|e| error(doc, line, "KLP6004", e))
    }
}

fn resolve_path(document: &str, addon: &str) -> PathBuf {
    let raw = Path::new(addon);
    if raw.is_absolute() {
        raw.to_path_buf()
    } else {
        Path::new(document)
            .parent()
            .unwrap_or_else(|| Path::new(""))
            .join(raw)
    }
}

fn argument(
    args: &HashMap<String, String>,
    name: &str,
    default: Option<String>,
) -> mlua::Result<String> {
    args.get(name)
        .cloned()
        .or(default)
        .ok_or_else(|| lua_failure(format!("缺少参数: {name}")))
}

fn valid_color(value: &str) -> bool {
    value == "default" || (value.len() == 6 && value.bytes().all(|b| b.is_ascii_hexdigit()))
}

fn make_context(
    lua: &Lua,
    doc: &Document,
    args: &[(String, String)],
    line: usize,
    bpm: Option<f64>,
) -> mlua::Result<Table> {
    let ctx = lua.create_table()?;
    let args: HashMap<String, String> = args.iter().cloned().collect();
    let string_args = args.clone();
    ctx.set(
        "string",
        lua.create_function(move |_, (name, default): (String, Option<String>)| {
            argument(&string_args, &name, default)
        })?,
    )?;
    let int_args = args.clone();
    ctx.set(
        "int",
        lua.create_function(move |_, (name, default): (String, Option<String>)| {
            let raw = argument(&int_args, &name, default)?;
            raw.parse::<i32>()
                .map_err(|_| lua_failure(format!("参数必须是整数: {name}")))
        })?,
    )?;
    let bool_args = args.clone();
    ctx.set(
        "bool",
        lua.create_function(move |_, (name, default): (String, Option<String>)| {
            let raw = argument(&bool_args, &name, default)?;
            match raw.as_str() {
                "true" | "on" => Ok(true),
                "false" | "off" => Ok(false),
                _ => Err(lua_failure(format!("参数必须是布尔值: {name}"))),
            }
        })?,
    )?;
    let color_args = args.clone();
    ctx.set(
        "color",
        lua.create_function(move |_, (name, default): (String, Option<String>)| {
            let raw = argument(&color_args, &name, default)?;
            if !valid_color(&raw) {
                return Err(lua_failure(format!("参数颜色非法: {name}: {raw}")));
            }
            Ok(raw.to_ascii_lowercase())
        })?,
    )?;
    let duration_args = args;
    let duration_doc = doc.clone();
    ctx.set(
        "duration",
        lua.create_function(move |_, (name, default): (String, Option<String>)| {
            let raw = argument(&duration_args, &name, default)?;
            parse_duration(&raw, bpm, &duration_doc, line)
                .map(|v| v as f64)
                .map_err(|e| lua_failure(e.to_string()))
        })?,
    )?;
    ctx.set(
        "chars",
        lua.create_function(|lua, text: String| {
            let table = lua.create_table()?;
            for (index, c) in text.chars().enumerate() {
                table.set(index + 1, c.to_string())?;
            }
            Ok(table)
        })?,
    )?;
    Ok(ctx)
}

fn required_string(value: Value, label: &str) -> std::result::Result<String, String> {
    match value {
        Value::String(s) => s
            .to_str()
            .map(|s| s.to_string())
            .map_err(|_| format!("{label} 必须是字符串")),
        Value::Nil => Err(format!("{label} 缺失")),
        _ => Err(format!("{label} 必须是字符串")),
    }
}

fn required_i64(value: Value, label: &str) -> std::result::Result<i64, String> {
    match value {
        Value::Integer(v) => Ok(v),
        Value::Number(v)
            if v.is_finite() && v.fract() == 0.0 && v >= i64::MIN as f64 && v < i64::MAX as f64 =>
        {
            Ok(v as i64)
        }
        Value::Nil => Err(format!("{label} 缺失")),
        _ => Err(format!("{label} 必须是整数")),
    }
}

fn required_bool(value: Value, label: &str) -> std::result::Result<bool, String> {
    match value {
        Value::Boolean(v) => Ok(v),
        Value::String(s) => match s
            .to_str()
            .map_err(|_| format!("{label} 必须是布尔值"))?
            .as_ref()
        {
            "true" | "on" => Ok(true),
            "false" | "off" => Ok(false),
            _ => Err(format!("{label} 必须是布尔值")),
        },
        _ => Err(format!("{label} 必须是布尔值")),
    }
}

fn get(table: &Table, key: &str) -> std::result::Result<Value, String> {
    table.get(key).map_err(|e| e.to_string())
}

fn parse_color(value: Value, label: &str) -> std::result::Result<Option<String>, String> {
    let value = required_string(value, label)?;
    if value == "default" {
        return Ok(None);
    }
    if !valid_color(&value) {
        return Err(format!("{label}: {value}"));
    }
    Ok(Some(value.to_ascii_lowercase()))
}

fn parse_ops(table: Table, event_index: usize) -> std::result::Result<Vec<Op>, String> {
    let mut ops = Vec::new();
    for (index, item) in table.sequence_values::<Value>().enumerate() {
        let item = item.map_err(|e| e.to_string())?;
        let Value::Table(op) = item else {
            return Err(format!(
                "event #{} op #{} 必须是 table",
                event_index,
                index + 1
            ));
        };
        let label = format!("event #{} op #{}", event_index, index + 1);
        let kind = required_string(get(&op, "op")?, &format!("{label} op"))?;
        let parsed = match kind.as_str() {
            "mv" => {
                let row = required_i64(get(&op, "row")?, &format!("{label} row"))?;
                let col = required_i64(get(&op, "col")?, &format!("{label} col"))?;
                if row <= 0 || col <= 0 {
                    return Err(format!("{label} row/col 必须从 1 开始"));
                }
                Op::Move(row as usize, col as usize)
            }
            "text" => Op::Text(required_string(
                get(&op, "value")?,
                &format!("{label} value"),
            )?),
            "color" => Op::Foreground(parse_color(get(&op, "value")?, "颜色非法")?),
            "background" => Op::Background(parse_color(get(&op, "value")?, "背景色非法")?),
            "style" => {
                let name = required_string(get(&op, "name")?, &format!("{label} name"))?;
                if name == "default" {
                    Op::Style(None, None)
                } else {
                    if !matches!(
                        name.as_str(),
                        "bold" | "italic" | "underline" | "strikeline"
                    ) {
                        return Err(format!("未知 style: {name}"));
                    }
                    let enabled = required_bool(get(&op, "enabled")?, &format!("{label} enabled"))?;
                    Op::Style(Some(name), Some(enabled))
                }
            }
            "space" => {
                let count = required_i64(get(&op, "count")?, &format!("{label} count"))?;
                if count < 0 {
                    return Err(format!("{label} count 不能为负数"));
                }
                Op::Space(count as usize)
            }
            "newline" => Op::Newline,
            "cleanline" => Op::CleanLine,
            "clear" => Op::Clear,
            "hide" => Op::Hide,
            "show" => Op::Show,
            _ => return Err(format!("未知 Lua op: {kind}")),
        };
        ops.push(parsed);
    }
    if ops.is_empty() {
        return Err(format!("event #{event_index} ops 不能为空"));
    }
    Ok(ops)
}

fn parse_events(value: Value, function: &str) -> std::result::Result<Vec<GeneratedEvent>, String> {
    let Value::Table(table) = value else {
        return Err(format!("Lua function {function} 必须返回事件数组"));
    };
    let mut events = Vec::new();
    for (index, item) in table.clone().sequence_values::<Value>().enumerate() {
        let item = item.map_err(|e| e.to_string())?;
        let Value::Table(event) = item else {
            return Err(format!("event #{} 必须是 table", index + 1));
        };
        let label = format!("event #{}", index + 1);
        let offset_ms = match get(&event, "offset")? {
            Value::Nil => 0,
            value => required_i64(value, &format!("{label} offset"))?,
        };
        if offset_ms < 0 {
            return Err(format!("{label} offset 不能为负数"));
        }
        let cursor = match get(&event, "cursor")? {
            Value::Nil => None,
            value => {
                let cursor = required_string(value, &format!("{label} cursor"))?;
                if !valid_identifier(&cursor) {
                    return Err(format!("{label} cursor 非法: {cursor}"));
                }
                Some(cursor)
            }
        };
        let z = match get(&event, "z")? {
            Value::Nil => None,
            value => {
                let z = required_i64(value, &format!("{label} z"))?;
                if z < 0 || z > i32::MAX as i64 {
                    return Err(format!("{label} z 必须是非负整数"));
                }
                Some(z as i32)
            }
        };
        let protect = match get(&event, "protect")? {
            Value::Nil => None,
            value => Some(required_bool(value, &format!("{label} protect"))?),
        };
        let Value::Table(ops) = get(&event, "ops")? else {
            return Err(format!("{label} ops 必须是 table"));
        };
        events.push(GeneratedEvent {
            offset_ms,
            cursor,
            z,
            protect,
            ops: parse_ops(ops, index + 1)?,
        });
    }
    if events.is_empty() && !matches!(get(&table, "ops")?, Value::Nil) {
        return Err(format!(
            "Lua function {function} 必须返回事件数组，而不是单个事件"
        ));
    }
    Ok(events)
}
