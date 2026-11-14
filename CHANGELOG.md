# t-SmartTube changes

This log covers the enhancement fork. Upstream release history remains available
in [SmartTube releases](https://github.com/yuliskov/SmartTube/releases).

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
