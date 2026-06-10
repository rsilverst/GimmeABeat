# Dev scratch — emulator + adb commands

Quick reference for testing on the WearOS emulator. Not meant to be polished docs.

## One-time setup after emulator boot

Synthetic HR providers need to be enabled every time the emulator boots:

```sh
adb shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS" com.google.android.wearable.healthservices
```

## Drive the synthetic HR value

**Reality check**: the synthetic provider runs a hardcoded sawtooth — HR
ramps 60→150 BPM in 5-BPM steps every second, then resets and repeats.
The `exercise_options_heart_rate` parameter does *not* hold the value.
Our app's 10-second smoothing window flattens this for matching purposes.

```sh
# walking-ish (lower-energy sawtooth)
adb shell am broadcast -a "whs.synthetic.user.START_WALKING" com.google.android.wearable.healthservices

# running-ish (higher-energy sawtooth)
adb shell am broadcast -a "whs.synthetic.user.STOP_EXERCISE" com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.START_RUNNING" com.google.android.wearable.healthservices

# stop synthetic — HR stops (emulator has no real sensor)
adb shell am broadcast -a "whs.USE_SENSOR_PROVIDERS" com.google.android.wearable.healthservices
```

**Two better paths for realistic test signals:**

1. **Android Studio's Health Services panel** — open the emulator's Extended
   Controls (•••) and find "Health Services" (heart icon). Slider lets you
   set HR to a specific value and hold it. Best for interactive testing.
2. **Bash script that simulates a workout** — drives the synthetic provider
   through phases (warm-up → jog → sprints → cool-down):

   ```sh
   bash scripts/simulate-workout.sh             # uses emulator-5556
   bash scripts/simulate-workout.sh emulator-XX # explicit device
   ```

**On a real watch**: none of this matters. The synthetic provider only
runs when `whs.USE_SYNTHETIC_PROVIDERS` has been broadcast. Real watches
deliver real sensor data through the same `ExerciseClient` callback with
no code or config changes.

## Targeting a specific emulator

If both a phone and wear emulator are running, add `-s <id>`:

```sh
adb devices                    # list with IDs
adb -s emulator-5556 shell ...  # usually wear is 5556 if booted second
```

## Build / install / launch the wear app

```sh
# build only
JAVA_HOME=/Applications/Android\ Studio\ Nightly.app/Contents/jbr/Contents/Home ./gradlew :wear:assembleDebug

# install + launch
adb -s emulator-5556 install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s emulator-5556 shell am start -n com.rsilverst.gimmeabeat/.wear.MainActivity

# uninstall (useful for testing the first-run permission flow)
adb -s emulator-5556 uninstall com.rsilverst.gimmeabeat
```

## Grant the heart-rate permissions without tapping through dialogs

```sh
adb -s emulator-5556 shell pm grant com.rsilverst.gimmeabeat android.permission.BODY_SENSORS
adb -s emulator-5556 shell pm grant com.rsilverst.gimmeabeat android.permission.health.READ_HEART_RATE
```

## Screenshot the watch

```sh
adb -s emulator-5556 exec-out screencap -p > /tmp/wear.png
open /tmp/wear.png
```

## Watch the app's logs

```sh
adb -s emulator-5556 logcat | grep -iE "gimmeabeat|WHS_|HeartRate"
```

## Known emulator gotchas

**Phone emulator must be SDK ≤ 36 for watch↔phone messaging.** The SDK 37 (`sdk_gphone16k_arm64`) Pixel Watch companion app crashes on `BluetoothAdapter.getAddress()` because the phone emulator has no Bluetooth, which blocks Wearable Data Layer message routing. Use a Pixel emulator on API 36 or below. Real phones won't have this problem.

**BODY_SENSORS permission UX on Wear OS 5 emulator is broken** — tapping Allow on the system dialog sometimes fails silently. Workaround during development: grant via `pm grant` (above). Real watches don't have this issue. Permission flow polish is in Stage 6.

## Watch + phone working setup (Stage 2 verified)

- `Wear_OS_Large_Round` (SDK 36)
- A Pixel emulator at SDK 36 (`sdk_gphone64_arm64`, e.g. Pixel_10_Pro)
- Pair them in Studio's Device Manager (Wear OS emulator menu → "Pair Wearable")
- Both apps installed via `assembleDebug` are signed with the same debug keystore — required for the Data Layer routing.

## Inspect Wearable Data Layer connection state

```sh
# from either side, peer node + connection state
adb -s emulator-XXXX shell dumpsys activity service WearableService | grep -iE "connected|peerNode|Name="
```
