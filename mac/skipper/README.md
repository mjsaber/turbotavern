# HearthStone Skipper (fork)

[z2z63/hearthstone_skipper](https://github.com/z2z63/hearthstone_skipper) 的 fork，加了 3 个 macOS 端必需的 patch：

| Patch | 说明 |
|---|---|
| `fix(settings): load TCPIP clash config from QSettings` | 上游 QVariant 比较 bug + 反向 `isValid()` 条件，导致 TCPIP 模式的 external-controller 配置永远 load 不上，被自动覆写成 NONE |
| `fix(macos): detect fullscreen Metal HS window via CGWindowList` | 上游用 AX API 找窗口，全屏 Metal/SDL 游戏返回 0 个窗口，浮窗永远不显示。fork 加了 CGWindowList fallback |
| `chore(logger): flush every log line` | spdlog 默认 4KB 缓冲，调试时痛苦。改成 `flush_on(trace)` |

**Fork**: https://github.com/mjsaber/hearthstone_skipper（`main` 分支已合上述 3 个 patch）

## Build

```bash
git clone https://github.com/mjsaber/hearthstone_skipper.git
cd hearthstone_skipper
mkdir build && cd build
cmake .. -DCMAKE_PREFIX_PATH=$(brew --prefix qt)
make -j
open .
# 拖 "HearthStone Skipper.app" 到 /Applications/
```

依赖：
- Qt 6.x：`brew install qt`
- CMake 3.20+
- macOS 14+ SDK
