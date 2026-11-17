# Kaixinguo UI adaptation — Supjav

Source UI reference: `yagay/kaixinguo-android` main, November 2026.

- Five labeled bottom tabs: 推荐 / 排行榜 / 新剧 / 我的 / 搜索.
- Green Material 3 palette and ordinary page headers.
- Adaptive small-poster grid, pull-to-refresh, paginated feeds.
- Rankings show Day/Week/Month only; the Supjav public site has no verified rank-content subgroup.
- New releases are presented as the site's latest posts, **not** a fabricated date calendar.
- Search has text input, local history and a real site navigation taxonomy when available; no invented remote hot-search API.
- My shows recent viewing and favorites on one screen; settings have a separate entry.
- Detail remains an individual video with alternate servers, not a fictional season/episode list.
- Media3 service retains one playback instance, and player chrome overlays are separate from route transitions.

UI-only imports are conceptual; none of the Kaixinguo SDK authentication, recommendation or playback APIs should be mixed into Supjav.
