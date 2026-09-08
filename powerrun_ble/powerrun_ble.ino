// ===========================================================================
// Power Run - BLE telemetry for ESP32-C3
//
// No Wi-Fi. The board advertises a Nordic UART Service (NUS) and pushes one
// JSON line of telemetry every 200 ms as a BLE notification:
//     {"voltage":3.91,"current":120.4,"power":0.47,"heartRate":72}\n
//
// Sensors (all optional - missing ones just report 0):
//   INA219  power monitor    I2C  0x40   SDA=GPIO4  SCL=GPIO5
//   MAX30102 heart rate      I2C  0x57   SDA=GPIO4  SCL=GPIO5   (shared bus)
//   or an analog pulse sensor on GPIO1   (set USE_I2C_HEART_RATE 0)
//
// Board:  ESP32C3 Dev Module
// Tools:  USB CDC On Boot -> Enabled, Flash 4MB, Partition Scheme Default
// Libs:   NimBLE-Arduino
//         Adafruit INA219
//         SparkFun MAX3010x Pulse and Proximity Sensor Library
// ===========================================================================

#include <NimBLEDevice.h>
#include <Wire.h>
#include <Adafruit_INA219.h>

// ---- Config ------------------------------------------------------------
#define USE_I2C_HEART_RATE 1          // 1 = MAX30102 (I2C), 0 = analog sensor on GPIO1

#define I2C_SDA_PIN 4
#define I2C_SCL_PIN 5
#define I2C_FREQ 100000
#define ANALOG_HEART_RATE_PIN 1

static const char* BLE_NAME = "PowerRun-BLE";

// ---- BLE UUIDs (Nordic UART Service) ---------------------------------
#define NUS_SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

// ---- INA219 --------------------------------------------------------
Adafruit_INA219 ina219;
bool ina219_ok = false;

// ---- Heart rate ---------------------------------------------------
#if USE_I2C_HEART_RATE
  #include "MAX30105.h"
  #include "heartRate.h"

  MAX30105 hrSensor;
  bool hrSensor_ok = false;

  const uint8_t RATE_SIZE = 4;          // beats to average (smaller = shows a reading sooner)
  uint8_t rates[RATE_SIZE] = {0};
  uint8_t rateSpot = 0;
  uint8_t rateCount = 0;                // how many beats captured so far (<= RATE_SIZE)
  uint32_t lastBeat = 0;
  int beatAvg = 0;
  uint16_t noFingerCount = 0;
#else
  const int HR_BUF = 10;
  int hrBuf[HR_BUF] = {0};
  int hrIdx = 0;
#endif

// ---- BLE state -------------------------------------------------
NimBLECharacteristic* txChar = nullptr;
volatile bool deviceConnected = false;

uint32_t lastSend = 0;
const uint32_t SEND_INTERVAL = 200;   // 5 Hz - telemetry / heart-rate refresh

class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer* s, NimBLEConnInfo& info) override {
    deviceConnected = true;
    Serial.printf("BLE connected: %s\n", info.getAddress().toString().c_str());
    s->updateConnParams(info.getConnHandle(), 12, 24, 0, 200);
  }
  void onDisconnect(NimBLEServer* s, NimBLEConnInfo& info, int reason) override {
    deviceConnected = false;
    Serial.printf("BLE disconnected (reason %d) - advertising again\n", reason);
    NimBLEDevice::startAdvertising();
  }
  void onMTUChange(uint16_t mtu, NimBLEConnInfo& info) override {
    Serial.printf("BLE MTU = %u\n", mtu);
  }
};

// ---- Sensor setup ------------------------------------------------
void initI2C() {
  Wire.begin(I2C_SDA_PIN, I2C_SCL_PIN, I2C_FREQ);
  delay(50);
}

void initINA219() {
  if (ina219.begin()) {
    ina219.setCalibration_32V_2A();
    ina219_ok = true;
    Serial.println("INA219 ready");
  } else {
    Serial.println("INA219 not found - power values will read 0");
  }
}

void initHeartRate() {
#if USE_I2C_HEART_RATE
  // Share the bus that initI2C() already brought up on GPIO4/5.
  if (hrSensor.begin(Wire, I2C_SPEED_STANDARD, 0x57)) {
    hrSensor.setup();                     // sensible defaults (IR+Red, 100 Hz effective)
    hrSensor.setPulseAmplitudeRed(0x0A);  // Red low - only used for finger presence
    hrSensor.setPulseAmplitudeGreen(0);   // no green LED on MAX30102
    Wire.setClock(I2C_FREQ);              // keep the INA219 happy
    hrSensor_ok = true;
    Serial.println("MAX30102 ready - place a fingertip on the sensor");
  } else {
    Serial.println("MAX30102 not found - heartRate will read 0");
  }
#else
  pinMode(ANALOG_HEART_RATE_PIN, INPUT);
  Serial.println("Analog heart-rate sensor on GPIO1");
#endif
}

// ---- Heart rate reading --------------------------------------
#if USE_I2C_HEART_RATE
void processIR(int32_t ir) {
  if (ir < 20000) {                       // nothing on the sensor
    if (++noFingerCount > 200) {
      beatAvg = 0; lastBeat = 0; rateSpot = 0; rateCount = 0;
    }
    return;
  }
  noFingerCount = 0;

  if (checkForBeat(ir)) {
    uint32_t now = millis();
    if (lastBeat != 0) {
      uint32_t delta = now - lastBeat;
      float bpm = 60000.0f / (float)delta;
      if (bpm > 30 && bpm < 220) {
        rates[rateSpot++] = (uint8_t)bpm;
        rateSpot %= RATE_SIZE;
        if (rateCount < RATE_SIZE) rateCount++;
        // Average only the beats captured so far, so the first real beat
        // shows an immediate BPM instead of ramping up from zero.
        uint16_t sum = 0;
        for (uint8_t i = 0; i < rateCount; i++) sum += rates[i];
        beatAvg = sum / rateCount;
      }
    }
    lastBeat = now;
  }
}

void pollHeartRate() {
  if (!hrSensor_ok) return;
  hrSensor.check();                       // pull whatever samples are ready
  while (hrSensor.available()) {
    processIR((int32_t)hrSensor.getFIFOIR());
    hrSensor.nextSample();
  }
}

int currentHeartRate() { return beatAvg; }

#else  // analog path

void pollHeartRate() { /* nothing to do between packets */ }

int currentHeartRate() {
  hrBuf[hrIdx] = analogRead(ANALOG_HEART_RATE_PIN);
  hrIdx = (hrIdx + 1) % HR_BUF;
  long sum = 0;
  for (int i = 0; i < HR_BUF; i++) sum += hrBuf[i];
  return constrain(map(sum / HR_BUF, 0, 4095, 40, 180), 40, 200);
}
#endif

// ---- BLE ------------------------------------------------------
void initBLE() {
  NimBLEDevice::init(BLE_NAME);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(247);

  NimBLEServer* server = NimBLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());

  NimBLEService* nus = server->createService(NUS_SERVICE_UUID);
  txChar = nus->createCharacteristic(NUS_TX_UUID, NIMBLE_PROPERTY::NOTIFY);
  nus->createCharacteristic(NUS_RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  nus->start();

  NimBLEAdvertising* adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE_UUID);
  adv->setName(BLE_NAME);
  adv->enableScanResponse(true);
  NimBLEDevice::startAdvertising();

  Serial.printf("BLE advertising as \"%s\"\n", BLE_NAME);
}

// Send a string as one-or-more 20-byte notifications (safe at any MTU).
void notifyChunked(const String& s) {
  if (!deviceConnected || txChar == nullptr) return;
  const uint8_t* p = (const uint8_t*)s.c_str();
  size_t len = s.length();
  const size_t CHUNK = 20;
  for (size_t off = 0; off < len; off += CHUNK) {
    size_t n = (len - off < CHUNK) ? (len - off) : CHUNK;
    txChar->setValue(p + off, n);
    txChar->notify();
    delay(5);
  }
}

// ---- Main ---------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(500);
  Serial.println("\n\n=== Power Run BLE ===");

  initI2C();
  initINA219();
  initHeartRate();
  initBLE();
}

void loop() {
  pollHeartRate();                        // runs every iteration for beat detection

  uint32_t now = millis();
  if (now - lastSend >= SEND_INTERVAL) {
    lastSend = now;

    float v = 0, c = 0, p = 0;
    if (ina219_ok) {
      v = ina219.getBusVoltage_V();
      c = ina219.getCurrent_mA();
      p = ina219.getPower_mW() / 1000.0f;
    }
    int hr = currentHeartRate();

    String json = "{";
    json += "\"voltage\":" + String(v, 2) + ",";
    json += "\"current\":" + String(c, 2) + ",";
    json += "\"power\":" + String(p, 2) + ",";
    json += "\"heartRate\":" + String(hr);
    json += "}\n";

    if (deviceConnected) notifyChunked(json);
    Serial.print(deviceConnected ? "[tx] " : "[--] ");
    Serial.print(json);
  }

  delay(2);
}
