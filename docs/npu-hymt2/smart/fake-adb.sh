#!/bin/bash
# fake-adb.sh — stub do adb para os testes OFFLINE do wrapper sig-adb.sh.
# Respostas configuradas por env:
#   FAKE_DEVICES  -> saida de `adb devices`        (default: serial+device)
#   FAKE_MODEL    -> saida de `getprop ro.product.model`
#   FAKE_SHA      -> saida de `sha256sum <file>`
set -u
if [ "${1:-}" = "devices" ]; then
  echo "List of devices attached"
  echo "${FAKE_DEVICES-3B15BD00FVE00000	device}"
  exit 0
fi
# procurar o subcomando shell
args="$*"
case "$args" in
  *getprop*ro.product.model*)
    echo "${FAKE_MODEL:-CPH2747}"; exit 0 ;;
  *sha256sum*)
    echo "${FAKE_SHA:-0000000000000000000000000000000000000000000000000000000000000000}  $3"; exit 0 ;;
  *push*)
    exit 0 ;;
esac
echo "FAKE-ADB: exec"
exit 0
