#!/usr/bin/env bash
# tools/shot.sh NAME [cmd [s]]...  -> send debug commands (each "cmd|s"), settle, capture .scratch/shots/NAME.png
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; DEV=${DEV:-emulator-5560}; PKG=io.embodit.lightplanner
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
name="$1"; shift
for c in "$@"; do n="${c%%|*}"; s="${c#*|}"; [ "$s" = "$c" ] && s=""; MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd $n ${s:+--es s '$s'}" >/dev/null 2>&1; sleep "${GAP:-1}"; done
sleep "${PRE:-1}"
bash "$ROOT/tools/harness/emu.sh" fast on >/dev/null 2>&1; sleep "${SETTLE:-6}"
SH="$ROOT/.scratch/shots"; mkdir -p "$SH/emu"; before=$(ls -1 "$SH/emu" | wc -l)
adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
for i in $(seq 1 12); do sleep 0.5; [ "$(ls -1 "$SH/emu" | wc -l)" -gt "$before" ] && break; done; sleep 0.5
newest=$(ls -1t "$SH/emu"/*.png | head -1); mv -f "$newest" "$SH/$name.png" && echo "OK $name"
bash "$ROOT/tools/harness/emu.sh" fast off >/dev/null 2>&1
