# Android 端 setup

## 状态

**Skip 协议已在 OnePlus 10T (Android 15, 国际服 HS v35.4.241958) 验证通过**。
当前触发方式：手动 ADB + curl。Overlay App 待写，详见 [`overlay-app/README.md`](overlay-app/README.md)。

## 你需要

- Android 13+ 手机（OnePlus 10T 测试过；Android 11+ Scoped Storage 适用）
- 国际服炉石（`com.blizzard.wtcg.hearthstone`）
- 一台 Mac/PC + ADB（仅 setup + 当前手动触发用；未来 Overlay App 不需要）

## 安装

### 1. ClashMetaForAndroid

从 https://github.com/MetaCubeX/ClashMetaForAndroid/releases/latest 下载 arm64 版：

```bash
adb install cmfa-*.*.*-meta-arm64-v8a-release.apk
```

### 2. 开启 External Controller（关键）

CMFA 默认完全关闭 mihomo API。profile 里写 `external-controller` 也会被覆盖。

**必须手动操作**：
1. 启动 CMFA → 右上 设置（齿轮）
2. 找 **「覆盖 / Override」** 节
3. 把 **「外部控制器 / External Controller」** 开关打开
4. 地址：`0.0.0.0:9090`
5. 密钥：留空
6. 返回

### 3. 导入 profile

CMFA 的内置文件浏览器只看得见自己 sandbox（Android Scoped Storage 限制），所以 `adb push /sdcard/Download/` 的文件 CMFA 选不到。走 URL import：

```bash
# 在 PC 上跑临时 HTTP server 喂 profile
python3 -m http.server 8765 --directory android/cmfa &
adb reverse tcp:8765 tcp:8765
```

在 CMFA 里：
1. 「配置 / Profiles」页 → 右下 「+」 → **「URL」**
2. URL: `http://127.0.0.1:8765/hs-skipper.yaml`
3. 名称：`hs-skipper`
4. 保存 → 在配置列表里点选它（左边圆圈选中）

### 4. 启动 VPN

回 CMFA 主页 → 顶部大开关 → 首次会弹 VPN 授权 → 允许。

OnePlus（OxygenOS）默认对第三方常驻 VPN 友好；小米/华为/OPPO ColorOS 等需要把 CMFA 加电池白名单避免被杀。

## 当前触发拔线（手动）

```bash
# 一次性建立 port forward（避开 Mac 上 mihomo-party 占的 9090）
adb forward tcp:9091 tcp:9090

# 战斗动画开始时跑：
bash android/cmfa/skip.sh
```

`skip.sh` 内容就是：

```bash
curl -s http://127.0.0.1:9091/connections \
  | jq -r '.connections[]
           | select(.metadata.process == "com.blizzard.wtcg.hearthstone"
                    and .metadata.host == "")
           | .id' | head -1 \
  | xargs -I{} curl -X DELETE "http://127.0.0.1:9091/connections/{}"
```

返回 HTTP 204 = 成功。

## 故障排查

- **`Empty reply from server`** 调 API：CMFA 没开 External Controller Override（见步骤 2）
- **`profile doesn't contain proxies`**：CMFA 要求至少 1 个 proxy entry，我们的 profile 里有一个 `dummy` 占位 socks5。如果你改过 profile，确保保留这个 entry
- **filter 找不到 connection**：HS 没在跑、或者 BG 战斗还没开始（战斗 socket 在战斗动画开始那一刻才建）
- **CMFA 文件管理器看不到 hs-skipper.yaml**：Scoped Storage 限制，走 URL import（步骤 3）

## 下一步

写 Overlay App，免去 ADB。详见 [`overlay-app/README.md`](overlay-app/README.md)。
