# macOS 端 setup

## 你需要

- macOS 14+（Sequoia 15.x 测试过）
- Homebrew
- 炉石（国际服测试过）

## 安装步骤

### 1. Mihomo Party

```bash
brew install --cask mihomo-party
```

首次启动会要求授权 system helper（输密码），授权后会创建 TUN 网卡（默认 `utun1500`）。

### 2. 应用我们的 mihomo 配置

```bash
mkdir -p "$HOME/Library/Application Support/mihomo-party/profiles"
cp mac/mihomo/mihomo.yaml \
   "$HOME/Library/Application Support/mihomo-party/mihomo.yaml"
cp mac/mihomo/profile-default.yaml \
   "$HOME/Library/Application Support/mihomo-party/profiles/default.yaml"
```

关键设置（已经在 snapshot 里）：
- `external-controller: 127.0.0.1:9090` + `secret: ""` — Skipper 走这个调 API
- `find-process-mode: always` — 必须，让 Skipper 能 filter HS 进程
- `tun.enable: true` — 让 HS 流量经过 mihomo
- `sniffer.enable: false`、`parse-pure-ip: false` — 关掉，让 BG 战斗 socket 的 `host` 字段为空（Skipper filter 条件）

重启 Mihomo Party，确认：
- 主页 core 是 Mihomo (Meta)
- 顶部 TUN 开关打开
- 当前 profile 是 default

### 3. HearthStone Skipper

我们维护了一个 fork（含 3 个必需的 bug fix，未上游）：

```bash
git clone https://github.com/mjsaber/hearthstone_skipper.git
cd hearthstone_skipper
mkdir build && cd build
cmake .. -DCMAKE_PREFIX_PATH=$(brew --prefix qt)
make -j
cp -R "HearthStone Skipper.app" /Applications/
```

详见 [`skipper/README.md`](skipper/README.md)。

### 4. 配 Skipper 连接 Mihomo Party

```bash
defaults write com.z2z63-dev.skipper external_controller_type "TCPIP"
defaults write com.z2z63-dev.skipper external_controller "127.0.0.1:9090"
defaults write com.z2z63-dev.skipper secret ""
defaults write com.z2z63-dev.skipper unix_socket ""
```

重启 HS Skipper。

### 5. 系统权限

首次使用引导：

- **Accessibility**: HS Skipper 检测窗口焦点用
- **Local Network**: macOS 15+ 首次访问 127.0.0.1 会要求授权（如果找不到，先试着用一次让系统登记，再到 Settings → Privacy → Local Network 打开）

## 用法

启动炉石后，HS Skipper 应该自动出现：
- 菜单栏小图标 → 「一键拔线」
- 全屏 HS 右上角红字「一键拔线」浮窗

进 BG 战斗动画时点一下。

## 故障排查

- **Skipper 设置检测失败**：先确认 `curl http://127.0.0.1:9090/version` 能返回 mihomo 版本号。如果不能，Mihomo Party 的 external-controller 没开 TCP（默认是 unix socket）。打开 Mihomo Party 设置→外部控制器→设为 TCP。
- **浮窗不出现**：HS Skipper 没拿到 Accessibility 权限，或者 HS 是全屏 Metal 模式（fork 已修这个 bug，没修说明你用的是 z2z63 上游版本——见 [`skipper/README.md`](skipper/README.md)）。
- **点了没反应**：进 BG **战斗动画**那一刻才能拔；选英雄、商店阶段那条 socket 还没建。
