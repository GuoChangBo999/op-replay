# OP Replay — openpilot 行车记录 + 车辆状态融合播放器

把 openpilot 的 `fcamera.hevc`（视频）和 `qlog.zst`（车辆信号日志）
融合成一个 App：视频播放的同时，显示车速 / 油门 / 刹车 / 方向盘时间轴。

---

## 一、它能做什么

1. **本地文件**：点「选视频」「选日志」，选好后点「播放」→ 融合显示。
2. **连接设备**：
   - 输入 `IP:22`（SSH）→ 浏览车机目录 → 点选文件下载 → 播放。
   - 输入 `IP:5088`（openpilot API）→ 显示设备状态（该端口不列文件）。
3. **解析**：App 内置**纯 Python 解析器**，直接在手机上解析 `qlog.zst`，
   不需要车机、不需要电脑。（已与官方解析器逐条对拍：598/598 完全一致）

---

## 二、构建 APK 的步骤

本工程需要 **Android Studio**（本机没有 Android SDK，无法命令行构建）。

1. 用 Android Studio 打开 `app-project/` 目录（Open an existing project）。
2. 等待 Gradle Sync（首次会下载依赖：AGP 8.5.2 / Kotlin 1.9.24 /
   Chaquopy 15.0.1 / sshj 0.38.0）。
3. 菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。
4. 产物：`app/build/outputs/apk/debug/app-debug.apk`。
5. 传到手机安装即可（需允许「安装未知来源应用」）。

### 依赖说明
- **Chaquopy 15.0.1**：Android 里跑 Python。只需要 pip 包 `zstandard`
  （有官方 wheel），解析逻辑是我们自己写的纯 Python，不需要 pycapnp。
- **sshj 0.38.0**：SSH/SFTP 浏览 + 下载车机文件。

---

## 三、关于视频格式（重要）

openpilot 的 `fcamera.hevc` 是 **HEVC 裸码流**，WebView 通常**不能直接播放裸流**。

**两种处理办法：**

- **A（推荐）**：先把它转成 mp4 再放进 App。
  电脑/车机上执行：
  ```
  ffmpeg -fflags +genpts -r 20 -i fcamera.hevc -c:v copy fcamera.mp4
  ```
  （`-c:v copy` 无损、秒转；若播放器不认再加 `-c:v libx264`）
- **B**：先试直接选 `.hevc`。部分机型/WebView 可能能播，不能播就用 A。

> 说明：App 里的 `<video>` 播放的是 mp4。信号时间轴（60.0s）与视频（59.9s）
> 几乎完全对齐，无需手动同步。

---

## 四、SSH 连接说明

- 用户名默认 `comma`，端口 `22`。
- 密钥：把私钥**内容**（`-----BEGIN OPENSSH PRIVATE KEY-----` 整段）
  粘到界面的「SSH 私钥」框里。
- 车机 route 目录一般是：
  `/data/media/0/realdata/<route>/`（里面是 `fcamera.hevc` / `qlog.zst` 等）。
- 留空密钥则回退用密码认证（默认密码 = `comma`）。

---

## 五、解析器原理（已验证）

`app/src/main/python/op_parser.py` 是一个**自包含的 Cap'n Proto 解码器**：

- qlog.zst → zstd 解压 → 一串 Cap'n Proto 消息；
- 每条消息头：`4 + 4*段数` 字节，向上对齐到 8；
- 定位 Event.union 中 `carState`（判别符 = 21）；
- 从 struct 指针读 CarState 数据段：
  `vEgo@0 gas@4 brake@12 steer@16 aEgo@20`，`gasPressed@bit8.0 brakePressed@bit8.1`。

对拍结果：与 pycapnp + 官方 schema 逐条比对 **598/598 全一致**。

---

## 六、已知限制

- 「IP:5088 列文件」：该端口只提供状态，不列目录——列文件请用端口 22（SSH）。
- 首次 SSH 连接会信任主机密钥（PromiscuousVerifier），仅建议在局域网使用。
- 大视频（75MB）复制/下载会有几秒等待。
