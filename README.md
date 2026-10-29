# Hexagon Chat

一个在 **高通骁龙设备本地运行 GGUF 大模型** 的 Android 客户端，支持 **CPU / Adreno GPU（OpenCL）/ Hexagon NPU（HTP）** 三种后端，基于 [llama.cpp](https://github.com/ggml-org/llama.cpp) 官方 Android 预编译包封装。

App 把 `llama-server` 包进一个很小的原生壳里，外面加了 Material 3 的仪表盘与调参界面；聊天和历史直接复用 llama.cpp 自带的 Web UI。

| 对话 | 历史 |
|---|---|
| ![对话](docs/chat.png) | ![历史](docs/history.png) |

## 功能

- **三种后端**：CPU、Adreno OpenCL、Hexagon HTP（内置 v73/v75/v79/v81/v85 的 skel）。
- **Material 3 界面**：浅色/深色、Top App Bar、启动/停止单选分段控件、卡片、底部导航。
- **实时仪表盘**：SoC / NPU / GPU 识别、RAM 与 Swap、热状态、电池温度、tok/s、张量落点（`HTP 1879 MiB · OpenCL 617 MiB · CPU 497 MiB`）。
- **运行时可调参数**（只显示当前二进制真正支持的 flag）：
  后端、上下文、`-ngl`、Flash Attention、KV 类型、线程、batch/ubatch、并行槽、
  加载模式（`auto/mmap/mlock/mmap+mlock/none/dio`）、KV 卸载、repack、warmup、采样、思考模式、metrics、日志等级。
- **模型管理**：二级菜单，顶部「导入模型」，下面是已导入的 GGUF 列表，当前模型打勾。
- **历史对话**：读取 llama.cpp Web UI 的 IndexedDB；点击打开并继续聊，长按可查看 / 导出 / 删除。
- **JSON 导入导出**：ChatGPT 风格 `[{title, messages:[{role, content}]}]`。
- **显示开关**：回答开始后保持思考过程展开；可隐藏每条消息下面的 tokens/耗时统计。

## 环境要求

- 骁龙设备，Hexagon **v73 及以上**（8 Gen 2 / 8 Gen 3 / 8 Elite 或更新）。NPU 后端依赖 FastRPC 与 unsigned PD，部分 OEM 固件会限制，此时会自动退回 GPU/CPU。
- Android 9+（`minSdk 29`），`arm64-v8a`。
- 自备 GGUF 模型，例如 4B 的 `Q4_0` 文件。**仓库不附带模型。**

## 安装（使用 Release）

1. 从 [Releases](../../releases) 下载 `HexagonChat-1.0-debug.apk`。
2. `adb install -r HexagonChat-1.0-debug.apk`
3. 推送模型：
   ```bash
   adb push Qwen3-4B-Q4_0.gguf /sdcard/Android/data/com.dsh.llamahub/files/models/
   ```
4. 打开 App → 点「启动」→ 切到「对话」。

> 发布的是 **debug 版本**：使用仓库内的 debug keystore 签名，且 `android:debuggable="true"`。
> 仅供测试，不要上架应用商店。详见[签名](#签名)。

## 从源码构建

需要：JDK 17、Android SDK（build-tools + platform 35）、Python 3、`gh`（下载 llama.cpp release）、`adb`。

```powershell
git clone https://github.com/Xinjigalaxy/hexagon-chat.git
cd hexagon-chat
.\prepare-deps.ps1          # 下载 llama.cpp b11980 Snapdragon 包，并从设备拉 libOpenCL.so
.\build_apk.ps1             # 产物：out/HexagonChat.apk
```

`prepare-deps.ps1` 只有在拉取 `/vendor/lib64/libOpenCL.so` 时才需要连接骁龙设备；这个高通闭源库**不会**提交到仓库。

构建流程：`aapt2 compile/link` → `javac` → `d8` → 追加 `classes.dex` 与原生库 → `zipalign` → `apksigner`。

## 签名

Release APK 使用仓库内的 `keystore/debug.keystore` 签名：

- alias `androiddebugkey`，store/key 密码均为 `android`，RSA 2048，`CN=Android Debug`
- 签名方案：**v2 + v3**（不启用 v1，`minSdk 29`）
- 证书 SHA-256：`0f7a4fabfba2a0eab203c182c8ef056a48c1be08b21ce861e51dfe418ac42ee9`

debug keystore 是**有意提交**的，这样发布的 APK 可以复现、也能用同一把 key 覆盖升级。它绝不能用于正式产品。要出正式版：自己生成 keystore、去掉 `android:debuggable`、递增 `versionCode`，再用自己的 key 签名。

## 实现原理

- `app/`：Java 源码（`MainActivity`、`M3`、`HistoryStore`）+ manifest + 图标。
- `assemble.py`：把 aapt2 产物、`classes.dex`、llama.cpp 原生库、`llama-server` 可执行文件（改名 `lib/arm64-v8a/libllamaserver.so`）重新打包。
- App 从 `nativeLibraryDir` 启动 `llama-server`，设置 `LD_LIBRARY_PATH=<lib>:/vendor/lib64`、`ADSP_LIBRARY_PATH=<lib>`，然后通过 `http://127.0.0.1:8080` 驱动它。
- Web UI 由 `llama-server` 自己提供；App 注入少量脚本完成历史读取/删除/导入和若干 UI 修复。

## 隐私

- 无埋点，除 `127.0.0.1` 外不发任何网络请求。
- 对话存在 Web UI 的本地 IndexedDB；导出的 JSON 只写到你通过系统文件选择器指定的位置。

## 已知限制

- 强绑定高通：HTP 后端和 OpenCL 库只适用于骁龙。
- **HTP 不支持量化 KV**（`q4_0`/`q8_0`，缺少 `SET_ROWS`）：App 会拦截并提示改用 f16 或切到 GPU；OpenCL/CPU 下可用。
- 普通应用无法使用 `mlock`（Android `RLIMIT_MEMLOCK` 限制）；想让模型常驻内存请选加载模式 `none`。
- 历史导入导出使用简化的 JSON 结构，不是 ChatGPT 完整的 `mapping` 树。
- 目前是 debug 版本，功能可用但仍有粗糙之处。

## 第三方

- [llama.cpp](https://github.com/ggml-org/llama.cpp)：MIT。构建时下载官方 Android Snapdragon 预编译包。
- 高通 `libOpenCL.so`：从设备提取，不随仓库分发。
- 模型不附带，请分别遵守各模型的许可证。

## 许可证

MIT，见 [LICENSE](LICENSE)。
