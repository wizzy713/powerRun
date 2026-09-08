# PowerRun - Real-Time BLE Biometric & Power Telemetry System

A complete embedded and mobile telemetry platform designed for athletic tracking, portable power monitoring, and wearable performance diagnostics. PowerRun captures real-time electrical power data (voltage, current, power draw, capacity reserve) and biometric vitals (heart rate BPM and pulse rhythm) on an **ESP32-C3**, streaming updates at **5 Hz over Bluetooth Low Energy (BLE)** to a dedicated **Android companion application** or any **Web Bluetooth-enabled browser**.

> **Note**: This repository contains the **pure Bluetooth Low Energy (BLE)** architecture of PowerRun. All legacy Wi-Fi and WebSocket implementations have been deliberately excluded in favor of BLE to minimize battery draw, eliminate router/hotspot dependencies, and enable immediate point-to-point pairing outdoors.

---

## Table of Contents

- [System Architecture](#system-architecture)
- [Key Features](#key-features)
- [Repository Structure](#repository-structure)
- [Hardware Setup & Pinout](#hardware-setup--pinout)
  - [I2C Bus Pin Selection (ESP32-C3)](#i2c-bus-pin-selection-esp32-c3)
  - [INA219 Power Monitor Wiring](#ina219-power-monitor-wiring)
  - [MAX30102 Heart Rate Sensor Wiring](#max30102-heart-rate-sensor-wiring)
  - [Alternative: Analog Pulse Sensor (GPIO 1)](#alternative-analog-pulse-sensor-gpio-1)
  - [Hardware Diagnostics (`i2c_scan.ino`)](#hardware-diagnostics-i2c_scanino)
- [Firmware: `powerrun_ble`](#firmware-powerrun_ble)
  - [Dependencies](#dependencies)
  - [Arduino IDE Configuration](#arduino-ide-configuration)
  - [Flashing Instructions](#flashing-instructions)
- [BLE GATT Protocol Specifications](#ble-gatt-protocol-specifications)
  - [Nordic UART Service (NUS)](#nordic-uart-service-nus)
  - [Telemetry Packet Format](#telemetry-packet-format)
  - [MTU & Chunk Reassembly](#mtu--chunk-reassembly)
- [Android Companion App: `PowerRunApp`](#android-companion-app-powerrunapp)
  - [Architecture](#architecture)
  - [Installing Pre-built APK](#installing-pre-built-apk)
  - [Building from Source](#building-from-source)
  - [Bluetooth Permissions](#bluetooth-permissions)
- [Web Dashboard: `power-run.html`](#web-dashboard-power-runhtml)
  - [Web Bluetooth Support](#web-bluetooth-support)
  - [Metrics & Telemetry Cards](#metrics--telemetry-cards)
- [Telemetry Processing & Formulas](#telemetry-processing--formulas)
- [Troubleshooting](#troubleshooting)

---

## System Architecture

```mermaid
graph TD
    subgraph SENSORS ["Hardware Sensors"]
        INA["INA219 High-Side Power Monitor<br/>(I2C: 0x40, SDA: GPIO4, SCL: GPIO5)"]
        MAX["MAX30102 Pulse Oximeter<br/>(I2C: 0x57, SDA: GPIO4, SCL: GPIO5)"]
        ANA["Analog Pulse Sensor<br/>(ADC1: GPIO1 - Optional)"]
    end

    subgraph ESP32 ["ESP32-C3 Microcontroller (powerrun_ble.ino)"]
        CORE["FreeRTOS / Arduino Core"]
        SENS_READ["Sensor Polling & Averaging Loop<br/>(Peak detection & 4-beat rolling filter)"]
        NIMBLE["NimBLE-Arduino Stack<br/>(Nordic UART Service: NUS)"]
        TX_CHUNK["Packet Serializer & Chunking<br/>(20-byte chunks @ 5 Hz, MTU 247)"]
        CORE --> SENS_READ
        SENS_READ --> TX_CHUNK
        TX_CHUNK --> NIMBLE
    end

    INA -->|I2C 100 kHz| SENS_READ
    MAX -->|I2C 100 kHz| SENS_READ
    ANA -->|ADC1 Channel 1| SENS_READ

    subgraph BLE_LINK ["Bluetooth Low Energy (2.4 GHz Air Interface)"]
        NIMBLE -->|GATT Notifications<br/>NUS TX (6E400003...)| CLIENTS
    end

    subgraph CLIENTS ["Client Displays"]
        subgraph ANDROID ["Android Device (PowerRunApp)"]
            BLELINK["BleLink.java<br/>(Scan, Reassembly, GATT Callback)"]
            BRIDGE["JavascriptInterface<br/>(PowerRunBle Bridge)"]
            WEBVIEW["WebView (assets/dashboard.html)<br/>STMicroelectronics Themed UI"]
            BLELINK --> BRIDGE --> WEBVIEW
        end
        subgraph BROWSER ["Desktop / Mobile Web Browser"]
            WEBBLE["Web Bluetooth API<br/>(navigator.bluetooth)"]
            HTMLDASH["power-run.html<br/>Canvas Strip-Chart & Gauges"]
            WEBBLE --> HTMLDASH
        end
    end
```

---

## Key Features

- **Zero-Wi-Fi Independence**: Operates without routers, Wi-Fi passwords, IP addresses, or hotspots. Ideal for runners outdoors.
- **Ultra-Low Latency & High Rate**: Telemetry pushed every 200 ms (5 Hz) over BLE notifications.
- **Efficient Memory Footprint**: Uses `NimBLE-Arduino` for lower RAM and flash consumption than traditional Bluedroid.
- **Full Electrical Diagnostics**: Measures bus voltage ($0-26\text{ V}$), current draw ($\pm 3.2\text{ A}$), and instantaneous wattage with peak power hold tracking.
- **Biometric Heart Rate Extraction**: Photoplethysmography (PPG) peak detection with dynamic 4-beat window averaging and active finger-detection gating.
- **Dual Display Support**:
  - **Native Android App**: Full-screen WebView interface with background BLE scanning and automatic reconnection.
  - **Direct Web Bluetooth**: Run `power-run.html` directly in Chrome or Edge without installing software.
- **Safety Gating & Alerts**: Visual low-battery alarm triggers when capacity drops below critical levels.

---

## Repository Structure

```
├── powerrun_ble/
│   └── powerrun_ble.ino           # ESP32-C3 BLE firmware (NimBLE + INA219 + MAX30102)
├── PowerRunApp/                   # Complete Android Studio project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/powerrun/dashboard/
│   │   │   │   ├── MainActivity.java    # Android WebView shell & JS bridge
│   │   │   │   └── BleLink.java         # Native BLE GATT scanner & parser
│   │   │   ├── assets/
│   │   │   │   └── dashboard.html       # Built-in responsive telemetry UI
│   │   │   ├── res/                     # Icons, app styles, theme configs
│   │   │   └── AndroidManifest.xml      # BLE permissions & hardware flags
│   │   └── build.gradle.kts             # App build config (SDK 35, Java 11)
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradlew                          # Gradle wrapper
├── PowerRun-debug.apk             # Ready-to-install Android APK
├── power-run.html                 # Standalone web dashboard with Web Bluetooth support
├── i2c_scan/
│   └── i2c_scan.ino               # Hardware diagnostic tool for I2C bus debugging
├── I2C_REFERENCE.md               # Technical I2C bus timing & register documentation
├── I2C_EXAMPLES.md                # Example code snippets for INA219 registers
├── .gitignore                     # Ignores build files and legacy Wi-Fi code
└── README.md                      # Comprehensive project guide
```

---

## Hardware Setup & Pinout

### I2C Bus Pin Selection (ESP32-C3)

On the **ESP32-C3 (RISC-V)**, `GPIO 8` (on-board status LED) and `GPIO 9` (BOOT button) are hardware strapping pins. Using them for I2C can prevent the ESP32 from booting into normal run mode if a connected sensor pulls the line low during reset.

PowerRun uses **GPIO 4 (SDA)** and **GPIO 5 (SCL)** for safe, conflict-free I2C operation.

```cpp
#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5
#define I2C_FREQ 100000 // 100 kHz Standard Mode
```

### INA219 Power Monitor Wiring

The INA219 measures high-side DC voltage and current via an on-board $0.1\ \Omega$ shunt resistor.

| ESP32-C3 Pin | INA219 Pin | Notes |
| :--- | :--- | :--- |
| **3.3V** | **VCC** | Power supply |
| **GND** | **GND** | Common ground |
| **GPIO 4** | **SDA** | I2C Data line (needs 4.7kΩ pull-up if not on module) |
| **GPIO 5** | **SCL** | I2C Clock line (needs 4.7kΩ pull-up if not on module) |

**Load Wiring**:
- **VIN+**: Connect to positive terminal of power source / battery ($3.0\text{ V} - 26\text{ V}$).
- **VIN-**: Connect to positive terminal of load / circuit under test.
- **GND**: Common ground between source, load, and ESP32.

### MAX30102 Heart Rate Sensor Wiring

The MAX30102 communicates over the **same I2C bus** in parallel with the INA219.

| ESP32-C3 Pin | MAX30102 Pin | Notes |
| :--- | :--- | :--- |
| **3.3V** | **VIN / VCC** | 3.3V regulated power |
| **GND** | **GND** | Ground |
| **GPIO 4** | **SDA** | Shared I2C Data |
| **GPIO 5** | **SCL** | Shared I2C Clock |

### Alternative: Analog Pulse Sensor (GPIO 1)

If using a pulse sensor with analog output (e.g. KY-039):
1. Connect `Signal` to **GPIO 1** (`ADC1_CH1`).
2. Set `#define USE_I2C_HEART_RATE 0` in `powerrun_ble.ino`.

### Hardware Diagnostics (`i2c_scan.ino`)

Before flashing the full BLE firmware, verify your sensor wiring using the included scanner:
1. Open `i2c_scan/i2c_scan.ino` in Arduino IDE.
2. Select your ESP32-C3 port and upload.
3. Open Serial Monitor at **115200 baud**.
4. Expected output:
   ```text
   ===== I2C scanner (SDA=GPIO4  SCL=GPIO5) =====
   Idle levels: SDA(GPIO4)=1  SCL(GPIO5)=1
   lines look OK (both high)
   scan @ 100000 Hz: 0x40(INA219)  0x57(MAX30102)   (2 found)
   ```
   *If `BUS STUCK LOW` appears, inspect SDA/SCL pull-ups, verify 3.3V power, and ensure no wires are shorted to GND.*

---

## Firmware: `powerrun_ble`

The firmware is located in `powerrun_ble/powerrun_ble.ino`.

### Dependencies

Install the following libraries via the Arduino Library Manager (`Tools -> Manage Libraries...`):

1. **NimBLE-Arduino** by *h2zero* (v1.4.0 or higher)
2. **Adafruit INA219** by *Adafruit*
3. **Adafruit BusIO** (dependency of Adafruit INA219)
4. **SparkFun MAX3010x Pulse and Proximity Sensor Library** by *SparkFun*

### Arduino IDE Configuration

- **Board**: `ESP32C3 Dev Module`
- **USB CDC On Boot**: `Enabled` *(Crucial for serial monitoring via USB-C)*
- **Flash Size**: `4MB (32Mb)`
- **Partition Scheme**: `Default 4MB with spiffs (1.2MB APP/1.5MB SPIFFS)`
- **Core Debug Level**: `None` (or `Info` for debugging)
- **Upload Speed**: `921600`

### Flashing Instructions

1. Connect the ESP32-C3 board to your PC via USB-C.
2. Open `powerrun_ble/powerrun_ble.ino`.
3. Verify pin configurations in the `#define` section.
4. Press **Upload**.
5. Once flashed, the ESP32-C3 immediately begins advertising as **`PowerRun-BLE`**.

---

## BLE GATT Protocol Specifications

PowerRun implements the standard **Nordic UART Service (NUS)** protocol, allowing compatibility with custom apps, Web Bluetooth, and generic BLE debuggers (such as nRF Connect).

### Nordic UART Service (NUS)

| Parameter | UUID | Description |
| :--- | :--- | :--- |
| **Service** | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Nordic Semiconductor UART Service |
| **TX Characteristic** | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | **NOTIFY** (Telemetry from ESP32 to client) |
| **RX Characteristic** | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | **WRITE / WRITE_NR** (Commands to ESP32) |
| **CCCD** | `00002902-0000-1000-8000-00805F9B34FB` | Client Characteristic Configuration |

- **Advertisement Name**: `PowerRun-BLE`
- **TX Power**: `+9 dBm` (`ESP_PWR_LVL_P9`)
- **Connection Interval**: $15\text{ ms} - 30\text{ ms}$ (`min=12, max=24, latency=0, timeout=200`)

### Telemetry Packet Format

Every 200 ms, the ESP32 pushes a single-line JSON string terminated by a newline character (`\n`):

```json
{"voltage":3.91,"current":120.40,"power":0.47,"heartRate":72}\n
```

| Key | Type | Unit | Description |
| :--- | :--- | :--- | :--- |
| `voltage` | `float` | Volts ($V$) | Bus voltage across source and ground |
| `current` | `float` | Milliamps ($mA$) | Current through the $0.1\ \Omega$ shunt |
| `power` | `float` | Watts ($W$) | Power delivered ($V \times I$) |
| `heartRate` | `int` | BPM | Calculated heart rate (0 if no finger detected) |

### MTU & Chunk Reassembly

- The firmware initiates an MTU request of **247 bytes**.
- To prevent truncation on devices that fail MTU negotiation (fallback to default 23 bytes / 20 payload bytes), the firmware sends strings using chunked 20-byte writes:
  ```cpp
  void notifyChunked(const String& s);
  ```
- Clients (`BleLink.java` and `power-run.html`) maintain an internal buffer (`rxBuf`) accumulating bytes until `\n` is encountered, ensuring complete, uncorrupted JSON frames.

---

## Android Companion App: `PowerRunApp`

`PowerRunApp` is a lightweight, zero-dependency native Android application that hosts the telemetry dashboard and handles BLE connectivity.

### Architecture

- **`MainActivity.java`**:
  - Keeps the display awake continuously (`FLAG_KEEP_SCREEN_ON`).
  - Embeds a hardware-accelerated `WebView` rendering `file:///android_asset/dashboard.html`.
  - Exposes a bidirectional `@JavascriptInterface` named `PowerRunBle`.
  - Manages Android 12+ runtime permission dialogs.
- **`BleLink.java`**:
  - Uses `BluetoothLeScanner` with `SCAN_MODE_LOW_LATENCY`.
  - Automatically identifies devices matching `PowerRun-BLE` or NUS Service UUID.
  - Negotiates MTU to 247 and enables CCCD notifications on NUS TX.
  - Reassembles fragmented byte packets and triggers `window.onTelemetry(jsonLine)` in JavaScript.

### Installing Pre-built APK

A pre-compiled APK is included in the root directory:
1. Copy `PowerRun-debug.apk` to your Android phone (or download via USB/email).
2. Tap the APK file to install (enable "Install unknown apps" if prompted).
3. Open **Power Run**, tap **Connect Bluetooth**, and accept the nearby devices permission.

### Building from Source

Using Android Studio or the command line:

```bash
cd PowerRunApp
./gradlew assembleDebug
```
The output APK will be generated at `PowerRunApp/app/build/outputs/apk/debug/app-debug.apk`.

### Bluetooth Permissions

`PowerRunApp` automatically requests the proper permissions according to the Android OS version:
- **Android 12+ (API 31+)**: `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`.
- **Android 11 & Below (API <= 30)**: `BLUETOOTH`, `BLUETOOTH_ADMIN`, `ACCESS_FINE_LOCATION`.

---

## Web Dashboard: `power-run.html`

The dashboard is built with responsive HTML5, CSS3, and high-performance Canvas 2D rendering using the **STMicroelectronics Brand Palette** (Navy Deep Blue `#00205B`, Cyan Blue `#0082C8`, Vibrant Green `#50B848`, zero purple).

### Web Bluetooth Support

`power-run.html` directly supports the browser's **Web Bluetooth API** (`navigator.bluetooth`):
1. Open `power-run.html` in **Google Chrome**, **Microsoft Edge**, or **Opera** (desktop or Android).
2. Click **Connect Bluetooth**.
3. Select **`PowerRun-BLE`** from the browser's pairing dialog.
4. Telemetry begins streaming immediately.

### Metrics & Telemetry Cards

1. **POWER % REMAIN ON HOLD**:
   - Primary gauge showing remaining battery capacity based on discharge curve ($3.0\text{ V} - 3.8\text{ V}$).
   - **Peak Power Hold**: Latches and displays the maximum wattage recorded during the session.
   - Secondary indicators for Live Voltage ($V$), Instant Current ($mA$), and Active Power ($W$).
2. **HEART RATE MONITOR**:
   - Digital BPM readout with an animated SVG heart pulsing synchronously with the runner's pulse.
   - Rhythm status classifications: `STANDBY`, `ACTIVE`, `ELEVATED`, `PEAK ZONE`.
3. **REAL-TIME DUAL STRIP-CHART**:
   - 60-point sliding window Canvas chart.
   - Shows electrical power/current curve (green) overlaid with heart rate trend (cyan).
4. **LOW BATTERY BANNER**:
   - Automatically drops down when remaining capacity is $< 15\%$ or voltage is $< 3.2\text{ V}$.

---

## Telemetry Processing & Formulas

### 1. Battery Capacity Percentage

The battery remaining percentage is derived using a linear mapping between defined minimum cutoff and nominal fully-charged voltages:

$$\text{Capacity } \% = \operatorname{clamp}\left( \frac{V_{\text{measured}} - V_{\min}}{V_{\max} - V_{\min}} \times 100,\ 0,\ 100 \right)$$

*Default parameters: $V_{\min} = 3.0\text{ V}$, $V_{\max} = 3.8\text{ V}$ (adjustable in dashboard settings).*

### 2. Peak Power Hold

The maximum power draw observed across all samples is latched:

$$P_{\text{hold}} = \max\left(P_{\text{hold}},\ P_{\text{current}}\right)$$

### 3. Heart Rate Dynamic Moving Average

The MAX30102 photoplethysmography (PPG) algorithm measures infrared absorption pulses. To prevent delays when starting a workout, the BPM average computes over available beats up to $N = 4$:

$$\text{BPM}_{\text{avg}} = \frac{1}{K} \sum_{i=0}^{K-1} \text{BPM}_i \quad \text{where } K = \min(\text{captured\_beats}, 4)$$

If infrared signal falls below threshold ($IR < 20000$) for $> 200$ consecutive polling cycles, the finger is treated as removed and BPM resets to $0$.

---

## Troubleshooting

| Symptom | Probable Cause | Resolution |
| :--- | :--- | :--- |
| **I2C Bus Stuck Low / Sensors not detected** | Loose wiring, incorrect SDA/SCL pins, or missing pull-ups. | Run `i2c_scan/i2c_scan.ino`. Ensure SDA is **GPIO 4** and SCL is **GPIO 5**. Verify 3.3V supply. |
| **ESP32 boots into download mode / won't run** | Strapping pins pulled low during boot. | Avoid using GPIO 8 or GPIO 9 for I2C. Connect sensors strictly to GPIO 4 and 5. |
| **"BLE unavailable" on dashboard** | Browser lacks Web Bluetooth or not running on secure origin. | Use Google Chrome or Edge. Ensure the page is accessed via `localhost`, `file:///`, or `https://`. |
| **"Turn on Bluetooth" on Android app** | Phone Bluetooth adapter is powered off. | Turn on Bluetooth in Android Quick Settings. |
| **"Grant permission" on Android app** | Nearby device permissions rejected. | Open Android Settings -> Apps -> Power Run -> Permissions -> Allow Nearby Devices / Location. |
| **Heart rate reads 0 BPM** | No finger on MAX30102 sensor or ambient light interference. | Place fingertip gently on the MAX30102 LED surface. Avoid heavy pressure that restricts blood flow. |
| **Voltage reads 0.00 V** | INA219 not found on I2C bus. | Check I2C address solder jumpers on INA219 (default address is `0x40`). |

---

## License

This project is open source and available under the **MIT License**.
