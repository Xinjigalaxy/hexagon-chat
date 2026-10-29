# Hexagon Chat 1.0 (debug)

Local GGUF chat on Qualcomm Snapdragon — CPU / Adreno GPU / **Hexagon NPU**, built on llama.cpp b11980.

## Download

- `HexagonChat-1.0-debug.apk` (arm64-v8a, Android 9+)
- SHA-256: `BD05733CFF3D1280A6019ED541AA9331C3F5E36125790A9CEFD6FC8A9674778A`

## What's inside

- Material 3 UI (light/dark), top bar with New chat / Models / Start–Stop segmented control
- Live dashboard: SoC, NPU/GPU detection, RAM/swap, thermal, battery, tok/s, tensor placement
- Runtime tuning for every flag supported by the bundled binary (ctx, `-ngl`, FA, KV types, batch, slots, load mode, sampling, reasoning, metrics, log level)
- Model manager with import + switch
- History from the llama.cpp Web UI: open to continue, long-press to view / export / delete
- JSON import/export (ChatGPT-style `[{title, messages}]`)
- Optional: keep reasoning expanded after the answer starts; hide per-message token stats

## Install

```bash
adb install -r HexagonChat-1.0-debug.apk
adb push your-model-Q4_0.gguf /sdcard/Android/data/com.dsh.llamahub/files/models/
```

Open the app, press Start, then Chat.

## Notes

- **Debug build**: signed with the bundled debug keystore (`CN=Android Debug`) and `android:debuggable="true"`. Testing only — not for Play Store.
- No model is bundled; Snapdragon 8 Gen 2+ recommended for the NPU backend.
- Quantized KV cache (`q4_0`/`q8_0`) is blocked on Hexagon (missing `SET_ROWS`); use f16 or switch to GPU.
- `mlock` is not available to normal Android apps; use load mode `none` for a fully resident model.
- Built against llama.cpp `b11980`; the Web UI and native libraries come from its official Snapdragon release.

## Verify

```bash
apksigner verify --print-certs HexagonChat-1.0-debug.apk
# signer SHA-256 0f7a4fabfba2a0eab203c182c8ef056a48c1be08b21ce861e51dfe418ac42ee9
```
