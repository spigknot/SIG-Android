
import hashlib, os
def sh(p):
    if not os.path.exists(p): return "AUSENTE"
    return hashlib.sha256(open(p,"rb").read()).hexdigest()[:24]
print("instalado (pull)   :", sh("C:/llama-npu/rodada2/r18-pull/libggml-htp-v81.so"))
print("instalado (execout):", sh("C:/llama-npu/rodada2/r18-pull/htp-v81-installed-execout.so"))
print("backup v12         :", sh("C:/llama-npu/rodada2/jnilibs-v12-backup/libggml-htp-v81.so"))
print("kqon-libs          :", sh("C:/llama-npu/rodada2/kqon-libs/libggml-htp-v81.so"))
print("jniLibs atual      :", sh("C:/npu-probe/app/src/main/jniLibs/arm64-v8a/libggml-htp-v81.so"))
print("apk build          :", sh("C:/npu-probe/app/build/outputs/apk/debug/app-debug.apk"))
print()
for f in ["libggml-htp-v81.so","libllama.so","libggml-hexagon.so"]:
    print(f"{f}: v12bk={sh('C:/llama-npu/rodada2/jnilibs-v12-backup/'+f)} kqon={sh('C:/llama-npu/rodada2/kqon-libs/'+f)} jni={sh('C:/npu-probe/app/src/main/jniLibs/arm64-v8a/'+f)}")
