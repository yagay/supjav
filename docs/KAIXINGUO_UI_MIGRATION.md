# Kaixinguo UI adaptation — Supjav

Source UI reference: `yagay/kaixinguo-android` main, November 2026.

- Six labeled bottom tabs: 推荐 / 分类 / 排行 / 最新 / 我的 / 搜索.
- Green Material 3 palette and ordinary page headers.
- Adaptive small-poster grid, pull-to-refresh, paginated feeds.
- Categories dynamically index same-site links to /category/, /tag/, /cast/, /maker/ and the legacy /category/cast/ and /category/maker/ forms. Links are kept as the actual observed relative paths instead of rebuilt from translated display labels.
- Category links are also harvested from video detail pages. Successful observations, favorites and recently visited category paths are stored locally, so the directory can remain visible during temporary site access failures.
- Rankings show Day/Week/Month only; the Supjav public site has no verified rank-content subgroup.
- New releases are presented as the site's latest posts, **not** a fabricated date calendar.
- Search has text input, local history and a real site navigation taxonomy when available; no invented remote hot-search API.
- My shows recent viewing and favorites on one screen; settings have a separate entry.
- Detail remains an individual video with alternate servers, not a fictional season/episode list.
- Media3 service retains one playback instance, and player chrome overlays are separate from route transitions.

UI-only imports are conceptual; none of the Kaixinguo SDK authentication, recommendation or playback APIs should be mixed into Supjav.

## Verification

The website can return HTTP 403 to direct clients; repository compilation cannot confirm that live category links are obtainable from the user's network. When unavailable, the UI reports that limitation and reuses only previously discovered links, not fabricated remote categories. Parser tests validate multilingual and legacy URL structures.
