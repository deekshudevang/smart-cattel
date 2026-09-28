# pH Sensor Calibration Documentation

## Overview
The Smart Cattle Health Monitoring system uses an analog pH sensor which requires a 2-point calibration to accurately read the pH of the cattle environment/fluids. It never claims a pH reading is accurate without this calibration. 

The system reads the raw analog signal from the pH sensor pin, converts it to voltage, and maps it dynamically to a calculated slope and intercept using stored calibration constants.

## Implementation Details

### Variables Used
- `ph_calibration`: Struct that holds the calibration values (`voltage4`, `voltage7`) and a magic byte to verify EEPROM validity.
- `ph_raw`: The raw analog value read from the ADC (0-1023).
- `ph_voltage`: The smoothed/filtered voltage conversion of the raw ADC reading.
- `ph_value`: The finalized, calculated pH level after applying the calibration slope and intercept.
- `ph_valid`: Boolean flag indicating if the pH reading is within acceptable sensor limits (0.1V - 4.9V) and within realistic physical bounds (0.0 - 14.0). If false, the reading is discarded/sent as `null`.

### Filtering and Safety
- **Noisy values**: The raw voltages are passed through a `MovingAverageFilter` covering the last 10 samples before conversion to smooth the data.
- **Sensor detection**: Voltages below 0.1V or above 4.9V indicate a disconnected or shorted sensor, immediately flagging the reading as invalid.
- **Out of bounds**: If the calculated `ph_value` falls outside 0.0 to 14.0, it is flagged as invalid and safely serialized as `null`.

## Calibration Procedure

The sensor requires two reference buffer solutions: pH 4.0 and pH 7.0.

1. **Clean the probe** using distilled water.
2. **Submerge in pH 7.0 buffer** and observe the voltage. Let it stabilize. Record this value (e.g., 2.5V).
3. **Clean the probe** again.
4. **Submerge in pH 4.0 buffer** and observe the voltage. Let it stabilize. Record this value (e.g., 3.0V).

### Applying Calibration Configuration via Serial API
You can update the calibration dynamically via the UART serial interface without recompiling the firmware. 
The system will automatically store it in the EEPROM and load it on subsequent reboots.

Format:
```
CAL_PH:<Voltage_at_pH_4>,<Voltage_at_pH_7>\n
```

Example command sending 3.04V for pH 4.0, and 2.53V for pH 7.0:
```
CAL_PH:3.04,2.53
```

When successful, the Arduino will respond with:
`[pH] Calibration updated and saved to EEPROM`
