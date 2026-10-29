import os
import sys
import zipfile

BASE_APK = sys.argv[1]
DEX = sys.argv[2]
LLAMA_ROOT = sys.argv[3]
VENDOR_DIR = sys.argv[4]
OUT = sys.argv[5]

LIB_DIR = os.path.join(LLAMA_ROOT, "lib")
BIN_DIR = os.path.join(LLAMA_ROOT, "bin")

LIBS = [
    "libllama-server-impl.so",
    "libllama-common.so",
    "libmtmd.so",
    "libllama.so",
    "libggml.so",
    "libggml-base.so",
    "libggml-cpu.so",
    "libggml-rpc.so",
    "libggml-opencl.so",
    "libggml-hexagon.so",
    "libggml-htp-v73.so",
    "libggml-htp-v75.so",
    "libggml-htp-v79.so",
    "libggml-htp-v81.so",
    "libggml-htp-v85.so",
]

if os.path.exists(OUT):
    os.remove(OUT)

with open(BASE_APK, "rb") as f:
    data = f.read()
with open(OUT, "wb") as f:
    f.write(data)

missing = []
with zipfile.ZipFile(OUT, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(DEX, "classes.dex")

    exe = os.path.join(BIN_DIR, "llama-server")
    if not os.path.isfile(exe):
        raise SystemExit("missing " + exe)
    z.write(exe, "lib/arm64-v8a/libllamaserver.so")

    for name in LIBS:
        p = os.path.join(LIB_DIR, name)
        if not os.path.isfile(p):
            missing.append(name)
            continue
        z.write(p, "lib/arm64-v8a/" + name)

    # Qualcomm OpenCL loader: pulled from the target device by prepare-deps.ps1
    p = os.path.join(VENDOR_DIR, "libOpenCL.so")
    if os.path.isfile(p):
        z.write(p, "lib/arm64-v8a/libOpenCL.so")
    else:
        missing.append("libOpenCL.so (vendor)")

if missing:
    raise SystemExit("missing libs: " + ", ".join(missing))

print("assembled %.1f MB -> %s" % (os.path.getsize(OUT) / 1e6, OUT))
