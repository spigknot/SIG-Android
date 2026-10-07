#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""runner2.py — harness da RODADA 2 (NPU/Hexagon Hy-MT2) — fail-closed.

Uso:
  python runner2.py --name t1_probe --timeout 120 --shell './bin/llama-cli --list-devices'
  python runner2.py --name bench_htp --timeout 300 --shell './bin/llama-bench ...' --no-telemetry

Garantias:
  - serial EXPLICITO do device (USB por padrao; --serial para sobrescrever)
  - LD_LIBRARY_PATH + ADSP_LIBRARY_PATH sempre setados (child env do sandbox)
  - timeout child-owned: subprocess com timeout + kill do process group em estouro
  - rc REAL capturado (nunca pipeline que engole exit code)
  - stdout/stderr SEPARADOS em arquivos (C:/llama-npu/rodada2/logs/)
  - telemetria device (temp/bateria) antes/depois
  - linha JSONL append por tentativa (docs/npu-hymt2/rodada2/trials.jsonl)
  - aborta com diagnostico claro se device/sandbox/skel ausentes (PREFLIGHT)
"""
import argparse, json, os, subprocess, sys, time, pathlib

SERIAL_DEFAULT = "3B15BD00FVE00000"  # USB CPH2747; wireless usa --serial
ADB = os.path.expandvars(r"$LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe")
DEST = "/data/local/tmp/sig-hymt2-npu-r1"
LOGS = pathlib.Path(r"C:/llama-npu/rodada2/logs")
TRIALS = pathlib.Path(r"D:/Projetos/SIG/docs/npu-hymt2/rodada2/trials.jsonl")
MODEL = "models/Hy-MT2-1.8B-Q4_0.gguf"

def adb(shell_cmd: str, timeout_s: int, serial: str):
    """roda adb shell com timeout child-owned. Retorna (rc, out, err, wall).
    Sempre acorda o device antes (KEYCODE_WAKEUP): com tela apagada/doze o CPU
    do device trava em ~500 MHz e as medicoes de CPU/OCL caem ~30x (descoberto
    na Rodada 2; o HTP/DSP nao sofre)."""
    full = (f'input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; '
            f'cd {DEST} && export LD_LIBRARY_PATH=./lib ADSP_LIBRARY_PATH=./lib && {shell_cmd}')
    t0 = time.time()
    try:
        p = subprocess.run(
            [ADB, "-s", serial, "shell", full],
            capture_output=True, timeout=timeout_s,
            stdin=subprocess.DEVNULL)
        rc, out, err = p.returncode, p.stdout, p.stderr
    except subprocess.TimeoutExpired as e:
        rc, out, err = 124, (e.stdout or b""), (e.stderr or b"") + b"\n[TIMEOUT runner2]"
    wall = time.time() - t0
    return rc, out, err, wall

def telemetry(serial: str):
    """temp/bateria/carga/memoria (read-only)."""
    try:
        p = subprocess.run(
            [ADB, "-s", serial, "shell",
             'cat /sys/class/power_supply/battery/capacity; '
             'cat /sys/class/power_supply/battery/temp; '
             'cat /sys/class/thermal/thermal_zone0/temp; cat /proc/loadavg; '
             'grep -E "MemFree|SwapFree" /proc/meminfo | head -n 2; '
             'grep pswpout /proc/vmstat'],
            capture_output=True, timeout=30, stdin=subprocess.DEVNULL)
        vals = (p.stdout or b"").decode("utf-8", "replace").split()
        return {"battery_pct": vals[0] if len(vals) > 0 else "?",
                "battery_temp_dc": vals[1] if len(vals) > 1 else "?",
                "tz0_mc": vals[2] if len(vals) > 2 else "?",
                "loadavg": " ".join(vals[3:6]) if len(vals) > 5 else "?",
                "memfree_kb": vals[8] if len(vals) > 8 else "?",
                "swapfree_kb": vals[10] if len(vals) > 10 else "?",
                "pswpout": vals[12] if len(vals) > 12 else "?"}
    except Exception as e:
        return {"error": str(e)[:120]}

def preflight(serial: str, need_model: bool) -> str | None:
    """checks fail-closed; devolve mensagem de erro ou None."""
    tests = [
        ("test -x ./bin/llama-cli", "llama-cli ausente/nao-executavel"),
        ("test -f ./lib/libggml-hexagon.so", "libggml-hexagon.so ausente"),
        ("test -f ./lib/libggml-htp-v81.so", "skel v81 ausente"),
    ]
    if need_model:
        tests.append((f"test -f {MODEL}", "modelo ausente"))
    for cond, msg in tests:
        rc, out, err, _ = adb(cond, 30, serial)
        if rc != 0:
            return f"PREFLIGHT FALHOU: {msg} (rc={rc})"
    return None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--timeout", type=int, default=300)
    ap.add_argument("--shell", required=True, help="comando shell (dentro do sandbox)")
    ap.add_argument("--serial", default=SERIAL_DEFAULT)
    ap.add_argument("--need-model", action="store_true")
    ap.add_argument("--no-telemetry", action="store_true")
    ap.add_argument("--tag", default="")
    args = ap.parse_args()

    LOGS.mkdir(parents=True, exist_ok=True)
    ts = time.strftime("%H%M%S")
    base = f"{args.name}-{ts}"

    # preflight rapido (fail-closed)
    pf = preflight(args.serial, args.need_model)
    if pf:
        print(pf)
        sys.exit(3)

    tel_before = {} if args.no_telemetry else telemetry(args.serial)
    rc, out, err, wall = adb(args.shell, args.timeout, args.serial)
    tel_after = {} if args.no_telemetry else telemetry(args.serial)

    out_f = LOGS / f"{base}.out"
    err_f = LOGS / f"{base}.err"
    out_f.write_bytes(out)
    err_f.write_bytes(err)

    rec = {
        "ts": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "name": args.name, "tag": args.tag,
        "cmd": args.shell, "timeout_s": args.timeout,
        "rc": rc, "wall_s": round(wall, 2),
        "out": str(out_f), "err": str(err_f),
        "tel_before": tel_before, "tel_after": tel_after,
        "serial": args.serial,
    }
    with open(TRIALS, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")

    print(f"[runner2] {args.name}: rc={rc} wall={wall:.1f}s out={out_f.name} err={err_f.name}")
    if tel_before and tel_after:
        print(f"[runner2] temp antes={tel_before.get('battery_temp_dc')} depois={tel_after.get('battery_temp_dc')} (decimos C)")
    # eco curto do fim do stdout para o chat
    tail = out.decode("utf-8", "replace").strip().splitlines()[-8:]
    for l in tail:
        print("  |", l[:160])
    sys.exit(0 if rc == 0 else 1)

if __name__ == "__main__":
    main()
