# Steam Controller — Implementation TODO

Tracking the remaining work from the original implementation in commits `00b07d0c` and `1ed8b3f3`.
Reference issue: https://github.com/moonlight-stream/moonlight-android/issues/1062

All changes are isolated to:
- [`SteamController.java`](app/src/main/java/com/limelight/binding/input/driver/SteamController.java)
- [`BluetoothDriverService.java`](app/src/main/java/com/limelight/binding/input/driver/BluetoothDriverService.java)

---

## 1. Grip button mapping

**Status:** `TODO`

**Problem:** `BLEButtonChunk3` (type flag `0x40`) is read but all 3 bytes are silently discarded:
```java
// SteamController.java:236–240
if((type & BLEButtonChunk3) != 0) {
    byte[] buttons = new byte[3];
    buffer.get(buttons); // data is never used
}
```
The left grip and right grip buttons live in these bytes. Without parsing them they are always reported as unpressed.

**What to do:**
Parse the 3 bytes and map to `PADDLE1_FLAG` / `PADDLE2_FLAG` from `ControllerPacket`. The bit positions need to be confirmed against the SDL source:
[`SDL_hidapi_steam.c`](https://github.com/libsdl-org/SDL/blob/main/src/joystick/hidapi/SDL_hidapi_steam.c) — search for `k_EBLEButtonChunk3`.

Based on the SDL reference implementation, the expected mapping is:
- Byte 0, bit 0 → Left grip → `PADDLE1_FLAG`
- Byte 0, bit 1 → Right grip → `PADDLE2_FLAG`

```java
if((type & BLEButtonChunk3) != 0) {
    byte[] buttons = new byte[3];
    buffer.get(buttons);
    long b = Byte.toUnsignedLong(buttons[0]) | Byte.toUnsignedLong(buttons[1]) << 8 | Byte.toUnsignedLong(buttons[2]) << 16;
    setButtonFlag(ControllerPacket.PADDLE1_FLAG, (int)(b & 0x00000001)); // left grip
    setButtonFlag(ControllerPacket.PADDLE2_FLAG, (int)(b & 0x00000002)); // right grip
}
```

**Note:** `PADDLE1_FLAG`–`PADDLE4_FLAG` are "extended buttons (Sunshine only)" per `ControllerPacket.java:21–24` — they will only function when streaming via Sunshine, not NVIDIA GameStream.

**Verify** the bit mapping by enabling the commented-out verbose log in `onCharacteristicChanged` and pressing grips while watching logcat, then compare against the SDL source.

---

## 2. Controller shows as Xbox 360, not Steam Controller

**Status:** `TODO`

**Problem:** `SteamController` is constructed with zeroed-out vendor/product IDs and the `AbstractController` fields `type`, `capabilities`, and `supportedButtonFlags` all default to `0`:

```java
// SteamController.java:140
super(0x1234, listener, 0, 0); // vendorId=0, productId=0
// type = 0 → LI_CTYPE_UNKNOWN
// capabilities = 0 → nothing advertised
// supportedButtonFlags = 0 → nothing advertised
```

`ControllerHandler.UsbDeviceContext.sendControllerArrival()` sends these to `conn.sendControllerArrivalEvent()`:
```java
// ControllerHandler.java:3260–3262
conn.sendControllerArrivalEvent((byte)controllerNumber, getActiveControllerMask(),
        device.getType(), device.getSupportedButtonFlags(), device.getCapabilities());
```

**Note:** VID/PID are stored in the context (lines 571–572) but are **not** forwarded to the host arrival event. What the host uses to identify the controller is solely `type`, `capabilities`, and `supportedButtonFlags`. With all three at `0`, the host has no capability information and defaults to a generic Xbox layout.

**What to do:**

Set correct values in the `SteamController` constructor or `probeService()` (after confirmed connected). Also update VID/PID for correctness (Steam Controller BLE values):

```java
// Real Steam Controller BLE VID/PID
super(0x1234, listener, 0x28de, 0x1102);

// In constructor or probeService(), set on AbstractController fields:
this.type = MoonBridge.LI_CTYPE_UNKNOWN; // SC has no standard mapping; keep UNKNOWN
                                          // so Sunshine uses its own SC profile
this.capabilities = MoonBridge.LI_CCAP_ANALOG_TRIGGERS; // add RUMBLE once implemented
this.supportedButtonFlags =
    ControllerPacket.A_FLAG | ControllerPacket.B_FLAG |
    ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG |
    ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG |
    ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG |
    ControllerPacket.LB_FLAG | ControllerPacket.RB_FLAG |
    ControllerPacket.LS_CLK_FLAG | ControllerPacket.RS_CLK_FLAG |
    ControllerPacket.PLAY_FLAG | ControllerPacket.BACK_FLAG |
    ControllerPacket.SPECIAL_BUTTON_FLAG |
    ControllerPacket.PADDLE1_FLAG | ControllerPacket.PADDLE2_FLAG; // grips (after #1 is done)
```

**Note:** `AbstractController.type`, `capabilities`, and `supportedButtonFlags` are `protected` fields — they are directly writable from `SteamController`. `sendControllerArrivalEvent` is not called until the first input packet arrives (via `assignControllerNumberIfNeeded` → `sendControllerArrival` in `ControllerHandler.java:557–561`), so setting these in the constructor is safe.

---

## 3. Inputs not reaching the host on Android 13+

**Status:** `TODO` — investigate

**Problem:** `@pattontim` (GitHub issue) reported that on Android 16 the controller exits lizard mode and GATT events appear in the log, but no inputs show on the Moonlight overlay. The most likely cause is deprecated BLE APIs introduced in Android 13 (API 33).

**Deprecated APIs in use (three call sites):**

| Location | Deprecated method | Replacement (API 33+) |
|---|---|---|
| `Callback.onCharacteristicChanged` (line 100) | `onCharacteristicChanged(gatt, chr)` — `characteristic.getValue()` may return stale data | Override `onCharacteristicChanged(gatt, chr, byte[] value)` |
| `GattOperation.run()` (CHR_WRITE, line 336–337) | `chr.setValue(value)` + `gatt.writeCharacteristic(chr)` → returns `boolean` | `gatt.writeCharacteristic(chr, value, writeType)` → returns `int` |
| `onDescriptorWrite()` direct write (line 121–122) | `reportChr.setValue(enterValveMode)` + `gatt.writeCharacteristic(reportChr)` | Same replacement as CHR_WRITE |
| `GattOperation.run()` (ENABLE_NOTIFICATION, line 363–364) | `cccd.setValue(value)` + `gatt.writeDescriptor(cccd)` | `gatt.writeDescriptor(cccd, value)` |

**Important:** The `onDescriptorWrite` direct write (line 121–122) bypasses the `GattOperation` queue and is NOT covered by fixing `GattOperation.run()` alone. Both call sites must be updated.

**Fix for `onCharacteristicChanged`:**
```java
// Override both — old one for <API33, new one for 33+
@Override
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
    // called on <API33
    if (characteristic.getUuid().equals(inputCharacteristic)) {
        handleRead(ByteBuffer.wrap(characteristic.getValue()));
    }
}

@Override
@RequiresApi(33)
public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
    // called on API33+ — value param is reliable, do NOT call characteristic.getValue()
    if (characteristic.getUuid().equals(inputCharacteristic)) {
        handleRead(ByteBuffer.wrap(value));
    }
}
```

**Fix for `writeCharacteristic` / `writeDescriptor`:** Wrap both old and new APIs behind a version check in `GattOperation.run()` AND in `onDescriptorWrite()`. Old API (`boolean`) stays for pre-33; new API (`int`, returns `GATT_SUCCESS = 0`) for 33+.

**Diagnosis step first:** Enable the commented-out verbose logs in `onCharacteristicChanged` and `GattOperation.run()` and reproduce on Android 13+ to confirm whether the issue is at the read path or the write path (enterValveMode not completing).

---

## 4. Rumble / haptic feedback

**Status:** `TODO`

**Problem:** Both `rumble()` and `rumbleTriggers()` are stubs:
```java
// SteamController.java:278–285
@Override
public void rumble(short lowFreqMotor, short highFreqMotor) {
    // TODO: Implement rumble
}

@Override
public void rumbleTriggers(short leftTrigger, short rightTrigger) {
    // TODO: Implement rumble
}
```

**Context:** The Steam Controller has no traditional motors. It simulates rumble using the trackpad haptic actuators. The haptic command is sent by writing a feature report to the `reportCharacteristic` UUID (`100F6C34-...`), the same characteristic used for `enterValveMode`.

The command format (from SDL source `SDL_hidapi_steam.c`, function `HIDAPI_DriverSteam_RumbleJoystick`):
```
byte[0] = 0x8f  (haptic feedback command)
byte[1] = 0x07  (payload length)
byte[2] = left_amplitude_lo
byte[3] = left_amplitude_hi
byte[4] = right_amplitude_lo
byte[5] = right_amplitude_hi
byte[6] = period_lo   (haptic period in microseconds, controls frequency)
byte[7] = period_hi
```

**Verify** the exact byte format against:
- [`SDL_hidapi_steam.c`](https://github.com/libsdl-org/SDL/blob/main/src/joystick/hidapi/SDL_hidapi_steam.c) — search `HIDAPI_DriverSteam_RumbleJoystick`
- [`controller_structs.h`](https://github.com/libsdl-org/SDL/blob/main/src/joystick/hidapi/steam/controller_structs.h) — haptic struct definitions

**Implementation sketch:**
```java
@Override
public void rumble(short lowFreqMotor, short highFreqMotor) {
    if (!isRegistered()) return;
    // Map motor values to haptic amplitude (both trackpads)
    // SDL uses ~period 1000µs at max amplitude
    byte[] hapticCmd = new byte[] {
        (byte)0x8f, (byte)0x07,
        (byte)(lowFreqMotor & 0xFF),  (byte)(lowFreqMotor >> 8),   // left pad
        (byte)(highFreqMotor & 0xFF), (byte)(highFreqMotor >> 8),  // right pad
        (byte)0xe8, (byte)0x03  // period ~1000µs
    };
    writeCharacteristic(reportCharacteristic, hapticCmd);
}
```

`rumbleTriggers()` can be a no-op or delegate to `rumble()` — the SC has no trigger haptics.

After implementing, add `MoonBridge.LI_CCAP_RUMBLE` to `capabilities` (see item #2).

---

## 5. Reconnect and disconnect handling

**Status:** `TODO`

**Problem A — reconnect is a stub:**
```java
// SteamController.java:287–289
public void reconnect() {
    // TODO: Implement reconnect
}
```
This is called by `BluetoothDriverService.connectBluetoothDevice()` when the same device address is seen again after a disconnect. Without it, the controller cannot recover mid-session.

**Problem B — stop() is empty:**
```java
// SteamController.java:272–275
@Override
public void stop() {
    // nothing
}
```
`stop()` is called by `BluetoothDriverService.disconnectBluetoothDevice()` → `device.stop()`. Without closing the GATT connection here, the BLE stack holds the connection open and Android may refuse to re-connect.

**Design: reconnect inside the GATT callback**

Use a `mShouldReconnect` flag so that `reconnect()` signals intent and the actual new connection is established inside `onConnectionStateChange`, *after* the old GATT is fully torn down. This avoids a spurious `notifyDeviceRemoved()` event being sent to the host mid-reconnect.

Add the field:
```java
private volatile boolean mShouldReconnect = false;
```

**Fix for `stop()`:**
```java
@SuppressLint("MissingPermission")
@Override
public void stop() {
    mShouldReconnect = false;
    if (mGatt != null) {
        mGatt.disconnect();
        mGatt.close();
        mGatt = null;
    }
    mIsRegistered = false;
}
```

**Fix for `reconnect()`:**
```java
@SuppressLint("MissingPermission")
public void reconnect() {
    mIsRegistered = false;
    mShouldReconnect = true;
    if (mGatt != null) {
        mGatt.disconnect(); // triggers onConnectionStateChange(STATE_DISCONNECTED)
    } else {
        mShouldReconnect = false;
        mGatt = connectGatt(false);
    }
}
```

**Fix for `onConnectionStateChange`:** Handle disconnection and branch on the flag:
```java
@Override
public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
    if (newState == BluetoothProfile.STATE_CONNECTED) {
        mHandler.post(() -> mGatt.discoverServices());
    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        if (mShouldReconnect) {
            // Intentional reconnect: close old GATT then open a fresh connection.
            // Do NOT notify removal — the controller stays registered with the host.
            mShouldReconnect = false;
            mGatt.close();
            mGatt = connectGatt(false);
        } else {
            // Unexpected disconnect (or stop() already called): clean up.
            mIsRegistered = false;
            notifyDeviceRemoved();
        }
    }
}
```

**Note:** The existing numeric literals `2` / `0` for `newState` should be replaced with `BluetoothProfile.STATE_CONNECTED` / `BluetoothProfile.STATE_DISCONNECTED` as shown above.

---

## 6. Verbose production logging in handleRead()

**Status:** `TODO`

**Problem:** Four active `LimeLog.info()` calls in `handleRead()` fire on every input report:
```java
// SteamController.java:226, 232, 246, 258
LimeLog.info("Buttons: "+Long.toBinaryString(b));
LimeLog.info("Triggers: "+left+" | "+right);
LimeLog.info("Joystick: "+x+" | "+y);
LimeLog.info("Right Pad: "+x+" | "+y);
```
At 60 Hz input rate this generates ~240 log lines/second in production.

**Fix:** Change all four from `LimeLog.info` to `LimeLog.verbose` so they only appear when verbose logging is enabled.

---

## Suggested implementation order

1. **#6 (verbose logging)** — one-liner fix, no risk, do it first
2. **#5 (reconnect/stop)** — self-contained, unblocks session stability testing
3. **#3 (deprecated APIs)** — needed to confirm the stack works correctly on current Android versions; remember to fix both `GattOperation.run()` and the direct write in `onDescriptorWrite`
4. **#2 (controller identity)** — sets correct type/capabilities/supportedButtonFlags so the host can identify the SC
5. **#1 (grip buttons)** — small parse change, verify bit positions before merging
6. **#4 (rumble)** — most uncertainty in the byte format; implement last once the rest is stable
