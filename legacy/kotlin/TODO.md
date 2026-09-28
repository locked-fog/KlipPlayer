# KLIPlayer Agent 开发指南

## 0. 项目定位

项目名称：KLIPlayer

含义：Kotlin CLI-Player。
CLIPlayer 指 “CLI-Player”，即命令行播放器；KLIPlayer 是使用 Kotlin/JVM 重写的新版本。

KLIPlayer 是一个跨平台命令行歌词/文字演出播放器。它读取 `.klip` 脚本文件，加载音频文件，将脚本编译为全局时间轴事件表，然后在终端中按音乐时间同步输出带颜色、位置、样式、节奏、特效和 Z 轴保护的文字演出。

项目目标不是做完整脚本语言运行时，也不是做 TUI 框架，而是做一个稳定、轻量、跨平台、方便 AI 和人类共同编写脚本的 CLI 演出工具。

核心目标：

- Kotlin/JVM 实现。
- 打包为可运行 JAR。
- 支持 Windows / Linux / macOS。
- 支持 ANSI 终端输出。
- 内置音频播放，不依赖外部播放器作为正式方案。
- 支持 `.klip` 脚本。
- 支持绝对时间、相对时间、节拍时间、分数节拍。
- 支持 anchor 对轴。
- 支持 track 分块书写。
- 支持 cue + emit 的编译期“伪协程”特效。
- 支持 loop 编译期展开。
- 支持多 cursor。
- 支持 z/protect 防止低层特效覆盖高层歌词。
- 支持中日文宽字符显示宽度计算。
- 暂不实现 KTS、变量、宏参数、随机数、TUI、真实运行时协程、完整 virtual screen。
- 图像输出暂时保留设计余地，但不要在初版实现。

一句话定义：

KLIPlayer 是一个带 Z 轴保护的、基于 KLIP 脚本的、编译期时间轴展开型终端演出播放器。

------

## 1.0.1 当前实现状态

已完成并合入 `main`：

- 单 Gradle Kotlin/JVM module，JVM target 21。
- 可运行 JAR。
- `README.md`、`TODO.md`、`docs/KLIP_SPEC.md`、`docs/WORKFLOW.md`、`examples/demo.klip`。
- 基础数据结构：Meta、Anchor、Track、Cue、Event、Op。
- KLIP parser：meta、anchor、track、cue、emit、cue 内 loop、命令标签、整行注释、文本转义。
- 时间解析：绝对时间、anchor 时间、相对时间、毫秒、普通节拍、分数节拍。
- compiler：track/cue/emit/loop 编译期展开，按 time/z/order 排序。
- ProtectionMask：只保存保护 Z 值，不保存完整屏幕字符。
- WcWidth：按 Unicode code point 计算 CJK、假名、全角标点、组合符号等显示宽度。
- ANSI TerminalRenderer：移动光标、前景/背景色、style、space/newline、cleanline/clear、hide/show。
- AudioPlayer/AudioClock：Java Sound 结合打包的 MP3/FLAC 服务提供器尝试播放音频；缺失、不支持或未配置音乐时明确 warning 并使用 monotonic no-audio clock。
- CLI：`check`、`compile`、`play`。
- parser/compiler/protection mask/wcwidth/audio/renderer 基础测试和负例测试。

仍未实现：

- 精确音频硬件时间戳。
- JSON compile 输出。
- 复杂终端端到端测试。
- 更完整的音频设备和编解码兼容性验证。
- 任何禁止清单中的功能。

------

## 1. 最高优先级开发原则

本项目必须快速推进，禁止过度工程化。

Agent 必须遵守：

- 不得在 `main` 分支上直接开发。
- 不得直接向 `main` 合并。
- 不得无理由拆成多模块。
- 不得引入依赖注入框架。
- 不得引入插件系统。
- 不得引入 KTS。
- 不得引入真实协程运行时语义。
- 不得为了“架构优雅”拆出大量 service / manager / factory / provider。
- 不得把项目写成复杂语言虚拟机。
- 初版优先保证能解析、能播放、能保护歌词不被特效覆盖。

允许适度拆文件，但必须保持简单。建议初始 Kotlin 源文件控制在 5～8 个以内。

建议结构：

/src/main/kotlin/

- Main.kt
- KlipParser.kt
- KlipCompiler.kt
- Timeline.kt
- TerminalRenderer.kt
- AudioPlayer.kt
- WcWidth.kt

/src/test/kotlin/

- KlipParserTest.kt
- KlipCompilerTest.kt
- WcWidthTest.kt
- ProtectionMaskTest.kt

/examples/

- demo.klip

/docs/

- KLIP_SPEC.md
- WORKFLOW.md

不要一开始创建复杂目录树。项目变大后再自然拆分。

------

## 2. Git 工作流硬性要求

### 2.1 分支规则

`main` 是稳定分支，禁止直接开发。

任何开发必须先创建功能分支：

- `feat/initial-core`
- `feat/audio-renderer-core`
- `feat/parser-hardening`
- `feat/klip-parser`
- `feat/protection-mask`
- `docs/spec-sync`
- `fix/audio-sync`
- `docs/spec-update`

Agent 开始工作前必须执行或确认：

- 当前不在 `main` 上开发；如果在 `main`，必须先拉新分支。
- 所有修改必须提交到功能分支。
- 合并前必须生成完整变更报告。

### 2.2 提交规则

提交信息使用简短中文或英文均可，但必须清楚。

示例：

- `feat: 初始化 Kotlin/JVM 项目`
- `feat: 实现 KLIP track cue emit 编译`
- `feat: 实现 Z 轴保护掩码`
- `test: 添加宽字符显示宽度测试`
- `docs: 添加 KLIP 语法规范`

每个提交应当对应一个明确变更，不要把无关修改混在一个提交里。

### 2.3 合并前审查规则

任何功能分支合并到 `main` 前，必须进行独立审查。

审查流程：

1. 主 Agent 完成开发后，不得直接 merge。
2. 主 Agent 必须运行：
   - `git status`
   - `git diff main...HEAD`
   - `./gradlew cleanTest test`
   - `./gradlew build`
3. 主 Agent 必须启动或请求一个独立 sub-agent 审查全部更改。
4. sub-agent 的职责是审查，不得直接合并。
5. sub-agent 必须检查：
   - 是否修改了不相关文件。
   - 是否违反本文件中的开发原则。
   - 是否在 main 上直接开发。
   - 是否引入了过度架构。
   - 是否引入了被禁止的功能，例如 KTS、真实协程、TUI。
   - KLIP 语法实现是否符合规范。
   - track / cue / emit / loop 是否是编译期展开。
   - Z 轴保护是否不依赖完整 virtual screen。
   - 宽字符处理是否覆盖中文、日文假名、全角字符。
   - 测试是否通过。
6. sub-agent 必须向用户报告审查结果。
7. 只有用户明确同意后，主 Agent 才能合并到 `main`。
8. 如果用户没有明确同意，不得 merge。
9. 如果审查发现问题，必须在当前功能分支修复，然后重新审查。

合并命令建议：

- `git checkout main`
- `git merge --no-ff <branch-name>`

禁止未经用户同意执行：

- `git merge`
- `git rebase main`
- `git push --force`
- 删除分支
- 删除文件
- 重写历史

------

## 3. KLIP 语法

见 `docs/KLIP_SPEC.md` 。

------

## 4. 宽字符适配

必须支持中日文宽字符。
《熱異常》这类歌词中会大量出现日文假名、汉字、全角符号，因此不能按 Kotlin `Char.length` 计算终端列宽。

必须实现 `displayWidth`：

- ASCII 普通字符宽度 1。
- CJK 统一表意文字宽度 2。
- 平假名宽度 2。
- 片假名宽度 2。
- 韩文宽度 2。
- 全角标点宽度 2。
- 全角拉丁字符宽度 2。
- 组合附加符号宽度 0。
- 控制字符宽度 0。
- emoji 可暂按 2 处理。

输出文本时按 code point 遍历，而不是简单按 Char 遍历。

写入文本时：

1. 根据当前 cursor 位置和字符显示宽度计算占用格。
2. 查询 ProtectionMask 判断这些格是否可写。
3. 如果可写，移动终端光标并输出该字符。
4. 如果不可写，跳过该字符，但 cursor 逻辑位置仍然前进。
5. 如果 protect=on，标记该字符占用的所有格。
6. 单字符输出可以接受，不要求复杂 chunk 合并。
7. flush 问题不是初版重点，只要体感延迟不明显即可。

必须添加测试：

- `熱` 宽度为 2。
- `異` 宽度为 2。
- `常` 宽度为 2。
- `あ` 宽度为 2。
- `ア` 宽度为 2。
- `A` 宽度为 1。
- `，` 或 `。` 宽度为 2。
- 组合符号宽度为 0。

------

## 5. 编译模型

KLIPlayer 的核心不是运行时解释复杂语言，而是编译期展开。

流程：

1. 读取 `.klip`
2. 解析 meta
3. 解析 anchor
4. 解析 cue
5. 解析 track
6. 展开 loop
7. 展开 emit
8. 生成全局 `List<Event>`
9. 按 `timeMs` 升序排序
10. 同一毫秒内，按 z 升序执行，低层先画，高层后画
11. 启动音频播放
12. 播放循环根据音频时钟执行事件

Event 至少包含：

- timeMs
- order
- cursorId
- z
- protect
- ops
- sourceLine

排序规则：

1. timeMs 小的先执行。
2. timeMs 相同，z 小的先执行。
3. z 相同，按源码/展开 order 执行。

不要实现运行时协程。
不要实现运行时 loop。
不要实现运行时宏。
不要实现变量系统。

------

## 6. 音频播放

KLIPlayer 必须内置音频播放能力。
不要把外部 `mpv` / `ffplay` 作为正式方案。

允许初版使用轻量 JVM 音频库实现 MP3 播放。
如果无法获得精确音频播放位置，初版可以使用音频启动后的 monotonic clock 作为 `AudioClock`，但必须封装在 `AudioPlayer` / `AudioClock` 中，方便之后替换为真实音频时间戳。

当前实现说明：

- 使用 Java Sound 尝试加载音频格式，并随应用打包 MP3 和 FLAC 的 Java Sound 服务提供器。
- 如果 `music` 未配置、音频文件不存在、或 Java Sound 无法启动播放，会输出明确 warning。
- fallback 使用 monotonic no-audio clock，以保证脚本演出仍可按时间线运行。
- 仍保留 fallback 路径，避免音频设备或编解码初始化失败时阻断脚本演出。

抽象接口：

- start()
- currentMs()
- stop()
- isFinished()

主循环：

- 启动音频。
- 循环读取 `currentMs()`。
- 执行所有 `event.timeMs <= currentMs` 的事件。
- 所有事件执行完毕且音频结束后退出。

禁止把音频逻辑散落在 parser 或 renderer 中。

------

## 7. 终端输出

初版目标是 ANSI 终端。

必须支持：

- 移动光标
- 前景色
- 背景色
- 样式
- 清行
- 清屏
- 隐藏/显示光标

Windows 下默认面向 Windows Terminal / 现代终端。
不要求支持旧版 cmd 的非 ANSI 模式。

程序退出时必须尽量恢复终端状态：

- style default
- color default
- background default
- show cursor
- newline

异常退出时也应尽量恢复。

------

## 8. CLI 命令

必须支持：

kliplayer play <file.klip>

播放脚本。

kliplayer check <file.klip>

只解析和编译，不播放。
输出：

- meta 信息
- anchor 数量
- cue 数量
- track 数量
- event 数量
- 时间轴范围
- 错误和警告

kliplayer compile <file.klip>

输出展开后的事件表，方便调试。
可以先输出纯文本格式，之后再考虑 JSON。

命令行参数可以简单实现，不要引入复杂 CLI 框架，除非非常必要。

------

## 9. 错误处理

解析和编译错误必须包含：

- 文件名
- 行号
- 错误类型
- 简短说明

示例：

KLP1001 file.klip line 12: 未知标签 [foo]
KLP2001 file.klip line 31: cue 内不允许使用绝对时间
KLP3001 file.klip line 45: 未定义 anchor: chorus
KLP4001 file.klip line 52: 未定义 cue: rain
KLP5001 file.klip line 77: 时间表达式无法解析: intro++2b

运行期兜底错误当前使用简短格式：

KLP9001 runtime: 终端输出失败

当前实现中的错误码范围：

- `KLP1001`：解析错误，包括未知标签、非法参数、非法颜色、非法 style、非法命令形态。
- `KLP2001`：cue 内使用了非相对时间。
- `KLP3001` / `KLP3002`：未定义或重复定义 anchor。
- `KLP4001` / `KLP4002`：未定义或重复定义 cue。
- `KLP5001`：时间表达式或 duration 无法编译，包括非法绝对时间、缺少 BPM 上下文、分数节拍分母为 0。
- `KLP9001`：非 `KlipException` 的运行期异常，例如终端或文件系统相关失败。

错误分为：

- ParseError：语法解析失败
- CompileError：语义编译失败
- RuntimeError：播放/终端/音频运行失败

`check` 命令遇到错误必须返回非 0 退出码。

------

## 10. 测试要求

初版至少需要测试：

- 绝对时间解析。
- 相对时间解析。
- anchor + beat 解析。
- 分数节拍解析。
- track 乱序书写后最终排序正确。
- cue + emit 展开正确。
- loop 展开正确。
- z/protect 低层不能覆盖高层。
- 同 z 可以覆盖。
- 高 z 可以覆盖低 z。
- 宽字符宽度。
- `.klip` 示例可以通过 check。

不要求一开始做复杂 UI 测试。
但 parser / compiler / protection mask 必须有测试。

------

## 11. 初版示例

examples/demo.klip 应包含：

```klip
[meta music="demo.mp3"]
[meta width=80]
[meta height=24]

[anchor intro 00:00.000 bpm=180]
[anchor chorus 00:10.000 bpm=180]

[cue rain cursor=rain z=20 protect=off]
[loop 3]
[+0ms][mv 1,10]|
[+80ms][mv 1,10][space][mv 2,10]|
[+80ms][mv 2,10][space][mv 3,10]|
[+80ms][mv 3,10][space]
[endloop]
[endcue]

[cue flash cursor=warn z=80 protect=off]
[+0ms][mv 5,20][color ff0055]WARNING
[+120ms][cleanline]
[+120ms][mv 5,20][color ff0055]WARNING
[+120ms][cleanline]
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

## 20. 禁止实现清单

初版明确禁止：

- KTS
- 变量
- 宏参数
- 随机数
- TUI
- 真实运行时协程
- 多线程特效调度
- 完整 virtual screen
- 图片输出
- sixel
- kitty image protocol
- 插件系统
- 复杂脚本表达式
- 条件判断
- 函数调用
- 网络功能

如果 Agent 认为必须实现其中任何一项，必须先停止并向用户说明理由，得到同意后才能继续。
