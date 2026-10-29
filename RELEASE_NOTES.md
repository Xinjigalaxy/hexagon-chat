# Hexagon Chat 1.0（debug）

在高通骁龙设备本地运行 GGUF 模型：CPU / Adreno GPU / **Hexagon NPU**，基于 llama.cpp b11980。

## 下载

- `HexagonChat-1.0-debug.apk`（arm64-v8a，Android 9+）
- SHA-256：`BD05733CFF3D1280A6019ED541AA9331C3F5E36125790A9CEFD6FC8A9674778A`

## 本次内容

- Material 3 界面（浅色/深色），顶部栏：新对话 / 模型 / 启动–停止单选分段
- 实时仪表盘：SoC、NPU/GPU 识别、RAM/Swap、热状态、电池、tok/s、张量落点
- 运行时可调当前二进制支持的全部参数（上下文、`-ngl`、FA、KV 类型、batch、并行槽、加载模式、采样、思考模式、metrics、日志等级）
- 模型管理：导入 + 切换 GGUF
- 历史对话：读取 Web UI 的 IndexedDB，点击继续聊，长按查看 / 导出 / 删除
- JSON 导入导出（ChatGPT 风格 `[{title, messages}]`）
- 可选：回答开始后保持思考过程展开；隐藏每条消息的 tokens/耗时统计

## 安装

```bash
adb install -r HexagonChat-1.0-debug.apk
adb push your-model-Q4_0.gguf /sdcard/Android/data/com.dsh.llamahub/files/models/
```

打开 App，点「启动」，再切到「对话」。

## 说明

- **debug 版本**：使用仓库内的 debug keystore 签名（`CN=Android Debug`），且 `android:debuggable="true"`。仅供测试，不要上架。
- 不附带模型；NPU 后端建议骁龙 8 Gen 2 及以上。
- Hexagon 上不支持量化 KV（`q4_0`/`q8_0`，缺 `SET_ROWS`），请用 f16 或切到 GPU。
- 普通 Android 应用无法使用 `mlock`，需要模型常驻请选加载模式 `none`。
- 基于 llama.cpp `b11980`；Web UI 与原生库来自其官方 Snapdragon 预编译包。

## 校验

```bash
apksigner verify --print-certs HexagonChat-1.0-debug.apk
# 证书 SHA-256 0f7a4fabfba2a0eab203c182c8ef056a48c1be08b21ce861e51dfe418ac42ee9
```
