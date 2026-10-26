# t-SmartTube changes

This log covers the enhancement fork. Upstream release history remains available
in [SmartTube releases](https://github.com/yuliskov/SmartTube/releases).

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
