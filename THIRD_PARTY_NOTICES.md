# 第三方说明

## llama.cpp

本项目打包并运行 [llama.cpp](https://github.com/ggml-org/llama.cpp) 官方
Android arm64 Snapdragon 预编译包（`b11980`）。llama.cpp 使用 MIT 许可证。
二进制与内嵌 Web UI 由 `prepare-deps.ps1` 下载，不包含在本仓库源码中。

## 高通 OpenCL 库

`libOpenCL.so` 由 `prepare-deps.ps1` 从目标设备的 `/vendor/lib64` 提取，
**不随本仓库分发**。

## 模型

仓库不包含任何模型权重。GGUF 模型受各自许可证约束
（如 Apache-2.0、Gemma Terms of Use、Llama Community License），分发量化文件前请自行确认。

## AndroidX / Material

未使用。Material 3 外观由 framework 控件加 `M3.java` 的 token 实现，
APK 不依赖 AndroidX。
