#!/usr/bin/env bash
# Drives the wear emulator's synthetic Health Services to simulate a
# rough 5-minute workout: warm-up → jog → sprint intervals → cool-down.
#
# Caveat: the synthetic provider runs a hardcoded sawtooth ramp (60→150
# every ~18s) within each phase. So inside a phase the HR oscillates,
# but the overall shape across phases approximates a workout.
#
# Usage:  bash scripts/simulate-workout.sh [emulator-id]
#         (default emulator-5556)

set -euo pipefail

EMU="${1:-emulator-5556}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s $EMU"
HS="com.google.android.wearable.healthservices"

phase() {
  local label="$1" target="$2" duration_sec="$3"
  printf '[%s] %s — target %d BPM for %ds\n' \
    "$(date +%H:%M:%S)" "$label" "$target" "$duration_sec"
  $ADB shell am broadcast -a "whs.synthetic.user.STOP_EXERCISE" "$HS" >/dev/null
  $ADB shell am broadcast \
    -a "whs.synthetic.user.START_EXERCISE" \
    --ei exercise_options_heart_rate "$target" \
    "$HS" >/dev/null
  sleep "$duration_sec"
}

# Make sure synthetic providers are on.
$ADB shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS" "$HS" >/dev/null

phase "Warm-up"           80  30
phase "Steady jog"       130  90
phase "Sprint interval"  170  30
phase "Recovery jog"     120  60
phase "Sprint interval"  170  30
phase "Cool-down"         95  60

echo "[$(date +%H:%M:%S)] Done. Stopping synthetic exercise."
$ADB shell am broadcast -a "whs.synthetic.user.STOP_EXERCISE" "$HS" >/dev/null
