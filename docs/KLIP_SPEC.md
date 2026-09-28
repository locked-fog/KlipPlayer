# KLIP 脚本语法规范

语法基线：v1.1.1（与旧版 KLIPlayer 兼容）
适用项目：KlipPlayer
文件后缀：`.klip`
编码：UTF-8

## 1. 总览

KLIP 是 KlipPlayer 使用的终端演出脚本格式。

KlipPlayer 会读取 `.klip` 文件，将其中的歌词、文字、颜色、位置、节拍、特效、Z 轴保护等内容编译为一张全局时间轴事件表，然后按音频播放时间同步输出到终端。

KLIP 的核心思想是：

脚本阶段可以自由分块书写。
编译阶段展开 track、cue、emit、loop 和 Lua 编译期 addon 函数。
运行阶段只执行一张扁平事件表。
所谓“协程式特效”不是运行时协程，而是 `cue + emit` 的编译期展开。

KLIP 不是通用编程语言。
KLIP 不提供变量、条件判断、随机数、KTS 脚本、真实协程或运行时插件系统。
KLIP 允许通过 Lua addon 在编译期生成事件；Lua 不会在播放阶段运行。

------

## 2. 基础概念

### 2.1 文件

`.klip` 文件是 UTF-8 文本文件。

推荐结构：

```klip
[meta music="song.mp3"]
[meta width=160]
[meta height=40]
[meta addon="addons/textfx.lua"]

[anchor intro 00:00.000 bpm=180]
[anchor chorus 00:58.430 bpm=180]

[cue rain cursor=rain z=20 protect=off]
  ...
[endcue]

[track fx cursor=fx z=20 protect=off]
  ...
[endtrack]

[track lyrics cursor=main z=100 protect=on]
  ...
[endtrack]
```

### 2.2 标签

KLIP 使用方括号表示标签：

```klip
[meta music="song.mp3"]
[00:01.000]
[+500]
[intro+4b]
[mv 10,20]
[color ff0000]
```

标签分为：

- 顶层标签：`meta`、`anchor`
- 块标签：`track`、`cue`、`loop`
- 时间标签：`[00:01.000]`、`[+500]`、`[intro+4b]`
- 命令标签：`[mv 10,20]`、`[color ff0000]`
- 编译期标签：`[emit rain]`、`[func type text="abc"]`

### 2.3 注释

只有整行注释。

去除行首空白后，以 `//` 开头的行是注释。

```klip
// 这是注释
    // 这也是注释
```

不支持任意位置的行内注释。

原因：歌词、URL、模拟命令行内容中可能出现 `//`，如果把任意位置的 `//` 都当作注释，会误删文本。

下面这一行中的 `//` 不是注释，而是输出文本的一部分：

```klip
[00:01.000]https://github.com/locked-fog/CLIPlayer
```

------

## 3. 文本与空白规则

### 3.1 标签外文本

标签外文本默认作为输出文本。

```klip
[00:01.000]熱異常
```

表示在 `00:01.000` 输出 `熱異常`。

### 3.2 行首缩进

行首缩进只用于脚本排版，不输出。

```klip
[track lyrics cursor=main z=100 protect=on]
  [00:01.000]熱異常
[endtrack]
```

其中事件行前面的两个空格不会输出。

### 3.3 标签之间的纯空白

两个标签之间如果只有空格或制表符，这些空白视为脚本排版空白，不输出。

```klip
[00:01.000]   [mv 10,20]   [color ff0000]熱異常
```

`[00:01.000]`、`[mv 10,20]`、`[color ff0000]` 之间的空白不输出。

### 3.4 文本段中的空白

如果某个标签后的文本段包含非空白字符，则该文本段按原样输出，包括文本段开头的空格。

```klip
[00:01.000][color ff0000] 熱異常
```

输出文本为：

```text
 熱異常
```

也就是说，`熱異常` 前面的空格会输出。

### 3.5 输出纯空格

如果需要明确输出空格，使用：

```klip
[space]
[space 4]
```

`[space]` 输出 1 个空格。
`[space 4]` 输出 4 个空格。

### 3.6 转义

标签外文本中，以下字符需要转义：

```text
\[  输出 [
\]  输出 ]
\\  输出 \
\n  输出换行字符
\t  输出制表符
```

推荐优先使用 `[newline]` 和 `[space n]` 控制排版，而不是在歌词文本里大量使用 `\n` 和 `\t`。

------

## 4. 标识符与基础值

### 4.1 标识符

用于 track、cue、anchor、cursor、Lua function 和 `[func ...]` 参数名。

格式：

```text
[A-Za-z_][A-Za-z0-9_-]*
```

合法：

```text
intro
chorus
main
fx
rain_1
warning-flash
```

非法：

```text
1intro
+rain
中文名
```

### 4.2 整数

十进制整数。

```text
0
1
20
100
```

用于：

- width
- height
- z
- row
- col
- loop 次数
- space 数量

### 4.3 布尔开关

KLIP 使用 `on` / `off`。

```klip
protect=on
protect=off
[style bold on]
[style bold off]
```

### 4.4 字符串

字符串使用双引号。

```klip
[meta music="path/to/music.mp3"]
[meta title="熱異常"]
```

字符串中支持：

```text
\"  输出 "
\\  输出 \
\n  输出换行
\t  输出制表符
```

### 4.5 颜色

颜色使用 6 位十六进制 RGB。

```klip
[color ff0000]
[background 101010]
```

不使用 `#` 前缀。

合法：

```text
ff0000
00ffcc
FFFFFF
```

特殊值：

```klip
[color default]
[background default]
```

------

## 5. Meta

Meta 用于声明全局信息。

语法：

```klip
[meta key=value]
```

推荐写在文件开头，但允许出现在顶层任意位置。

### 5.1 必须支持的 Meta

```klip
[meta music="song.mp3"]
[meta width=160]
[meta height=40]
[meta addon="addons/textfx.lua"]
```

`music`：音频文件路径。
`width`：终端画布宽度。
`height`：终端画布高度。
`addon`：Lua 编译期 addon 文件路径。可以声明多次；相对路径按 `.klip` 文件所在目录解析。

如果未声明：

```text
width = 160
height = 40
```

如果未声明 `music`，`check` 和 `compile` 可以正常执行，`play` 应报错或进入无音频播放模式，具体由实现决定，但必须在 CLI 输出中明确说明。

Lua addon 的完整语义见 `docs/LUA_ADDONS.md`。

------

## 6. Anchor 与对轴

Anchor 用于给音乐中的某个时间点命名，并绑定 BPM 上下文。

语法：

```klip
[anchor name mm:ss.mmm bpm=number]
```

示例：

```klip
[anchor intro 00:00.200 bpm=180]
[anchor chorus 00:58.430 bpm=180]
```

含义：

`intro` 是一个命名时间点，绝对时间为 `00:00.200`，从该时间点开始按 `180 BPM` 计算节拍。
`chorus` 是另一个命名时间点，绝对时间为 `00:58.430`，从该时间点开始按 `180 BPM` 计算节拍。

### 6.1 Anchor 名称

Anchor 名称必须是合法标识符。

```klip
[anchor intro 00:00.000 bpm=180]
```

### 6.2 BPM

BPM 必须是正数。

允许整数：

```klip
[anchor intro 00:00.000 bpm=180]
```

允许小数：

```klip
[anchor intro 00:00.000 bpm=178.5]
```

------

## 7. 时间语法

KLIP 中有三类时间：

- 绝对时间
- Anchor 时间
- 相对时间

时间标签直接写在方括号中。

### 7.1 绝对时间

格式：

```klip
[mm:ss.mmm]
```

示例：

```klip
[00:00.000]
[00:01.250]
[01:23.456]
```

要求：

- `mm` 是分钟，可以大于 59。
- `ss` 是秒，范围 00–59。
- `mmm` 是毫秒，必须为 3 位。

### 7.2 Anchor 时间

格式：

```klip
[anchorName]
[anchorName+duration]
[anchorName-duration]
```

示例：

```klip
[intro]intro的时间
[intro+4b]intro的时间+4拍，即intro的时间+(60000ms/bpm)*4，此处bpm在[anchor intro]中声明。
[intro+250]intro的时间+250ms
[chorus-2b]chorus的时间-2拍
[chorus+1/4b]chorus的时间+1/4拍
```

### 7.3 相对时间

格式：

```klip
[+duration]
```

示例：

```klip
[+500]
[+1b]
[+1/2b]
[+3/8b]
```

`[+500]` 表示相对上一事件延后 500 毫秒。例如：

```klip
[anchor intro 00:01.000 bpm=120]
[intro+100]intro+100ms，即00:01.100
[+200]相对上一时间+200ms，即00:01.300
[intro+50]intro+50ms。即00:01.050
[+4b]相对上一时间+4拍，即00:03.050
```

### 7.4 Duration

Duration 支持三种形式。

#### 毫秒

```klip
500
500ms
120
120ms
80
```

无单位数字默认表示毫秒。
也可以显式使用 `ms` 后缀，含义相同。

```klip
[+500]
[+500ms]
[intro+250]
[intro+250ms]
```

#### 普通节拍

```klip
1b
2b
0.5b
1.5b
```

`1b` 表示 1 拍。
`0.5b` 表示半拍。
`1.5b` 表示一拍半。

#### 分数节拍

```klip
1/2b
1/4b
3/8b
```

`1/2b` 表示半拍。
`1/4b` 表示四分之一拍。
`3/8b` 表示八分之三拍。

### 7.5 节拍换算

给定：

```klip
[anchor intro 00:00.000 bpm=180]
```

则：

```text
beatMs = 60000 / 180 = 333.333...
```

```text
[intro+1b]
```

约等于：

```text
00:00.333
```

```text
[intro+3b]
```

约等于：

```text
00:01.000
```

```text
[intro+1/2b]
```

约等于：

```text
00:00.167
```

最终时间统一转为 Long 毫秒。
建议使用四舍五入，而不是直接向下取整。

### 7.6 相对节拍的 BPM 上下文

相对时间 `[+1b]` 需要知道 BPM。

规则：

如果上一事件的时间来自某个 anchor 表达式，则继承该 anchor 的 BPM 上下文。

示例：

```klip
[track lyrics cursor=main z=100 protect=on]
  [intro+4b]第一句
  [+1b]第二句
[endtrack]
```

`[+1b]` 使用 `intro` 的 BPM。

如果上一事件是纯绝对时间，例如 `[00:01.000]`，且没有 BPM 上下文，则后续 `[+1b]` 是错误。

错误示例：

```klip
[track lyrics cursor=main z=100 protect=on]
  [00:01.000]第一句
  [+1b]第二句
[endtrack]
```

应报错：

```text
相对节拍缺少 BPM 上下文
```

------

## 8. Track

Track 是一组可自由书写的事件。

语法：

```klip
[track name cursor=cursorId z=number protect=on|off]
  ...
[endtrack]
```

`cursor`、`z`、`protect` 均为可选参数。
未声明时，`cursor` 默认等于 track 名称，`z` 默认为 `0`，`protect` 默认为 `off`。

示例：

```klip
[track lyrics cursor=main z=100 protect=on]
  [intro+4b][mv 10,20]第一句歌词
  [+1/2b][mv 11,20]第二句歌词
[endtrack]
```

### 8.1 Track 参数

#### name

Track 名称，仅用于调试和错误信息。

```klip
[track lyrics ...]
```

#### cursor

默认逻辑光标。

```klip
cursor=main
```

#### z

默认 Z 轴高度。

```klip
z=100
```

数字越大，越靠上。

#### protect

是否保护输出内容。

```klip
protect=on
protect=off
```

### 8.2 推荐 Z 轴约定

```text
z=100  主歌词，protect=on
z=80   重要提示、警告文字
z=20   普通特效
z=0    背景装饰
```

### 8.3 Track 内事件

Track 内事件行必须以时间标签开头。

```klip
[00:01.000][mv 10,20]hello
[intro+4b][mv 11,20]world
[+500][mv 12,20]next
```

### 8.4 Track 乱序书写

Track 内允许乱序书写。

```klip
[track lyrics cursor=main z=100 protect=on]
  [00:10.000]A
  [00:05.000]B
  [+500]C
[endtrack]
```

含义：

- A 在 10.000 秒。
- B 在 5.000 秒。
- C 相对源码上一条事件 B，因此在 5.500 秒。
- 最终播放顺序是 B、C、A。

重点：

相对时间基于“同一 track 中源码上一条事件”，不是最终排序后的上一事件。

### 8.5 Track 内 emit

Track 内可以使用 `[emit cueName]`。

```klip
[track fx cursor=fx z=20 protect=off]
  [chorus-1b][emit flash]
[endtrack]
```

`emit` 会在编译期展开 cue。

### 8.6 Track 内 func

Track 内可以使用 Lua 编译期函数：

```klip
[time][func name key=value key2="value"]
```

`func` 调用会在编译期展开为普通事件。调用行的时间会成为后续相对时间的基准，Lua 返回事件的最大 `offset` 不影响后续 `+duration`。

`func` 行必须独占事件行，不能和普通命令、文本或 `emit` 混写。

------

## 9. Cue

Cue 是可复用的局部时间线，主要用于特效。

Cue 不是运行时协程。
Cue 会在编译期由 `[emit cueName]` 展开为普通事件。

语法：

```klip
[cue name cursor=cursorId z=number protect=on|off]
  ...
[endcue]
```

`cursor`、`z`、`protect` 均为可选参数。
未声明时，`cursor` 默认等于 cue 名称，`z` 默认为 `0`，`protect` 默认为 `off`。

示例：

```klip
[cue rain cursor=rain z=20 protect=off]
  [+0][mv 1,20]|
  [+80][mv 1,20][space][mv 2,20]|
  [+80][mv 2,20][space][mv 3,20]|
[endcue]
```

### 9.1 Cue 参数

Cue 参数与 track 相同：

```klip
[cue rain cursor=rain z=20 protect=off]
```

包括：

- name
- cursor
- z
- protect

### 9.2 Cue 内时间规则

Cue 内只允许相对时间。

合法：

```klip
[+0]
[+80]
[+1/2b]
```

非法：

```klip
[00:01.000]
[intro+4b]
[chorus]
```

Cue 的局部时间从 0 开始。

建议 cue 第一条事件使用：

```klip
[+0]
```

### 9.3 Cue 不允许嵌套 emit

v1.1.1 中，cue 内不允许 `[emit ...]`。

原因：避免递归展开、循环引用和复杂依赖分析。

### 9.4 Cue 内 func

Cue 内可以使用 Lua 编译期函数：

```klip
[+0][func sparkle count=8]
```

函数返回事件的 `offset` 以该 cue 局部事件时间为基准。返回事件默认继承 cue 的 `cursor`、`z`、`protect`，也可以在 Lua 返回表中覆盖。

------

## 10. Emit

Emit 用于在某个时间点触发 cue。

语法：

```klip
[time][emit cueName]
```

示例：

```klip
[track fx cursor=fx z=20 protect=off]
  [chorus-2b][emit rain]
  [chorus-1b][emit rain]
  [chorus][emit flash]
[endtrack]
```

### 10.1 Emit 展开规则

给定：

```klip
[cue flash cursor=warn z=80 protect=off]
  [+0][mv 5,20][color ff0055]WARNING
  [+120][cleanline]
[endcue]
```

以及：

```klip
[chorus][emit flash]
```

如果 `chorus = 00:10.000`，则编译后得到：

```text
00:10.000 [mv 5,20][color ff0055]WARNING
00:10.120 [cleanline]
```

### 10.2 Emit 不是运行时协程

Emit 不创建线程。
Emit 不创建运行时协程。
Emit 不在运行时调度。
Emit 只在编译期复制 cue 的事件。

### 10.3 Emit 行限制

v1.1.1 中，`emit` 行应只包含：

```klip
[time][emit cueName]
```

不建议混写：

```klip
[time][emit rain][color ff0000]text
```

实现可以直接报错。

如果同一时间要触发多个 cue，写多行：

```klip
[chorus][emit rain]
[chorus][emit flash]
```

------

## 11. Loop

Loop 用于重复局部事件。

语法：

```klip
[loop n]
  ...
[endloop]
```

示例：

```klip
[cue rain cursor=rain z=20 protect=off]
  [loop 3]
    [+0][mv 1,10]|
    [+80][mv 1,10][space][mv 2,10]|
    [+80][mv 2,10][space][mv 3,10]|
    [+80][mv 3,10][space]
  [endloop]
[endcue]
```

### 11.1 Loop 位置

v1.1.1 中，loop 只允许出现在 cue 内。

不允许在 track 中使用 loop。

原因：loop 的主要用途是特效复用；track 中直接乱序写歌词和 emit 更清晰。

### 11.2 Loop 次数

`n` 必须是正整数。

合法：

```klip
[loop 4]
```

非法：

```klip
[loop 0]
[loop -1]
[loop count]
```

### 11.3 Loop 展开规则

Loop 是编译期展开。

每一轮的起点是上一轮结束时间。

示例：

```klip
[loop 2]
  [+100]A
  [+200]B
[endloop]
```

展开为：

```text
+100 A
+300 B
+400 A
+600 B
```

解释：

第一轮：

- A 在 +100
- B 在 +300

第二轮从第一轮结束时间 +300 开始：

- A 在 +400
- B 在 +600

### 11.4 Loop 内时间

Loop 内推荐只使用相对时间。
在 cue 内本来就只允许相对时间，因此 loop 内自然也只允许相对时间。

------

## 12. Cursor

Cursor 是逻辑光标。

每个 track / cue 通过 `cursor=` 指定默认 cursor。
每个逻辑 cursor 独立保存位置和文本状态；文本状态包括前景色、背景色和 style。
`color`、`background`、`style` 命令只更新当前逻辑 cursor 的文本状态。
渲染器在该 cursor 实际输出文本或 `[space]` 空格前，必须先把物理终端 ANSI 状态同步到该 cursor 的文本状态，避免不同 cursor 之间互相污染颜色或样式。
`[cleanline]` 和 `[clear]` 输出的擦除空格必须使用默认终端样式，不继承 cursor 的背景色、下划线等文本状态。

```klip
[track lyrics cursor=main z=100 protect=on]
[cue rain cursor=rain z=20 protect=off]
```

------

## 13. 输出命令

输出命令写在时间标签之后。

```klip
[00:01.000][mv 10,20][color ff0000]hello
```

同一事件行中的命令按从左到右顺序执行。

### 13.1 mv

移动当前 cursor。

```klip
[mv row,col]
```

示例：

```klip
[mv 10,20]
```

规则：

- row 从 1 开始。
- col 从 1 开始。
- row 是行，从上到下增加。
- col 是列，从左到右增加。
- 必须使用逗号。
- 不支持 `[mv 10 20]`。

### 13.2 color

设置当前 cursor 的前景色。

```klip
[color ff0000]
[color default]
```

### 13.3 background

设置当前 cursor 的背景色。

```klip
[background 101010]
[background default]
```

### 13.4 style

设置当前 cursor 的文本样式。

```klip
[style bold on]
[style bold off]

[style italic on]
[style italic off]

[style underline on]
[style underline off]

[style strikeline on]
[style strikeline off]

[style default]
```

`[style default]` 清除所有样式。

### 13.5 space

输出空格。

```klip
[space]
[space 4]
```

`[space]` 等价于 `[space 1]`。

输出空格时，也要经过 Z 轴保护判断。

### 13.6 newline

换行。

```klip
[newline]
```

语义：

- 当前 cursor 的 row 加 1。
- 当前 cursor 的 col 设为 1。

如果 row 超出 `height`，后续输出可以被裁剪或忽略，但必须保持程序不崩溃。

### 13.7 cleanline

清除当前 cursor 所在行。

```klip
[cleanline]
```

语义：

- 从当前行第 1 列到 `width` 列逐格清理。
- 只能清理当前事件 z 有权限清理的格子。
- 清理输出的空格使用默认终端样式，不继承当前 cursor 文本状态。
- 清理成功的格子 ProtectionMask 重置为未保护。
- 清理失败的格子保持不动。

### 13.8 clear

清屏。

```klip
[clear]
```

推荐语义：

- 对整个画布逐格清理。
- 只能清理当前事件 z 有权限清理的格子。
- 清理输出的空格使用默认终端样式，不继承当前 cursor 文本状态。
- 清理成功的格子 ProtectionMask 重置为未保护。

实现不得简单无条件输出 ANSI 全屏清除后忽略 ProtectionMask。
如果为了初版简化而这样做，必须在文档和 check warning 中明确标注：`[clear]` 会破坏保护语义，只建议在场景切换时使用。

### 13.9 hide / show

隐藏或显示物理终端光标。

```klip
[hide]
[show]
```

这是终端全局状态，不是某个逻辑 cursor 的独立状态。

程序退出时必须尽量执行 `[show]` 对应的恢复逻辑。

------

## 14. 文本输出语义

### 14.1 普通文本

```klip
[00:01.000]hello
```

输出 `hello`。

### 14.2 命令后文本

```klip
[00:01.000][mv 10,20][color ff0000]hello
```

先移动，再设置颜色，再输出文本。

### 14.3 多段文本

```klip
[00:01.000]hello[color ff0000]world
```

输出：

- `hello`
- 设置红色
- 输出 `world`

### 14.4 宽字符

文本输出必须按 Unicode code point 遍历，并计算终端显示宽度。

例如：

```text
熱
```

显示宽度为 2。

输出宽字符时，如果该字符占用 2 个格子，则两个格子都必须通过 ProtectionMask 检查。

如果任一格不可写，则整个字符跳过，但 cursor 逻辑位置仍前进 2 列。

------

## 15. Z 轴与 Protect

KLIP 使用 Z 轴和 ProtectionMask 防止低层特效覆盖高层歌词。

### 15.1 Z 轴方向

数字越大，越靠上。

```text
z=100 高层
z=20  低层
z=0   背景
```

### 15.2 protect

当事件的 `protect=on` 时，它成功输出的字符占用格会被保护。

示例：

```klip
[track lyrics cursor=main z=100 protect=on]
```

主歌词输出后，会保护它占用的格子。

### 15.3 ProtectionMask

ProtectionMask 不保存屏幕字符。
ProtectionMask 只保存每个格子的保护 Z 值。

每个格子的状态：

```text
-1      未保护
0..n    被某个 z 值保护
```

### 15.4 写入规则

当 writerZ 要写入某格：

如果该格未保护，允许写入。
如果该格 protectedZ <= writerZ，允许写入。
如果该格 protectedZ > writerZ，禁止写入。

也就是：

```text
writerZ >= protectedZ 允许
writerZ < protectedZ  禁止
```

### 15.5 标记规则

如果写入成功，并且当前事件 `protect=on`，则将该格 protectedZ 设置为 writerZ。

如果写入成功，但当前事件 `protect=off`，则不改变该格保护状态。

### 15.6 清理规则

`cleanline` 和 `clear` 也要遵循写入权限。

如果当前 z 有权限清理某格，则输出空格并将该格 ProtectionMask 重置为 -1。
如果当前 z 没有权限清理某格，则跳过。

### 15.7 同 z 覆盖

同 z 可以覆盖同 z 保护内容。

这允许主歌词 track 更新自己之前写出的歌词。

### 15.8 示例

```klip
[track fx cursor=fx z=20 protect=off]
  [chorus][mv 10,20]XXXXXXXX
[endtrack]

[track lyrics cursor=main z=100 protect=on]
  [chorus][mv 10,20]熱異常
[endtrack]
```

同一时间执行时，z=20 先执行，z=100 后执行。
最终显示歌词。

如果之后低层特效再次写入同一区域：

```klip
[track fx2 cursor=fx z=20 protect=off]
  [chorus+500][mv 10,20]YYYYYYYY
[endtrack]
```

它不能覆盖 z=100 protect=on 的歌词区域。

------

## 16. 宽字符显示宽度

KlipPlayer 必须实现显示宽度计算。

原因：日文歌词、中文歌词、全角符号在终端中通常占 2 列。
如果按字符串长度代替终端列宽计算，会导致 Z 轴保护、光标移动、覆盖判断全部错位。

### 16.1 基本规则

显示宽度：

```text
ASCII 普通字符      1
CJK 汉字            2
平假名              2
片假名              2
韩文                2
全角标点            2
全角拉丁字符        2
emoji               2
组合附加符号        0
控制字符            0
```

### 16.2 输出时的处理

对每个 code point：

1. 计算显示宽度。
2. 根据当前 cursor row/col 计算占用格。
3. 检查占用格是否都可写。
4. 如果可写，移动终端物理光标并输出该 code point。
5. 如果不可写，不输出。
6. 无论是否输出，逻辑 cursor 都按显示宽度前进。
7. 如果输出成功且 protect=on，标记所有占用格。

------

## 17. 编译模型

KLIP v1.1.1 的执行模型是编译期展开。

### 17.1 编译流程

1. 读取 `.klip` 文件。
2. 解析 meta。
3. 解析 anchor。
4. 解析 cue。
5. 解析 track。
6. 加载 `[meta addon=...]` 声明的 Lua addon。
7. 展开 cue 内 loop。
8. 展开 track/cue 内 `[func ...]`。
9. 展开 track 内 emit。
10. 生成全局事件列表。
11. 按时间、z、order 排序。
12. 播放时按音频时钟执行事件。

### 17.2 Event

编译后的事件至少包含：

```text
timeMs
order
cursorId
z
protect
ops
sourceLine
```

### 17.3 排序规则

全局事件排序：

1. `timeMs` 小的先执行。
2. `timeMs` 相同，`z` 小的先执行。
3. `z` 相同，`order` 小的先执行。

原因：

同一时间内，低层先画，高层后画。
这样背景和特效先输出，歌词后输出。

### 17.4 禁止运行时控制流

v1.1.1 禁止：

- 运行时 loop
- 运行时 coroutine
- 运行时 macro
- 运行时变量
- 运行时脚本执行
- 运行时 Lua addon 执行

所有 cue、emit、loop 和 Lua `[func ...]` 都必须在编译期展开。

------

## 18. 顶层语法

顶层允许：

```klip
[meta ...]
[anchor ...]
[cue ...]
[track ...]
```

顶层不允许直接写事件。

非法：

```klip
[00:01.000]hello
```

必须写在 track 中：

```klip
[track lyrics cursor=main z=100 protect=on]
  [00:01.000]hello
[endtrack]
```

原因：track 明确提供 cursor、z、protect，避免隐式状态混乱。

------

## 19. 块嵌套规则

允许：

```text
cue 内包含 loop
cue 内包含 func
track 内包含事件
track 内包含 emit
track 内包含 func
```

不允许：

```text
track 内包含 track
track 内包含 cue
cue 内包含 cue
cue 内包含 track
loop 内包含 track
loop 内包含 cue
loop 内包含 emit
cue 内包含 emit
func 行混写普通命令、文本或 emit
```

v1.1.1 的合法结构：

```klip
[cue ...]
  [loop ...]
    [+...]
  [endloop]
[endcue]

[track ...]
  [...]
  [...][emit name]
  [...][func name key=value]
[endtrack]
```

------

## 20. 错误处理

解析和编译错误信息必须包含：

- 错误码
- 文件名
- 行号
- 简短说明

推荐格式：

```text
KLP1001 file.klip line 12: 未知顶层标签 [foo]
```

### 20.1 错误分类

#### ParseError

语法无法解析。

示例：

```text
KLP1001 file.klip line 12: 未闭合标签
KLP1001 file.klip line 18: 非法颜色值
KLP1001 file.klip line 22: 未知命令标签 [foo]
KLP1001 file.klip line 30: 参数不是 key=value
```

当前 v1.1.1 实现将解析期错误统一归类为 `KLP1001`。

#### CompileError

语法合法，但语义无法编译。

示例：

```text
KLP2001 file.klip line 31: cue 内不允许使用绝对时间
KLP3001 file.klip line 45: 未定义 anchor: chorus
KLP3002 file.klip line 46: 重复定义 anchor: chorus
KLP4001 file.klip line 52: 未定义 cue: rain
KLP4002 file.klip line 53: 重复定义 cue: rain
KLP5001 file.klip line 60: 相对节拍缺少 BPM 上下文
KLP5001 file.klip line 66: 时间表达式无法解析: intro++2b
KLP6001 file.klip line 3: addon 加载失败: addons/textfx.lua
KLP6002 file.klip line 4: 重复注册 function: type
KLP6003 file.klip line 20: 未定义 function: type
KLP6004 file.klip line 21: Lua function type 返回结构非法
```

当前实现中，`KLP2001` 专用于 cue 内非法时间；anchor/cue 引用错误使用 `KLP300x`/`KLP400x`；时间表达式和 duration 编译错误使用 `KLP5001`；Lua addon 编译期错误使用 `KLP600x`。

#### RuntimeError

播放或终端输出阶段错误。
当前 v1.1.1 运行期兜底错误不包含文件名和行号。

示例：

```text
KLP9001 runtime: 终端输出失败
```

音频缺失、未配置或不支持时，当前 v1.1.1 `play` 不直接失败，而是输出 warning 并使用 monotonic no-audio clock。

### 20.2 check 命令

`klip check file.klip` 必须执行解析和编译。

如果存在错误，返回非 0 退出码。

------

## 21. CLI 相关语义

KLIP 规范主要定义脚本，但 KlipPlayer 应至少提供：

```bash
klip play file.klip
klip play --start-at 00:30.000 file.klip
klip check file.klip
klip compile file.klip
```

### 21.1 play

解析、编译并播放。

`play` 可使用 `--start-at MM:SS.mmm` 指定起始播放位置。使用该选项时，指定时间之前的事件会无视时间间隔快速执行；随后音频时钟和剩余事件从指定时间开始同步播放。

如果 `music` 未配置、文件不存在、或音频设备无法启动播放，当前实现会向 stderr 输出 warning，并使用 monotonic no-audio clock 继续执行事件表。Rust 实现使用 rodio 解码和播放音频；Java Sound 实现保留在 `legacy/kotlin/`。

### 21.2 check

只解析和编译，不播放。

当前实现输出纯文本摘要：

```text
file=examples/demo.klip
music=demo.mp3
width=80
height=24
anchors=2
cues=2
tracks=2
events=31
range=333..10027ms
```

### 21.3 compile

输出编译后的事件表，用于调试。

示例：

```text
333ms order=0 z=20 cursor=rain protect=false line=10 source=cue:rain/loop :: mv 1,10 | text |
333ms order=28 z=100 cursor=main protect=true line=31 source=track:lyrics :: mv 10,20 | color default | text 熱異常
```

------

## 22. 禁止功能

KLIP v1.1.1 明确禁止：

- KTS
- 变量
- 宏参数
- 随机数
- 条件判断
- 运行时函数调用
- 真实运行时协程
- 运行时线程特效
- TUI
- 完整 virtual screen
- 运行时插件系统
- KTS 插件系统
- 网络功能
- 顶层直接事件
- 宏式 cue 参数
- cue 内 emit
- track 内 loop
- `[at ...]`
- `[bpm ...]`
- `[cursor ...]`
- `[newcursor ...]`
- `[delcursor ...]`

图像相关功能暂时保留，不进入 v1.1.1。

允许的扩展边界：通过 `[meta addon="..."]` 加载可信 Lua 文件，并通过 `[func ...]` 在编译期生成事件。该机制不是运行时插件系统。

保留但不实现：

```klip
[img ...]
```

------

## 23. 完整示例

```klip
[meta music="demo.mp3"]
[meta width=80]
[meta height=24]

[anchor intro 00:00.000 bpm=180]
[anchor chorus 00:10.000 bpm=180]

[cue rain cursor=rain z=20 protect=off]
  [loop 3]
    [+0][mv 1,10]|
    [+80][mv 1,10][space][mv 2,10]|
    [+80][mv 2,10][space][mv 3,10]|
    [+80][mv 3,10][space]
  [endloop]
[endcue]

[cue flash cursor=warn z=80 protect=off]
  [+0][mv 5,20][color ff0055]WARNING
  [+120][cleanline]
  [+120][mv 5,20][color ff0055]WARNING
  [+120][cleanline]
[endcue]

[track fx cursor=fx z=20 protect=off]
  [intro+1b][emit rain]
  [intro+2b][emit rain]
  [chorus-1b][emit flash]
[endtrack]

[track lyrics cursor=main z=100 protect=on]
  [intro+1b][mv 10,20][color default]熱異常
  [+1/2b][mv 11,20]なにかが来ている
  [chorus][mv 12,20][color ff0000]すぐそこまで
[endtrack]
```

------

## 24. 语法速查

### Meta

```klip
[meta music="song.mp3"]
[meta width=160]
[meta height=40]
```

### Anchor

```klip
[anchor intro 00:00.000 bpm=180]
```

### Track

```klip
[track lyrics cursor=main z=100 protect=on]
  [intro+1b]text
[endtrack]
```

### Cue

```klip
[cue rain cursor=rain z=20 protect=off]
  [+0]text
[endcue]
```

### Emit

```klip
[chorus-1b][emit rain]
```

### Func

```klip
[intro][func type text="abc" interval=80ms]
```

### Loop

```klip
[loop 4]
  [+80]text
[endloop]
```

### Time

```klip
[00:01.000]
[intro]
[intro+4b]
[intro+1/2b]
[chorus-2b]
[+500]
[+1b]
[+3/8b]
```

### Output

```klip
[mv 10,20]
[color ff0000]
[color default]
[background 101010]
[background default]
[style bold on]
[style default]
[space]
[space 4]
[newline]
[cleanline]
[clear]
[hide]
[show]
```
