# bob-assist — 炉石「一键拔线」工具

跨平台（macOS + Android）的炉石酒馆战旗（BG）战斗动画跳过工具。

## 原理（一句话）

把炉石的流量过一遍 Clash/Mihomo（TUN 模式），通过 Clash 的 connection API 精确关掉「BG 战斗会话」那一条 TCP socket，触发 HS 客户端从服务端拉已预算好的战斗结果 → 动画被跳过。

- 详细原理：[`docs/how-it-works.md`](docs/how-it-works.md)
- 为什么其他方案不行：[`docs/failed-paths.md`](docs/failed-paths.md)
- 时间线 / 进展：[`PROGRESS.md`](PROGRESS.md)

## 平台

- **macOS** — 可用。见 [`mac/README.md`](mac/README.md)
- **Android** — Skip 协议已验证通过（手动 curl 触发），Overlay App 待写。见 [`android/README.md`](android/README.md)

## 关键 credit

- macOS 端基于 [z2z63/hearthstone_skipper](https://github.com/z2z63/hearthstone_skipper)；我们修复了 3 个 bug，fork 在 [mjsaber/hearthstone_skipper](https://github.com/mjsaber/hearthstone_skipper)
- Android 端用 [ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) 当 TCP 接管层，相同的 skip 协议

## 仓库结构

```
bob-assist/
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
