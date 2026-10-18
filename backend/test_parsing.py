import json
from app.schemas.telemetry import TelemetryPacket, TelemetryValidator

data_str = """
{"protocol_version":1,"cattle_id":"CATTLE-001","device_id":"SC-001","sequence":35,"timestamp":53500,"device_connected":true,"step_count_today":42,"step_count_lifetime":1420,"accelerometer_valid":true,"acceleration_x":0.25,"acceleration_y":-0.12,"acceleration_z":9.81,"max_state":"VALID","heart_rate_bpm":72,"heart_rate_valid":true,"bpm_quality":0.95,"spo2_percent":98,"spo2_valid":true,"spo2_quality":0.95,"temperature_c":38.6,"temperature_valid":true,"humidity":62.0,"humidity_valid":true,"sensor_quality":1.0,"last_successful_read":53000,"ph_value":6.85,"ph_valid":true,"ldr_value":512,"ldr_valid":true,"gps_valid":true,"gps_lat":12.9716,"gps_lon":77.5946,"gps_satellites":6}
"""
data = json.loads(data_str)
v = TelemetryValidator()
ok, packet, reason = v.process(data)
print("Standard Packet Valid:", ok, "Reason:", reason)
if packet:
    print("cattle_id:", packet.cattle_id)
    print("steps_today:", packet.step_count_today, "steps_lifetime:", packet.step_count_lifetime)
    print("accel:", (packet.acceleration_x, packet.acceleration_y, packet.acceleration_z), "valid:", packet.accelerometer_valid)
    print("legacy mems:", (packet.mems_x, packet.mems_y, packet.mems_z), "valid:", packet.mems_valid)
    print("HR:", packet.heart_rate_bpm, "legacy bpm:", packet.bpm, "valid:", packet.heart_rate_valid)
    print("SpO2:", packet.spo2_percent, "legacy spo2:", packet.spo2, "valid:", packet.spo2_valid)
    print("Temp:", packet.temperature_c, "legacy temp:", packet.temperature, "valid:", packet.temperature_valid)
    print("pH:", packet.ph_value, "legacy ph:", packet.ph, "valid:", packet.ph_valid)
    print("LDR:", packet.ldr_value, "legacy ldr:", packet.ldr, "valid:", packet.ldr_valid)

