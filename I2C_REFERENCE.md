# I2C & INA219 Technical Reference

## I2C Protocol Overview

I2C (Inter-Integrated Circuit) is a synchronous serial communication protocol using two open-drain lines:

### Signal Levels

- **Idle state**: Both SDA and SCL pulled HIGH by resistors (typically 4.7kΩ)
- **LOW**: Driven to ground by device
- **Voltage levels**: 3.3V or 5V depending on system

### I2C Bus Timing

```
SCL (Clock):  ════╖  ╖════╖  ╖════
              ════╜══╜════╜══╜════
                    
SDA (Data):   ════╖    ╖════╖  ╖══
              ════╜════╜════╜══╜══
```

### Communication Phases

1. **START Condition**: SDA goes LOW while SCL is HIGH
2. **Address Phase**: 7-bit address + 1 R/W bit
3. **ACK/NACK**: Slave pulls SDA LOW to acknowledge
4. **Data Transfer**: 8-bit data bytes with ACK between each
5. **STOP Condition**: SDA goes HIGH while SCL is HIGH

## ESP32 I2C Configuration

### Wire Library (Standard Arduino)

```cpp
#include <Wire.h>

// Initialize with default pins (GPIO21=SDA, GPIO22=SCL)
Wire.begin();

// Or specify custom pins
Wire.begin(SDA_PIN, SCL_PIN);

// Set I2C frequency (in Hz)
// Standard: 100000 (100 kHz)
// Fast: 400000 (400 kHz)
Wire.setClock(100000);
```

### Master Reading from Slave (INA219)

```cpp
// Step 1: Start transmission and send address
Wire.beginTransmission(0x40);      // INA219 default address

// Step 2: Send register address to read
Wire.write(0x01);                  // Request voltage register

// Step 3: End transmission (sends STOP)
uint8_t error = Wire.endTransmission();
// Returns: 0=success, 1=NACK on data, 2=NACK on address, etc.

// Step 4: Request data from slave
Wire.requestFrom(0x40, 2);         // Request 2 bytes

// Step 5: Read data
uint8_t msb = Wire.read();         // High byte
uint8_t lsb = Wire.read();         // Low byte

// Combine into 16-bit value
uint16_t voltage_raw = (msb << 8) | lsb;
float voltage_v = voltage_raw * 0.004;  // Convert to volts
```

## INA219 Register Map

### Configuration Register (0x00)

Controls sensor operation mode, conversion times, and averaging.

```
Bit  15  14  13  12  11  10  9   8   7   6   5   4   3   2   1   0
     RST MODE <-- SADC ----------> <-- BADC ----------> PG1 PG0 BRNG
```

| Field | Bits | Description |
|-------|------|-------------|
| RST   | 15   | Reset bit (1 = reset all registers) |
| MODE  | 3-0  | Operating mode (see table below) |
| SADC  | 8-11 | Shunt voltage ADC resolution/averaging |
| BADC  | 12-15| Bus voltage ADC resolution/averaging |
| PG    | 0-1  | PGA gain selection (0=±40mV, 1=±80mV, 2=±160mV, 3=±320mV) |
| BRNG  | 13   | Bus voltage range (0=16V, 1=32V) |

### Operating Mode (Bits 0-3)

| Value | Mode | Description |
|-------|------|-------------|
| 0000  | PowerDown | Powered down |
| 0001  | ShuntVoltage | Shunt voltage triggered |
| 0010  | BusVoltage | Bus voltage triggered |
| 0011  | ShuntAndBus | Shunt and bus voltage triggered |
| 0100  | ADCOff | ADC Off |
| 0101  | ShuntContinuous | Shunt voltage continuous |
| 0110  | BusContinuous | Bus voltage continuous |
| 0111  | ShuntAndBusContinuous | Shunt and bus continuous |

### Data Registers

| Register | Address | Bits | Description |
|----------|---------|------|-------------|
| Bus Voltage | 0x01 | 15-0 | Bus voltage measurement (4mV/LSB) |
| Shunt Voltage | 0x01 | 15-0 | Shunt voltage measurement (10µV/LSB) |
| Power | 0x03 | 15-0 | Power calculation (20mW/LSB) |
| Current | 0x04 | 15-0 | Current measurement (varies with calibration) |
| Calibration | 0x05 | 15-0 | Calibration register |

### Sample Register Read

```cpp
// Read bus voltage register (0x01)
Wire.beginTransmission(0x40);
Wire.write(0x01);
Wire.endTransmission();

Wire.requestFrom(0x40, 2);
uint16_t raw = (Wire.read() << 8) | Wire.read();

// Extract voltage (bits 15-3 contain voltage)
float voltage_v = (raw >> 3) * 0.004;  // 4mV per LSB
```

## Adafruit INA219 Library Implementation

The Adafruit library abstracts all I2C details:

### High-Level API

```cpp
#include <Adafruit_INA219.h>

Adafruit_INA219 ina219;

// Initialization with default address (0x40)
if (!ina219.begin()) {
  Serial.println("INA219 not found");
}

// Calibration presets
ina219.setCalibration_32V_2A();    // ±32V, ±2A range
ina219.setCalibration_32V_1A();    // ±32V, ±1A range (more precise)
ina219.setCalibration_16V_400mA(); // ±16V, ±400mA range (most precise)

// Read measurements
float shuntVoltage = ina219.getShuntVoltage_mV();  // Shunt voltage in mV
float busVoltage = ina219.getBusVoltage_V();        // Bus voltage in V
float current = ina219.getCurrent_mA();             // Current in mA
float power = ina219.getPower_mW();                 // Power in mW
float loadVoltage = busVoltage + shuntVoltage/1000; // Load voltage
```

### Custom Calibration

```cpp
// For different current ranges, calculate calibration value
// Calibration = 40960 / (ShuntResistance * MaxCurrent_A)

// For 0.1Ω shunt, 2A max:
// Calibration = 40960 / (0.1 * 2) = 204800
ina219.calibrate_init();  // Use library's default
```

## Troubleshooting I2C Communication

### Check 1: Verify I2C Connection

```cpp
bool checkI2CDevice(uint8_t addr) {
  Wire.beginTransmission(addr);
  uint8_t error = Wire.endTransmission();
  
  if (error == 0) {
    Serial.printf("Device 0x%02X: ACK received\n", addr);
    return true;
  } else {
    Serial.printf("Device 0x%02X: Error code %d\n", addr, error);
    return false;
  }
}
```

### Check 2: Read Raw Register

```cpp
bool readRegister(uint8_t addr, uint8_t reg, uint16_t &value) {
  Wire.beginTransmission(addr);
  Wire.write(reg);
  if (Wire.endTransmission() != 0) return false;
  
  Wire.requestFrom(addr, (uint8_t)2);
  if (Wire.available() >= 2) {
    value = (Wire.read() << 8) | Wire.read();
    return true;
  }
  return false;
}

// Usage
uint16_t busVoltageRaw;
if (readRegister(0x40, 0x01, busVoltageRaw)) {
  float voltage = (busVoltageRaw >> 3) * 0.004;
  Serial.printf("Bus voltage: %.2f V\n", voltage);
}
```

### Check 3: Verify Pull-Up Resistors

I2C requires pull-up resistors. If not present on breakout board, add 4.7kΩ resistors:

```
        +3.3V
          |
        [4.7k]
          |----+---- SDA (GPIO21)
                |
          Sensor

        +3.3V
          |
        [4.7k]
          |----+---- SCL (GPIO22)
                |
          Sensor
```

### Common Error Codes

| Code | Meaning | Solution |
|------|---------|----------|
| 0 | SUCCESS | Communication successful |
| 1 | Data too long | Reduce data size or check buffer |
| 2 | NACK on address | Device not responding - check wiring |
| 3 | NACK on data | Device error - check configuration |
| 4 | Other error | I2C bus error - check for conflicts |

## Performance Tuning

### I2C Clock Speed

```cpp
// Lower frequency for longer wires or noise issues
Wire.setClock(50000);   // 50 kHz (very slow, very stable)
Wire.setClock(100000);  // 100 kHz (standard mode)
Wire.setClock(400000);  // 400 kHz (fast mode)
Wire.setClock(1000000); // 1 MHz (fast mode plus)
```

### Conversion Time

INA219 conversion time depends on ADC settings:

```
Resolution   | Single  | Average
-------------|---------|----------
9-bit        | 84µs    | 532µs
10-bit       | 148µs   | 1.06ms
11-bit       | 276µs   | 2.13ms
12-bit       | 532µs   | 4.26ms
```

Shorter conversion = less power consumption but less accuracy.

### Reading Frequency

Don't read faster than the conversion completes:

```cpp
// If using 12-bit conversion (532µs)
// Add 1ms delay between reads to be safe
delay(1);

float voltage = ina219.getBusVoltage_V();
```

## Multiple I2C Devices

The same I2C bus can support multiple devices at different addresses:

```cpp
Adafruit_INA219 ina219_1(0x40);  // First INA219
Adafruit_INA219 ina219_2(0x41);  // Second INA219

// Both use same SDA/SCL pins (GPIO21/22)
ina219_1.begin();
ina219_2.begin();
```

Refer to device datasheets for available address options.

## Related Files

- `esp32_websocket_server.ino` - Main firmware with I2C integration
- `SETUP.md` - Setup guide and troubleshooting
- `sensor-monitor.jsx` - Web dashboard receiving I2C data
