import pytest
import math

class MovingAverageFilter:
    def __init__(self, size=10):
        self.size = size
        self.buffer = []
        
    def process(self, value):
        self.buffer.append(value)
        if len(self.buffer) > self.size:
            self.buffer.pop(0)
        return sum(self.buffer) / len(self.buffer)

def ph_algorithm(ph_raw, voltage4, voltage7, filter_instance):
    """
    Simulates the Arduino pH algorithm.
    Returns (ph_valid, ph_value)
    """
    # 1. raw ADC conversion
    raw_voltage = ph_raw * (5.0 / 1023.0)
    
    # 2. apply smoothing/filtering
    ph_voltage = filter_instance.process(raw_voltage)
    
    # 3. detect disconnected/invalid sensor
    if ph_voltage < 0.1 or ph_voltage > 4.9:
        return False, None
        
    # 4. calibration
    diff = voltage7 - voltage4
    if abs(diff) < 0.01:
        diff = -0.5
    
    slope = (7.0 - 4.0) / diff
    intercept = 7.0 - slope * voltage7
    
    ph_value = slope * ph_voltage + intercept
    
    # 5. out-of-range values validation
    if 0.0 <= ph_value <= 14.0:
        return True, round(ph_value, 2)
    else:
        return False, None

def test_raw_adc_conversion():
    # ADC value 511 roughly equals ~2.5V
    # If 7.0 buffer is at 2.5V, pH should be near 7.0
    ma_filter = MovingAverageFilter(size=1)
    valid, value = ph_algorithm(511, 3.0, 2.5, ma_filter)
    assert valid is True
    assert abs(value - 7.0) < 0.1

def test_calibration():
    # Testing with ideal voltages
    ma_filter = MovingAverageFilter(size=1)
    # 4.0 pH = 3.0V, 7.0 pH = 2.5V. 
    # Let's test ADC value that gives exactly 3.0V (613.8 -> 614)
    valid, value = ph_algorithm(614, 3.0, 2.5, ma_filter)
    assert valid is True
    assert abs(value - 4.0) < 0.1

def test_invalid_values():
    # Detect disconnected sensor (0V or 5V)
    ma_filter = MovingAverageFilter(size=1)
    valid, value = ph_algorithm(0, 3.0, 2.5, ma_filter)
    assert valid is False
    assert value is None
    
    valid, value = ph_algorithm(1023, 3.0, 2.5, ma_filter)
    assert valid is False
    assert value is None

def test_out_of_range_values():
    # Valid voltage but produces pH > 14.0 or < 0.0
    ma_filter = MovingAverageFilter(size=1)
    
    # E.g., if v7=2.5, v4=3.0, slope = -6. 
    # If voltage = 0.5V, pH = -6 * 0.5 + 22 = 19 (out of range)
    valid, value = ph_algorithm(102, 3.0, 2.5, ma_filter) 
    assert valid is False
    assert value is None

def test_noisy_values():
    ma_filter = MovingAverageFilter(size=3)
    
    # Pass in a noisy sequence that averages out to ~2.5V (511 ADC)
    ph_algorithm(400, 3.0, 2.5, ma_filter)
    ph_algorithm(622, 3.0, 2.5, ma_filter)
    valid, value = ph_algorithm(511, 3.0, 2.5, ma_filter)
    
    assert valid is True
    assert abs(value - 7.0) < 0.1
