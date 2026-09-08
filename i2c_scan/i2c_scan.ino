#include <Wire.h>

#define SDA_PIN 4
#define SCL_PIN 5

void lineCheck() {
  Wire.end();
  delay(20);
  pinMode(SDA_PIN, INPUT_PULLUP);
  pinMode(SCL_PIN, INPUT_PULLUP);
  delay(5);
  int sda = digitalRead(SDA_PIN);
  int scl = digitalRead(SCL_PIN);
  Serial.printf("Idle levels (internal pull-ups on): SDA(GPIO%d)=%d  SCL(GPIO%d)=%d\n",
                SDA_PIN, sda, SCL_PIN, scl);
  if (!sda || !scl) {
    Serial.println("  *** BUS STUCK LOW ***  A line held at 0 means:");
    Serial.println("      - SDA/SCL wire in the wrong hole (GND or a power pin), or");
    Serial.println("      - sensor VCC not actually powered (its pull-ups drag the line low), or");
    Serial.println("      - a short between SDA/SCL and GND.");
    Serial.println("      Disconnect BOTH sensors and re-check. If still 0 -> the short is on the");
    Serial.println("      ESP32 board itself (GPIO4/5). Otherwise add sensors back one at a time.");
  } else {
    Serial.println("  lines look OK (both high)");
  }
}

void scan(uint32_t hz) {
  Wire.end();
  delay(20);
  Wire.begin(SDA_PIN, SCL_PIN, hz);
  delay(50);
  Serial.printf("scan @ %lu Hz: ", hz);
  int found = 0, timeouts = 0;
  for (uint8_t a = 1; a < 127; a++) {
    Wire.beginTransmission(a);
    uint8_t err = Wire.endTransmission();
    if (err == 0) {
      Serial.printf("0x%02X%s  ", a,
                    a == 0x40 ? "(INA219)" : a == 0x57 ? "(MAX30102)" : "");
      found++;
    } else if (err == 5) {
      timeouts++;
    }
  }
  if (found == 0) Serial.print("nothing");
  if (timeouts > 100) Serial.print("  [bus jammed - every address times out]");
  Serial.printf("   (%d found)\n", found);
}

void setup() {
  Serial.begin(115200);
  delay(800);
  Serial.println("\n\n===== I2C scanner (SDA=GPIO4  SCL=GPIO5) =====");
  Serial.println("Expected devices: 0x40 INA219, 0x57 MAX30102\n");
}

void loop() {
  lineCheck();
  scan(100000);
  scan(50000);
  Serial.println();
  delay(3000);
}
