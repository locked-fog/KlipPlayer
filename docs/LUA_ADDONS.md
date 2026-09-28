# Lua 编译期 Addon

Lua addon 是 KLIP 的编译期事件生成器。它只在 `check`、`compile`、`play` 启动前的编译阶段运行，返回一组相对事件片段；KlipPlayer 会把这些片段展开成普通 `Event`。播放阶段仍然只执行扁平时间轴，不运行 Lua。

Lua addon 不是运行时脚本系统，不提供运行时控制流、协程、线程特效、变量系统或依赖注入。

## KLIP 语法

在 `.klip` 顶层声明 addon：

```klip
[meta addon="addons/textfx.lua"]
```

可以声明多个 addon。相对路径按 `.klip` 文件所在目录解析；绝对路径也允许。

在 `track` 或 `cue` 事件行中调用函数：

```klip
[time][func name key=value key2="value"]
```

示例：

```klip
[meta addon="addons/textfx.lua"]

[track lyrics cursor=main z=100 protect=on]
[00:00.000][func type text="Lua addon" interval=80ms]
[endtrack]
```

`[func ...]` 必须独占事件行，不能和普通命令、文本或 `[emit ...]` 混写。函数调用行本身会成为后续相对时间的基准，但函数返回事件的最大 `offset` 不会改变后续 `+duration` 的基准。

## Lua 文件结构

Addon 文件必须 `return` 一个 table：

```lua
return {
  id = "typing",
  version = "0.1.0",
  functions = {
    type = function(ctx)
      local text = ctx.string("text")
      local interval = ctx.duration("interval", "80ms")
      local events = {}

      for i, ch in ipairs(ctx.chars(text)) do
        events[#events + 1] = {
          offset = (i - 1) * interval,
          ops = {
            { op = "text", value = ch }
          }
        }
      end

      return events
    end
  }
}
```

`functions` 里的 key 是 KLIP 中 `[func name ...]` 的函数名。所有 addon 共享一个函数注册表，重复函数名会报错。

## ctx API

`ctx` 用于读取 KLIP 调用参数并做基础校验：

```lua
ctx.string(name[, default])
ctx.int(name[, default])
ctx.bool(name[, default])
ctx.color(name[, default])
ctx.duration(name[, default])
ctx.chars(text)
```

- `ctx.string` 返回字符串。
- `ctx.int` 返回整数。
- `ctx.bool` 接受 `true` / `false` / `on` / `off`。
- `ctx.color` 接受 6 位 RGB 或 `default`。
- `ctx.duration` 复用 KLIP duration 规则，支持 `80ms`、`80`、`1b`、`1/2b` 等；节拍值使用当前事件的 BPM 上下文。
- `ctx.chars` 按 Unicode code point 拆分字符串，适合中日文逐字效果。

## 返回 Event Schema

Lua 函数必须返回事件数组：

```lua
return {
  {
    offset = 0,
    cursor = "main",    -- 可选
    z = 100,            -- 可选
    protect = true,     -- 可选
    ops = {
      { op = "mv", row = 1, col = 1 },
      { op = "text", value = "hello" }
    }
  }
}
```

`offset` 单位是毫秒，缺省为 `0`。`cursor`、`z`、`protect` 缺省继承所在 track/cue。

支持的 op：

```lua
{ op = "mv", row = 1, col = 1 }
{ op = "text", value = "..." }
{ op = "color", value = "ff0000" }
{ op = "color", value = "default" }
{ op = "background", value = "101010" }
{ op = "background", value = "default" }
{ op = "style", name = "bold", enabled = true }
{ op = "style", name = "default" }
{ op = "space", count = 1 }
{ op = "newline" }
{ op = "cleanline" }
{ op = "clear" }
{ op = "hide" }
{ op = "show" }
```

## 完整示例

`type` 打字机：

```lua
return {
  functions = {
    type = function(ctx)
      local text = ctx.string("text")
      local interval = ctx.duration("interval", "80ms")
      local events = {}

      for i, ch in ipairs(ctx.chars(text)) do
        events[#events + 1] = {
          offset = (i - 1) * interval,
          ops = {
            { op = "text", value = ch }
          }
        }
      end

      return events
    end
  }
}
```

`flash` 闪烁：

```lua
return {
  functions = {
    flash = function(ctx)
      local text = ctx.string("text", "!")
      local color = ctx.color("color", "ff0055")
      local hold = ctx.duration("hold", "120ms")

      return {
        {
          offset = 0,
          z = 80,
          ops = {
            { op = "color", value = color },
            { op = "text", value = text }
          }
        },
        {
          offset = hold,
          z = 80,
          ops = {
            { op = "color", value = "default" },
            { op = "cleanline" }
          }
        }
      }
    end
  }
}
```

KLIP 调用：

```klip
[meta addon="addons/textfx.lua"]

[cue pulse cursor=fx z=20 protect=off]
[+0][mv 3,1]
[+0][func flash text="*" color=ffcc00 hold=160ms]
[endcue]

[track lyrics cursor=main z=100 protect=on]
[00:00.000][mv 1,1]
[+0][func type text="Lua addon demo" interval=70ms]
[+1200][emit pulse]
[endtrack]
```

## 错误处理

- `KLP6001`：addon 加载失败，例如文件不存在、顶层 Lua 执行失败、未返回合法 addon table。
- `KLP6002`：多个 addon 重复注册同名 function。
- `KLP6003`：调用了未定义 function。
- `KLP6004`：Lua function 执行失败，或返回的 event/op 结构非法。

调试建议：

- 先运行 `cargo run -- check file.klip` 验证解析和编译。
- 再运行 `cargo run -- compile file.klip` 查看展开后的事件表。
- Lua 函数尽量小步返回，先返回一个 `{ op = "text" }` 事件，再逐步增加 offset、颜色和样式。

## 安全模型

KlipPlayer 使用受限的 Lua 5.2 `mlua` 环境，只加载基础 `table`、`string`、`math` 能力和应用注入的 `ctx`。旧版 Kotlin/LuaJ 实现保留在 `legacy/kotlin/`。

以下能力不开放给 Lua：

```text
io
os
package
debug
luajava
require
loadfile
dofile
load
loadstring
```

这只是受限执行环境，不是操作系统级安全沙箱。不要加载未知来源的 addon；把 addon 当作可信项目代码处理。
