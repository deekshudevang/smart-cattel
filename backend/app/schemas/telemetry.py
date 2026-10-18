from pydantic import BaseModel, ValidationError, model_validator
from typing import Optional, Tuple, Dict, Any, Union

class TelemetryPacket(BaseModel):
    protocol_version: Optional[int] = 1
    device_id: str
    cattle_id: Optional[str] = None
    sequence: Optional[int] = 0
    timestamp: int
    device_connected: Optional[bool] = True

    # Real Step Counting
    step_count_today: Optional[int] = 0
    step_count_lifetime: Optional[int] = 0
    boot_count: Optional[int] = None
    chk: Optional[int] = None

    # Accelerometer / MEMS (Phase 4 Contract + Legacy)
    acceleration_x: Optional[float] = None
    acceleration_y: Optional[float] = None
    acceleration_z: Optional[float] = None
    accelerometer_valid: Optional[bool] = False
    mems_x: Optional[float] = None
    mems_y: Optional[float] = None
    mems_z: Optional[float] = None
    mems_valid: Optional[bool] = False

    # SPO2 & Heart Rate (Phase 4 Contract + Legacy)
    heart_rate_bpm: Optional[float] = None
    heart_rate: Optional[float] = None
    heart_rate_valid: Optional[bool] = False
    bpm: Optional[float] = None
    bpm_valid: Optional[bool] = False
    bpm_quality: Optional[float] = None
    max_state: Optional[str] = None

    spo2_percent: Optional[float] = None
    spo2_valid: Optional[bool] = False
    spo2: Optional[float] = None
    spo2_quality: Optional[float] = None

    # Temperature & Humidity
    temperature_c: Optional[float] = None
    temperature_valid: Optional[bool] = False
    temperature: Optional[float] = None
    humidity: Optional[float] = None
    humidity_valid: Optional[bool] = False
    sensor_quality: Optional[float] = None
    last_successful_read: Optional[int] = None

    # pH Sensor
    ph_value: Optional[float] = None
    ph_valid: Optional[bool] = False
    ph: Optional[float] = None

    # LDR Sensor
    ldr_value: Optional[float] = None
    ldr_valid: Optional[bool] = False
    ldr: Optional[float] = None

    # GPS
    gps_valid: Optional[bool] = False
    gps_lat: Optional[Union[float, str]] = None
    gps_lon: Optional[Union[float, str]] = None
    gps_satellites: Optional[int] = 0

    @model_validator(mode="before")
    @classmethod
    def reconcile_fields(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        
        # 1. Cattle ID fallback
        if not data.get("cattle_id"):
            data["cattle_id"] = data.get("device_id", "CATTLE-001")

        # 2. Acceleration / MEMS reconciliation
        if data.get("acceleration_x") is not None and data.get("mems_x") is None:
            data["mems_x"] = data["acceleration_x"]
        elif data.get("mems_x") is not None and data.get("acceleration_x") is None:
            data["acceleration_x"] = data["mems_x"]

        if data.get("acceleration_y") is not None and data.get("mems_y") is None:
            data["mems_y"] = data["acceleration_y"]
        elif data.get("mems_y") is not None and data.get("acceleration_y") is None:
            data["acceleration_y"] = data["mems_y"]

        if data.get("acceleration_z") is not None and data.get("mems_z") is None:
            data["mems_z"] = data["acceleration_z"]
        elif data.get("mems_z") is not None and data.get("acceleration_z") is None:
            data["acceleration_z"] = data["mems_z"]

        accel_v = data.get("accelerometer_valid", data.get("mems_valid", False))
        data["accelerometer_valid"] = bool(accel_v)
        data["mems_valid"] = bool(accel_v)

        # 3. Heart Rate reconciliation & physiological range check (Safeguard 12)
        hr_val = data.get("heart_rate_bpm") if data.get("heart_rate_bpm") is not None else (data.get("bpm") if data.get("bpm") is not None else data.get("heart_rate"))
        if hr_val is not None:
            try:
                hr_num = float(hr_val)
                if 20.0 <= hr_num <= 300.0:
                    data["heart_rate_bpm"] = hr_num
                    data["bpm"] = hr_num
                    data["heart_rate"] = hr_num
                else:
                    data["heart_rate_bpm"] = None
                    data["bpm"] = None
                    data["heart_rate"] = None
                    data["heart_rate_valid"] = False
                    data["bpm_valid"] = False
            except (ValueError, TypeError):
                data["heart_rate_bpm"] = None
                data["bpm"] = None
                data["heart_rate"] = None
                data["heart_rate_valid"] = False
        else:
            data["heart_rate_bpm"] = None
            data["bpm"] = None
            data["heart_rate"] = None

        if data.get("heart_rate_bpm") is not None:
            hr_v = data.get("heart_rate_valid", data.get("bpm_valid", data.get("hr_valid", True)))
            data["heart_rate_valid"] = bool(hr_v)
            data["bpm_valid"] = bool(hr_v)
        else:
            data["heart_rate_valid"] = False
            data["bpm_valid"] = False

        # 4. SpO2 reconciliation & range check (Safeguard 12)
        spo2_val = data.get("spo2_percent") if data.get("spo2_percent") is not None else data.get("spo2")
        if spo2_val is not None:
            try:
                spo2_num = float(spo2_val)
                if 30.0 <= spo2_num <= 100.0:
                    data["spo2"] = spo2_num
                    data["spo2_percent"] = spo2_num
                    data["spo2_valid"] = bool(data.get("spo2_valid", True))
                else:
                    data["spo2"] = None
                    data["spo2_percent"] = None
                    data["spo2_valid"] = False
            except (ValueError, TypeError):
                data["spo2"] = None
                data["spo2_percent"] = None
                data["spo2_valid"] = False
        else:
            data["spo2"] = None
            data["spo2_percent"] = None
            data["spo2_valid"] = False

        # 5. Temperature reconciliation & range check (Safeguard 12)
        temp_val = data.get("temperature_c") if data.get("temperature_c") is not None else data.get("temperature")
        if temp_val is not None:
            try:
                temp_num = float(temp_val)
                if -20.0 <= temp_num <= 80.0:
                    data["temperature"] = temp_num
                    data["temperature_c"] = temp_num
                    data["temperature_valid"] = bool(data.get("temperature_valid", data.get("temp_valid", True)))
                else:
                    data["temperature"] = None
                    data["temperature_c"] = None
                    data["temperature_valid"] = False
            except (ValueError, TypeError):
                data["temperature"] = None
                data["temperature_c"] = None
                data["temperature_valid"] = False
        else:
            data["temperature"] = None
            data["temperature_c"] = None
            data["temperature_valid"] = False

        # 6. pH reconciliation & range check (Safeguard 12)
        ph_val = data.get("ph_value") if data.get("ph_value") is not None else data.get("ph")
        if ph_val is not None:
            try:
                ph_num = float(ph_val)
                if 0.0 <= ph_num <= 14.0:
                    data["ph"] = ph_num
                    data["ph_value"] = ph_num
                    data["ph_valid"] = bool(data.get("ph_valid", True))
                else:
                    data["ph"] = None
                    data["ph_value"] = None
                    data["ph_valid"] = False
            except (ValueError, TypeError):
                data["ph"] = None
                data["ph_value"] = None
                data["ph_valid"] = False
        else:
            data["ph"] = None
            data["ph_value"] = None
            data["ph_valid"] = False

        # 7. LDR reconciliation & range check (Safeguard 12)
        ldr_val = data.get("ldr_value") if data.get("ldr_value") is not None else data.get("ldr")
        if ldr_val is not None:
            try:
                ldr_num = float(ldr_val)
                if 0.0 <= ldr_num <= 1024.0:
                    data["ldr"] = ldr_num
                    data["ldr_value"] = ldr_num
                    data["ldr_valid"] = bool(data.get("ldr_valid", True))
                else:
                    data["ldr"] = None
                    data["ldr_value"] = None
                    data["ldr_valid"] = False
            except (ValueError, TypeError):
                data["ldr"] = None
                data["ldr_value"] = None
                data["ldr_valid"] = False
        else:
            data["ldr"] = None
            data["ldr_value"] = None
            data["ldr_valid"] = False

        # 8. GPS coordinates to float when possible
        for k in ("gps_lat", "gps_lon"):
            v = data.get(k)
            if v is not None:
                try:
                    data[k] = float(v)
                except (ValueError, TypeError):
                    pass

        return data


class TelemetryValidator:
    def __init__(self):
        self.last_sequences: Dict[str, int] = {}

    def process(self, data: Any, raw_line: Optional[str] = None) -> Tuple[bool, Optional[TelemetryPacket], str]:
        """
        Validates incoming data dictionary against TelemetryPacket schema,
        verifies checksum if present, and checks for sequence anomalies.
        Returns: (is_valid, validated_packet, rejection_reason)
        """
        # 1. Checksum validation (Safeguard 12)
        if raw_line and ',"chk":' in raw_line:
            idx = raw_line.rfind(',"chk":')
            prefix = raw_line[:idx]
            xor_sum = 0
            for b in prefix.encode('utf-8'):
                xor_sum ^= b

            if isinstance(data, dict) and data.get("chk") is not None:
                try:
                    expected_chk = int(data["chk"])
                    if xor_sum != expected_chk:
                        return False, None, f"checksum_mismatch: got {expected_chk}, expected {xor_sum}"
                except (ValueError, TypeError):
                    return False, None, "invalid_checksum_format"

        # 2. Pydantic Schema Parsing
        try:
            packet = TelemetryPacket(**data)
        except ValidationError as e:
            return False, None, f"schema_error: {e.errors()[0]['msg']}"
        except Exception as e:
            return False, None, f"malformed_data: {str(e)}"

        # 3. Sequence / Reconnect validation
        dev_id = packet.device_id
        seq = packet.sequence or 0
        last_seq = self.last_sequences.get(dev_id)

        if last_seq is not None and seq != 0:
            if seq == last_seq:
                return False, None, "duplicate_sequence"
            if seq < last_seq:
                # Handle integer wrap-around or Arduino reboot
                if last_seq - seq > 1000 or seq < 10:
                    pass
                else:
                    return False, None, "out_of_order_sequence"

        self.last_sequences[dev_id] = seq
        return True, packet, "ok"
