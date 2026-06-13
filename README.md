# TurboTavern (極速酒館)

> Skip Hearthstone Battlegrounds combat animations on Android & macOS — a clean
> automatic disconnect trick ("拔線"), plus live hero & trinket tier overlays.
> No root, no PC, no Overwolf. Open source.

**Website: https://turbotavern.com** · [繁體中文](#繁體中文) · License: GPL-3.0

TurboTavern is a Hearthstone Battlegrounds companion that fast-forwards the
60-second combat animations and overlays S/A/B hero and trinket tier ratings,
running natively on Android and macOS — where you actually play, not on a
Windows desktop.

## Features

- **Skip combat animations** — automates the disconnect trick so every
  Battlegrounds battle is instant. Saves ~5–10 min per game.
- **Hero & trinket tier overlay** — live S/A/B badges (built-in hero tier list
  and trinket tier list), refreshed daily from real stats.
- **Native Android & macOS** — no Windows PC, no Overwolf, no root, no
  deck-tracker plugin.
- **100% on-device & private** — never reads or modifies game data.

## How it works

When a Battlegrounds combat starts, the server has already computed the result.
TurboTavern routes Hearthstone's traffic through Clash/Mihomo (TUN mode) and uses
the Clash connection API to cleanly reset the single "combat session" TCP socket
— the client immediately re-fetches the already-computed result, and the
animation is skipped. It never reads or writes game memory or packet contents.

- Detailed mechanism: [`docs/how-it-works.md`](docs/how-it-works.md)
- Why other approaches fail: [`docs/failed-paths.md`](docs/failed-paths.md)
- Progress timeline: [`PROGRESS.md`](PROGRESS.md)

## Platforms

- **macOS** — available. See [`mac/README.md`](mac/README.md)
- **Android** — skip protocol verified; overlay app in progress. See [`android/README.md`](android/README.md)

## Download

Join the waitlist for the packaged app at **https://turbotavern.com** — or build
from source using the per-platform setup guides above.

## Credits

- macOS build is based on [z2z63/hearthstone_skipper](https://github.com/z2z63/hearthstone_skipper) (3 bugs fixed; fork at [mjsaber/hearthstone_skipper](https://github.com/mjsaber/hearthstone_skipper))
- Android uses [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) as the TCP interception layer with the same skip protocol

## 繁體中文

**TurboTavern（極速酒館）** 是爐石戰記酒館戰棋（戰場）助手，支援 **Android 與 macOS**：

- **跳過戰鬥動畫 / 動畫變快** — 自動完成「拔線」，瞬間跳過 60 秒戰鬥動畫，每局省約 5–10 分鐘。
- **英雄與飾品分級懸浮窗** — 即時 S/A/B 分級（內建英雄分級、飾品分級），每日更新。
- **原生 Android 與 macOS** — 無需 Windows 電腦、無需 Overwolf、無需 Root。
- 與 HDT、Firestone、Overwolf 不同：那些是 Windows 桌面外掛，TurboTavern 直接在你遊玩的手機和 Mac 上運行。

官網：**https://turbotavern.com** ｜ 原理詳見 [`docs/how-it-works.md`](docs/how-it-works.md)

## 仓库结构

```
turbotavern/
├── README.md                       本文件
├── PROGRESS.md                     进展时间线
├── docs/
│   ├── how-it-works.md             原理详解（跨平台通用）
│   ├── failed-paths.md             失败方案 + 教训汇总
│   └── superpowers/                早期 spec/plan 归档
├── mac/
│   ├── README.md                   macOS 端 setup
│   ├── mihomo/                     Mihomo Party 配置 snapshot
│   └── skipper/                    HS Skipper（指向 fork）
└── android/
    ├── README.md                   Android 端 setup
    ├── cmfa/                       CMFA profile + 手动触发脚本
    └── overlay-app/                Overlay App TODO（未开始）
```

## Disclaimer

TurboTavern is not affiliated with or endorsed by Blizzard Entertainment.
Hearthstone is a trademark of Blizzard Entertainment, Inc.
