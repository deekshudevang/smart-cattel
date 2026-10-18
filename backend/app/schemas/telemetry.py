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

        # 3. Heart Rate reconciliation
        hr_val = data.get("heart_rate_bpm") if data.get("heart_rate_bpm") is not None else (data.get("bpm") if data.get("bpm") is not None else data.get("heart_rate"))
        if hr_val is not None:
            data["heart_rate_bpm"] = hr_val
            data["bpm"] = hr_val
            data["heart_rate"] = hr_val
        else:
            data["heart_rate_bpm"] = None
            data["bpm"] = None
            data["heart_rate"] = None

        hr_v = data.get("heart_rate_valid", data.get("bpm_valid", False))
        data["heart_rate_valid"] = bool(hr_v)
        data["bpm_valid"] = bool(hr_v)

        # 4. SpO2 reconciliation
        if data.get("spo2_percent") is not None and data.get("spo2") is None:
            data["spo2"] = data["spo2_percent"]
        elif data.get("spo2") is not None and data.get("spo2_percent") is None:
            data["spo2_percent"] = data["spo2"]

        spo2_v = data.get("spo2_valid", False)
        data["spo2_valid"] = bool(spo2_v)

        # 5. Temperature reconciliation
        if data.get("temperature_c") is not None and data.get("temperature") is None:
            data["temperature"] = data["temperature_c"]
        elif data.get("temperature") is not None and data.get("temperature_c") is None:
            data["temperature_c"] = data["temperature"]

        # 6. pH reconciliation
        if data.get("ph_value") is not None and data.get("ph") is None:
            data["ph"] = data["ph_value"]
        elif data.get("ph") is not None and data.get("ph_value") is None:
            data["ph_value"] = data["ph"]

        # 7. LDR reconciliation
        if data.get("ldr_value") is not None and data.get("ldr") is None:
            data["ldr"] = data["ldr_value"]
        elif data.get("ldr") is not None and data.get("ldr_value") is None:
            data["ldr_value"] = data["ldr"]

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

    def process(self, data: Any) -> Tuple[bool, Optional[TelemetryPacket], str]:
        """
        Validates incoming data dictionary against TelemetryPacket schema
        and checks for sequence anomalies (duplicates, out-of-order).
        Returns: (is_valid, validated_packet, rejection_reason)
        """
        try:
            packet = TelemetryPacket(**data)
        except ValidationError as e:
            return False, None, f"schema_error: {e.errors()[0]['msg']}"
        except Exception as e:
            return False, None, f"malformed_data: {str(e)}"

        dev_id = packet.device_id
        seq = packet.sequence or 0
        last_seq = self.last_sequences.get(dev_id)

        if last_seq is not None and seq != 0:
            if seq == last_seq:
                return False, None, "duplicate_sequence"
            if seq < last_seq:
                # Handle potential integer wrap-around or Arduino restart
                if last_seq - seq > 1000 or seq < 10:
                    pass
                else:
                    return False, None, "out_of_order_sequence"

        self.last_sequences[dev_id] = seq
        return True, packet, "ok"
