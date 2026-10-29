# Third-party notices

## llama.cpp

This project bundles and executes binaries from the official
[llama.cpp](https://github.com/ggml-org/llama.cpp) Android arm64 Snapdragon
release (`b11980`). llama.cpp is licensed under the MIT License.
The binaries and the embedded Web UI are downloaded by `prepare-deps.ps1`
and are not part of this repository's source.

## Qualcomm OpenCL loader

`libOpenCL.so` is extracted from the target device's `/vendor/lib64` by
`prepare-deps.ps1` and is **not** redistributed in this repository.

## Models

No model weights are included. GGUF models are subject to their own licenses
(e.g. Apache-2.0, Gemma Terms of Use, Llama Community License). Check before
redistributing any quantized file.

## AndroidX / Material

Not used. The Material 3 look is implemented with framework widgets and the
`M3.java` token helper, so the APK has no AndroidX dependency.
