# t-SmartTube changes

This log covers the enhancement fork. Upstream release history remains available
in [SmartTube releases](https://github.com/yuliskov/SmartTube/releases).

## 增强修订 16

- 放宽播放器字幕显示最大宽度：将 `#leanback_subtitles` 左右内边距由固定 `150sp` 调整为 `48dp`（标准 Android TV 16:9 画面下约占 90% 屏宽，且不受系统字体缩放放大边距），减少字幕单行空间未满即过早换行的问题，并兼容侧栏菜单缩放与恢复。
- 重排播放页字幕菜单与主页「设置 → 字幕」布局：原版字幕选项统一位于上方，AI 双语配置统一收拢于下方，消除两类设置项穿插混排。
- 移除界面中的「单次翻译句数」配置项并在后端固定为 `8`（兼容旧版已保存配置，平滑迁移不丢密钥与语言设置）；全面精简中英文菜单与模型弹窗文案，去除冒号拼接、括号说明与多行状态文字，统一为与原版一致的简洁短标题。

## 增强修订 15

- 新增受限生产级 TTML（`application/ttml+xml`）原生时间轴解析与通用自动字幕（`kind=asr`）滚动消解，合并渐进累加、换行滑窗及紧邻／重叠词汇回显（位图字幕与顺序容器安全退回原生路径）。
- 扩展 WebVTT 自动滚动识别与长视频容量：原生解析入口兼容前置 `STYLE` 块，支持主语言子标签匹配、ASR 样式类标签、行首时间戳、单行首句衔接与三行固定滑窗，并将单轨上限扩展至 `8192` 条字幕／`65536` 片段；同步去重同链接合成轨道避免误判放弃规范化。
- 新增无标点自动字幕停顿感知断句，并将单调非重叠显示窗口覆盖所有已规范化轨道；合并紧邻重复原生投影片段，仅对已规范化或独立获取的原版轨道放行空帧回退，未改动上游业务 Java 源码与子模块。

## 增强修订 14

- 修复开启双语字幕时 YouTube 自动生成滚动字幕（`kind=asr`）在跨句切换或长句分段后出现的跨句穿插与多行堆积问题：为规范化连续滚动流内的各个语义组收紧显示时间窗口，在后续语义组进入或同轮增量推进时及时结束上一组显示，消除旧句与新句重叠展示。
- 同步对齐字幕预取下一组起点查询，保持译文批量上下文与邻句只读参考范围不变；未改动上游 SmartTube 业务源码与子模块。

## 增强修订 13

- 修复 AI 字幕设置中“目标翻译语言”与“单次翻译句数”选择器弹窗出现深灰底黑字导致难以辨认的问题，改为沿用原生深色侧边单选面板，并补充 AI 连接配置弹窗输入框、下拉列表与提示文字的深色对比度适配。
- 新增单行字幕规范化处理：合并短句字幕块与批量译文内部的硬换行并区分中英文与全角标点连接空格，同时要求模型对每项返回无内部换行的单行译文。
- 在播放器原生字幕视图主题中配置 `1.2x` 行距倍率，适度拉开双语字幕上下两行间距且保持单行字幕显示位置不变，未改动上游 Java 源码与子模块。

## 增强修订 12

- 重构双语字幕分批预取调度：采用首屏小批快速起播与高低水位滑窗批量续取（播放页与独立字幕设置新增「单次翻译句数」，默认 8 句），消除连续播放时逐句零碎请求，并在后台提前准备下一批待译窗口。
- 升级模型上下文与分批翻译协议：使用紧凑段落标记传输批量原文字幕与译文，附带当前视频标题／频道元信息、前序最多 2 句与后序 1 句只读邻句参考，提升跨句代词、断句与专有名词连贯性；严格校验标记完整性并对缺漏段按连续区间二分补齐。
- 优化字幕翻译系统提示词与采样参数：收敛为口语化影视／视频字幕译者规范，支持常见渠道确定性零温（`temperature: 0`）采样并对不支持温度参数的推理模型安全省略。

## 增强修订 11

- 拍平播放页双语字幕菜单，直接展示频道双语开关、常用翻译语言列表（支持自定义语言代码）、字幕显示方式与 AI 模型设置；同步在主页「设置 → 字幕」顶部补齐全局配置入口。
- 模型设置改为「接口类型／快捷填入 + 接口地址 + 模型 ID + API Key」主屏结构，将推理思考控制、单次最大输出长度与可选 Workspace ID 收纳进默认折叠的「高级选项」；支持 OpenAI 兼容 Chat 地址的常见简写自动补全（仍拦截明文 `http://`）。
- 修复后台播放器销毁与冷启动账号加载重入对独立字幕设置页的影响，补齐电视遥控器 `Enter` 确认键唤起软键盘与折叠按钮落焦高亮，并移除 `AndroidManifest.xml` 中未使用的残留权限声明。

## 增强修订 10

- 修复 WebVTT 相同词级时间标记导致整条字幕无法增强的问题；同时刻文本共用时间窗口，不丢字或改写原始字幕时钟。
- 对词级时间先后或范围不可信、但原生可读的 cue，仅退为原 cue 时间区间；正常 cue 保留词级映射，格式、安全与资源预算检查继续生效。
- 增加默认关闭的 ADB 脱敏运行诊断，仅记录内部状态、计数与 HTTP 状态，不记录字幕正文、请求地址或密钥。

## Enhancement revision 4

- Remove LAN phone-pairing server, QR code dialog, web assets, and ZXing dependency to keep configuration purely native and lightweight.
- Raise the default maximum output token limit for new connections from 1024 to 8192.
- Simplify subtitle settings and model connection dialog labels, hints, and status copy.

## Enhancement revision 3

- Decouple subtitle source reset and seek lifecycle hooks from high-churn `ExoPlayerController` methods into `ExoMediaSourceFactory` and `SubtitleManager` (`Player.EventListener`) for clean upstream `32.71` integration.

## Enhancement revision 1

- Keep valid in-flight translation and prefetch through ordinary native-caption gaps without reviving cleared captions.
- Bound complete translation jobs by an overall deadline and preserve shorter connection-test deadlines.
- Respect numeric and HTTP-date Retry-After for rate limits and service-unavailable responses, including source/configuration changes.
- Add explicit OpenAI Chat/Responses, DeepSeek, Claude Messages, Gemini GenerateContent and custom Chat connections.
- Add conservative exact-model automatic low-thinking rules and request-policy feedback, preserving provider-default and manual compatible DeepSeek choices.
- Preserve legacy connection/Key bindings and require explicit Key replacement or removal when changing endpoint, service or workspace.
- Extend native and phone connection forms without adding a second subtitle settings system.

New service protocols are covered by synthetic contract tests; real-service access, model availability and device compatibility still require their own checks.

## Initial enhancement revision

- Add channel-level AI subtitle enablement and global target-language/display settings through the native subtitle menu.
- Add configurable chat-completions-compatible translation, bounded batching and recovery, source-aware caching, and guarded native bilingual output.
- Add temporary trusted-LAN connection pairing with locally generated QR codes and app-side credential storage.
- Preserve native subtitle selection, NONE, styling and the AI-off caption path.
- Add conservative WebVTT source mapping and paint-on normalization without modifying source bytes or inventing word timing.
- Keep accepted segmentation during activation-only effective-session rebuilds, while invalidating old presentation handles.
- Reject optional model segmentation that expands a unit beyond its input block's existing per-group size budget.
- Add separate stable/beta application identities, this fork's update feeds, and source-pinned upstream-following release workflows.

The subtitle enhancement is experimental in both upstream channels. Caption
coverage, first-result latency, long-group layout, live captions, and TV/remote
behavior continue to evolve; a channel label is not a complete quality guarantee.
