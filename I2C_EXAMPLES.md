# I2C Usage Examples - Practical Code Snippets

## Example 1: Reading INA219 with Adafruit Library (Recommended)

This is the easiest and most reliable approach:

```cpp
#include <Wire.h>
#include <Adafruit_INA219.h>

Adafruit_INA219 ina219;  // Use default address 0x40

void setup() {
  Serial.begin(115200);
  
  // Initialize I2C on GPIO21 (SDA), GPIO22 (SCL)
  Wire.begin(21, 22);
  
  // Initialize INA219 sensor
  if (!ina219.begin()) {
    Serial.println("Failed to find INA219");
    while (1) { delay(10); }
  }
  
  // Set calibration for your use case
  ina219.setCalibration_32V_2A();
  
  Serial.println("INA219 ready");
}

void loop() {
  // Read all measurements
  float shuntVoltage = ina219.getShuntVoltage_mV();  // mV
  float busVoltage = ina219.getBusVoltage_V();       // V
  float current = ina219.getCurrent_mA();            // mA
  float power = ina219.getPower_mW();                // mW
  
  // Calculate load voltage (what's connected to the sensor)
  float loadVoltage = busVoltage + shuntVoltage / 1000;
  
  // Print in nice format
  Serial.print("Bus Voltage:   "); Serial.print(busVoltage); Serial.println(" V");
  Serial.print("Shunt Voltage: "); Serial.print(shuntVoltage); Serial.println(" mV");
  Serial.print("Load Voltage:  "); Serial.print(loadVoltage); Serial.println(" V");
  Serial.print("Current:       "); Serial.print(current); Serial.println(" mA");
  Serial.print("Power:         "); Serial.print(power); Serial.println(" mW");
  Serial.println("---");
  
  delay(1000);  // Read once per second
}
```

## Example 2: Reading INA219 Directly via I2C (Raw)

If you want to understand low-level I2C communication:

```cpp
#include <Wire.h>

const uint8_t INA219_ADDR = 0x40;
const uint8_t BUS_VOLTAGE_REG = 0x01;
const uint8_t SHUNT_VOLTAGE_REG = 0x00;
const uint8_t POWER_REG = 0x03;
const uint8_t CURRENT_REG = 0x04;

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);  // SDA=21, SCL=22
}

void loop() {
  // Read bus voltage
  uint16_t busVoltageBits = readI2CRegister16(INA219_ADDR, BUS_VOLTAGE_REG);
  float busVoltage = (busVoltageBits >> 3) * 0.004;  // 4mV per LSB, bits 15-3
  
  // Read shunt voltage
  uint16_t shuntVoltageBits = readI2CRegister16(INA219_ADDR, SHUNT_VOLTAGE_REG);
  float shuntVoltage = (int16_t)shuntVoltageBits * 0.00001;  // 10µV per LSB
  
  // Read power
  uint16_t powerBits = readI2CRegister16(INA219_ADDR, POWER_REG);
  float power = powerBits * 0.020;  // 20mW per LSB
  
  // Read current
  uint16_t currentBits = readI2CRegister16(INA219_ADDR, CURRENT_REG);
  float current = (int16_t)currentBits * 0.1;  // ±2A range, depends on calibration
  
  Serial.printf("Bus: %.2fV | Shunt: %.2fmV | Power: %.2fmW | Current: %.2fmA\n",
                busVoltage, shuntVoltage, power, current);
  
  delay(1000);
}

// Helper function: Read 16-bit value from I2C register
uint16_t readI2CRegister16(uint8_t addr, uint8_t reg) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission() != 0) {
    Serial.println("I2C Error!");
    return 0;
  }
  
  Wire.requestFrom(addr, (uint8_t)2);
  if (Wire.available() < 2) return 0;
  
  uint8_t msb = Wire.read();
  uint8_t lsb = Wire.read();
  
  return (msb << 8) | lsb;
}
```

## Example 3: Reading MAX30102 Pulse Oximeter (I2C)

```cpp
#include <Wire.h>

const uint8_t MAX30102_ADDR = 0x57;
const uint8_t MODE_CONFIG_REG = 0x09;
const uint8_t FIFO_DATA_REG = 0x07;

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  
  // Initialize MAX30102
  initializeMax30102();
}

void loop() {
  // Read IR data from MAX30102
  long irValue = readMaxIRValue();
  
  Serial.printf("IR Value: %ld\n", irValue);
  
  // Simple heart rate detection (threshold-based)
  if (irValue > 50000) {  // Adjust threshold
    Serial.println("Pulse detected!");
  }
  
  delay(100);  // MAX30102 reads at 100Hz
}

void initializeMax30102() {
  Serial.println("Initializing MAX30102...");
  
  // Check if device responds
  Wire.beginTransmission(MAX30102_ADDR);
  if (Wire.endTransmission() != 0) {
    Serial.println("MAX30102 not found!");
    return;
  }
  
  // Setup for red LED (IR mode)
  // Clear FIFO: write 0x00 to 0x04
  writeI2CRegister(MAX30102_ADDR, 0x04, 0x00);
  
  // Mode config: 0x02 = Red only
  writeI2CRegister(MAX30102_ADDR, MODE_CONFIG_REG, 0x02);
  
  // LED power control
  writeI2CRegister(MAX30102_ADDR, 0x0C, 0x24);  // Red LED current
  
  Serial.println("MAX30102 initialized");
}

long readMaxIRValue() {
  Wire.beginTransmission(MAX30102_ADDR);
  Wire.write(FIFO_DATA_REG);
  if (Wire.endTransmission() != 0) return 0;
  
  // MAX30102 returns 3 bytes per sample
  Wire.requestFrom(MAX30102_ADDR, (uint8_t)3);
  
  long value = 0;
  if (Wire.available() >= 3) {
    value = (long)Wire.read() << 16;
    value |= (long)Wire.read() << 8;
    value |= Wire.read();
    value &= 0x03FFFF;  // Mask to 18 bits
  }
  
  return value;
}

void writeI2CRegister(uint8_t addr, uint8_t reg, uint8_t value) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  Wire.write(value);
  Wire.endTransmission();
}
```

## Example 4: Multiple I2C Devices on Same Bus

```cpp
#include <Wire.h>
#include <Adafruit_INA219.h>

// Two INA219 sensors at different addresses
Adafruit_INA219 ina219_main(0x40);     // Main power supply
Adafruit_INA219 ina219_secondary(0x41); // Secondary circuit

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  
  if (!ina219_main.begin()) {
    Serial.println("INA219 (main) not found");
  }
  
  if (!ina219_secondary.begin()) {
    Serial.println("INA219 (secondary) not found");
  }
  
  // Both use same I2C calibration
  ina219_main.setCalibration_32V_2A();
  ina219_secondary.setCalibration_32V_2A();
}

void loop() {
  Serial.println("=== Main Power Supply ===");
  printINA219Data(ina219_main);
  
  Serial.println("=== Secondary Circuit ===");
  printINA219Data(ina219_secondary);
  
  Serial.println("---");
  delay(1000);
}

void printINA219Data(Adafruit_INA219 &ina) {
  float voltage = ina.getBusVoltage_V();
  float current = ina.getCurrent_mA();
  float power = ina.getPower_mW();
  
  Serial.printf("V: %.2f | I: %.2f mA | P: %.2f mW\n",
                voltage, current, power);
}
```

## Example 5: I2C Error Handling

```cpp
#include <Wire.h>

bool safeI2CRead(uint8_t addr, uint8_t reg, uint8_t &value) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  uint8_t error = Wire.endTransmission();
  
  if (error != 0) {
    Serial.printf("I2C Error on write: %d\n", error);
    return false;
  }
  
  Wire.requestFrom(addr, (uint8_t)1);
  if (!Wire.available()) {
    Serial.println("No data available from I2C");
    return false;
  }
  
  value = Wire.read();
  return true;
}

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
}

void loop() {
  uint8_t chipID;
  
  if (safeI2CRead(0x40, 0x00, chipID)) {
    Serial.printf("Successfully read: 0x%02X\n", chipID);
  } else {
    Serial.println("Read failed, retrying...");
  }
  
  delay(1000);
}
```

## Example 6: I2C Speed Optimization

```cpp
#include <Wire.h>
#include <Adafruit_INA219.h>

Adafruit_INA219 ina219;

void setup() {
  Serial.begin(115200);
  
  // Slow I2C for problematic connections
  Wire.begin(21, 22);
  Wire.setClock(50000);  // 50 kHz - very stable
  
  // OR for fast reading:
  // Wire.setClock(400000);  // 400 kHz - fast mode
  
  if (!ina219.begin()) {
    Serial.println("INA219 not found");
  }
}

void loop() {
  unsigned long startTime = micros();
  
  float voltage = ina219.getBusVoltage_V();
  float current = ina219.getCurrent_mA();
  float power = ina219.getPower_mW();
  
  unsigned long readTime = micros() - startTime;
  
  Serial.printf("Read time: %lu µs | V: %.2f | I: %.2f | P: %.2f\n",
                readTime, voltage, current, power);
  
  delay(100);
}
```

## Example 7: Continuous Monitoring with Status LED

```cpp
#include <Wire.h>
#include <Adafruit_INA219.h>

Adafruit_INA219 ina219;
const int STATUS_LED = 25;  // GPIO25
const float CURRENT_LIMIT = 2000;  // 2A threshold

void setup() {
  Serial.begin(115200);
  Wire.begin(21, 22);
  pinMode(STATUS_LED, OUTPUT);
  
  if (!ina219.begin()) {
    Serial.println("INA219 not found");
    digitalWrite(STATUS_LED, HIGH);  // Error indicator
  } else {
    ina219.setCalibration_32V_2A();
    digitalWrite(STATUS_LED, LOW);
  }
}

void loop() {
  float current = ina219.getCurrent_mA();
  float power = ina219.getPower_mW();
  
  // Status LED indicator
  if (current > CURRENT_LIMIT) {
    digitalWrite(STATUS_LED, HIGH);  // Over-current warning
    Serial.printf("WARNING: Over-current! %.2f mA\n", current);
  } else {
    digitalWrite(STATUS_LED, LOW);   // Normal operation
  }
  
  Serial.printf("Current: %.2f mA | Power: %.2f mW\n", current, power);
  
  delay(500);
}
```

## Debugging Tips

### Enable Serial Output from Wire Library

```cpp
void setup() {
  Serial.begin(115200);
  
  // Add this to see I2C communication
  Wire.begin(21, 22);
  
  // Test I2C connection
  Serial.println("Testing I2C...");
  Wire.beginTransmission(0x40);
  uint8_t error = Wire.endTransmission();
  Serial.printf("Transmission result: %d (0=success)\n", error);
}
```

### Print I2C Register Values

```cpp
void printI2CRegisters(uint8_t addr) {
  Serial.printf("Registers for device 0x%02X:\n", addr);
  
  for (uint8_t reg = 0; reg < 0x06; reg++) {
    Wire.beginTransmission(addr);
    Wire.write(reg);
    Wire.endTransmission();
    
    Wire.requestFrom(addr, (uint8_t)2);
    uint16_t value = (Wire.read() << 8) | Wire.read();
    
    Serial.printf("  0x%02X: 0x%04X (%d)\n", reg, value, value);
  }
}
```

## Related Documentation

- `/mnt/skills/public/I2C_REFERENCE.md` - Protocol details and registers
- `SETUP.md` - Complete setup instructions
- `esp32_websocket_server.ino` - Full implementation
