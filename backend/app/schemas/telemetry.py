from pydantic import BaseModel, ValidationError, model_validator, Field
from typing import Optional, Tuple, Dict, Any, Union

class TelemetryPacket(BaseModel):
    protocol_version: Optional[int] = 1
    device_id: str
    cattle_id: Optional[str] = None
    sequence: Optional[int] = 0
    timestamp: int
    device_connected: Optional[bool] = True

    # Real Step Counting (Canonical)
    step_count_today: Optional[int] = 0
    step_count_lifetime: Optional[int] = 0
    boot_count: Optional[int] = None
    chk: Optional[int] = None

    # Provenance & Tracking
    source: Optional[str] = "serial"
    is_simulated: Optional[bool] = False
    raw_packet: Optional[str] = None
    checksum_valid: Optional[bool] = True

    # Accelerometer (Canonical)
    acceleration_x: Optional[float] = None
    acceleration_y: Optional[float] = None
    acceleration_z: Optional[float] = None
    accelerometer_valid: Optional[bool] = False

    # SPO2 & Heart Rate (Canonical)
    heart_rate: Optional[float] = None
    heart_rate_valid: Optional[bool] = False
    bpm_quality: Optional[float] = None
    max_state: Optional[str] = None

    spo2: Optional[float] = None
    spo2_valid: Optional[bool] = False
    spo2_quality: Optional[float] = None

    # Temperature & Humidity (Canonical)
    temperature: Optional[float] = None
    temperature_valid: Optional[bool] = False
    humidity: Optional[float] = None
    humidity_valid: Optional[bool] = False
    sensor_quality: Optional[float] = None
    last_successful_read: Optional[int] = None

    # pH Sensor (Canonical)
    ph: Optional[float] = None
    ph_valid: Optional[bool] = False

    # LDR Sensor (Canonical)
    ldr: Optional[float] = None
    ldr_valid: Optional[bool] = False

    # GPS (Canonical)
    gps_valid: Optional[bool] = False
    gps_lat: Optional[Union[float, str]] = None
    gps_lon: Optional[Union[float, str]] = None
    gps_satellites: Optional[int] = 0

    # Weight / Load Cell (Canonical)
    body_weight_kg: Optional[float] = None
    weight_raw: Optional[int] = None
    weight_stable: Optional[bool] = False
    weight_calibrated: Optional[bool] = False
    weight_valid: Optional[bool] = False
    weight_sensor_status: Optional[str] = "disconnected"

    # Explicit validity and reason maps for clients
    validity: Dict[str, bool] = Field(default_factory=dict)
    validity_reasons: Dict[str, Optional[str]] = Field(default_factory=dict)

    # ── Deprecated Property Accessors (Sunset: 2026-12-31) ──
    @property
    def weight_kg(self) -> Optional[float]:
        return self.body_weight_kg
    @property
    def bpm(self) -> Optional[float]:
        return self.heart_rate

    @property
    def heart_rate_bpm(self) -> Optional[float]:
        return self.heart_rate

    @property
    def bpm_valid(self) -> Optional[bool]:
        return self.heart_rate_valid

    @property
    def spo2_percent(self) -> Optional[float]:
        return self.spo2

    @property
    def temperature_c(self) -> Optional[float]:
        return self.temperature

    @property
    def ph_value(self) -> Optional[float]:
        return self.ph

    @property
    def ldr_value(self) -> Optional[float]:
        return self.ldr

    @property
    def mems_x(self) -> Optional[float]:
        return self.acceleration_x

    @property
    def mems_y(self) -> Optional[float]:
        return self.acceleration_y

    @property
    def mems_z(self) -> Optional[float]:
        return self.acceleration_z

    @property
    def mems_valid(self) -> Optional[bool]:
        return self.accelerometer_valid

    @property
    def steps_today(self) -> Optional[int]:
        return self.step_count_today

    @property
    def lifetime_steps(self) -> Optional[int]:
        return self.step_count_lifetime

    @model_validator(mode="before")
    @classmethod
    def reconcile_fields(cls, data: Any) -> Any:
        if not isinstance(data, dict):
            return data
        
        # 1. Cattle ID fallback and normalization
        cid = data.get("cattle_id") or data.get("device_id") or "CATTLE-001"
        data["cattle_id"] = str(cid).strip().upper()

        # 2. Step counting alias mapping
        if data.get("step_count_today") is None and data.get("steps_today") is not None:
            data["step_count_today"] = data["steps_today"]
        if data.get("step_count_lifetime") is None and data.get("lifetime_steps") is not None:
            data["step_count_lifetime"] = data["lifetime_steps"]

        # 3. Acceleration / MEMS reconciliation to canonical acceleration_x/y/z
        ax = data.get("acceleration_x") if data.get("acceleration_x") is not None else (data.get("mems_x") if data.get("mems_x") is not None else data.get("ax"))
        ay = data.get("acceleration_y") if data.get("acceleration_y") is not None else (data.get("mems_y") if data.get("mems_y") is not None else data.get("ay"))
        az = data.get("acceleration_z") if data.get("acceleration_z") is not None else (data.get("mems_z") if data.get("mems_z") is not None else data.get("az"))
        
        data["acceleration_x"] = float(ax) if ax is not None else None
        data["acceleration_y"] = float(ay) if ay is not None else None
        data["acceleration_z"] = float(az) if az is not None else None

        accel_v = data.get("accelerometer_valid", data.get("mems_valid", False))
        data["accelerometer_valid"] = bool(accel_v)
        reasons = dict(data.get("validity_reasons", {}))
        reasons["accelerometer"] = None if data["accelerometer_valid"] else "Sensor not connected"

        # 4. Heart Rate reconciliation to canonical heart_rate & range check
        hr_val = data.get("heart_rate") if data.get("heart_rate") is not None else (data.get("heart_rate_bpm") if data.get("heart_rate_bpm") is not None else data.get("bpm"))
        if hr_val is not None:
            try:
                hr_num = float(hr_val)
                if 20.0 <= hr_num <= 300.0:
                    data["heart_rate"] = hr_num
                    reasons["heart_rate"] = None
                else:
                    data["heart_rate"] = None
                    data["heart_rate_valid"] = False
                    reasons["heart_rate"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["heart_rate"] = None
                data["heart_rate_valid"] = False
                reasons["heart_rate"] = "Reading out of valid range"
        else:
            data["heart_rate"] = None
            reasons["heart_rate"] = "No contact"

        if data.get("heart_rate") is not None:
            hr_v = data.get("heart_rate_valid", data.get("bpm_valid", data.get("hr_valid", True)))
            data["heart_rate_valid"] = bool(hr_v)
            if not data["heart_rate_valid"] and not reasons.get("heart_rate"):
                reasons["heart_rate"] = "No contact"
        else:
            data["heart_rate_valid"] = False

        # 5. SpO2 reconciliation to canonical spo2 & range check
        spo2_val = data.get("spo2") if data.get("spo2") is not None else data.get("spo2_percent")
        if spo2_val is not None:
            try:
                spo2_num = float(spo2_val)
                if 30.0 <= spo2_num <= 100.0:
                    data["spo2"] = spo2_num
                    data["spo2_valid"] = bool(data.get("spo2_valid", True))
                    reasons["spo2"] = None if data["spo2_valid"] else "No contact"
                else:
                    data["spo2"] = None
                    data["spo2_valid"] = False
                    reasons["spo2"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["spo2"] = None
                data["spo2_valid"] = False
                reasons["spo2"] = "Reading out of valid range"
        else:
            data["spo2"] = None
            data["spo2_valid"] = False
            reasons["spo2"] = "No contact"

        # 6. Temperature reconciliation to canonical temperature & range check
        temp_val = data.get("temperature") if data.get("temperature") is not None else data.get("temperature_c")
        if temp_val is not None:
            try:
                temp_num = float(temp_val)
                if -20.0 <= temp_num <= 80.0:
                    data["temperature"] = temp_num
                    data["temperature_valid"] = bool(data.get("temperature_valid", data.get("temp_valid", True)))
                    reasons["temperature"] = None if data["temperature_valid"] else "Sensor not connected"
                else:
                    data["temperature"] = None
                    data["temperature_valid"] = False
                    reasons["temperature"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["temperature"] = None
                data["temperature_valid"] = False
                reasons["temperature"] = "Reading out of valid range"
        else:
            data["temperature"] = None
            data["temperature_valid"] = False
            reasons["temperature"] = "Sensor not connected"

        # 7. pH reconciliation to canonical ph & range check
        ph_val = data.get("ph") if data.get("ph") is not None else data.get("ph_value")
        if ph_val is not None:
            try:
                ph_num = float(ph_val)
                if 0.0 <= ph_num <= 14.0:
                    data["ph"] = ph_num
                    data["ph_valid"] = bool(data.get("ph_valid", True))
                    reasons["ph"] = None if data["ph_valid"] else "Probe not in solution"
                else:
                    data["ph"] = None
                    data["ph_valid"] = False
                    reasons["ph"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["ph"] = None
                data["ph_valid"] = False
                reasons["ph"] = "Reading out of valid range"
        else:
            data["ph"] = None
            data["ph_valid"] = False
            reasons["ph"] = "Probe not in solution"

        # 8. LDR reconciliation to canonical ldr & range check (preserving 0 lux)
        ldr_val = data.get("ldr") if data.get("ldr") is not None else data.get("ldr_value")
        if ldr_val is not None:
            try:
                ldr_num = float(ldr_val)
                if 0.0 <= ldr_num <= 1024.0:
                    data["ldr"] = ldr_num
                    data["ldr_valid"] = bool(data.get("ldr_valid", True))
                    reasons["ldr"] = None if data["ldr_valid"] else "Sensor not connected"
                else:
                    data["ldr"] = None
                    data["ldr_valid"] = False
                    reasons["ldr"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["ldr"] = None
                data["ldr_valid"] = False
                reasons["ldr"] = "Reading out of valid range"
        else:
            data["ldr"] = None
            data["ldr_valid"] = False
            reasons["ldr"] = "Sensor not connected"

        # 9. Weight / Load cell reconciliation to canonical body_weight_kg & range check
        w_val = data.get("body_weight_kg") if data.get("body_weight_kg") is not None else data.get("weight_kg")
        if w_val is None and data.get("weight") is not None:
            w_val = data.get("weight")
        if w_val is not None:
            try:
                w_num = float(w_val)
                if 0.0 <= w_num <= 1500.0:
                    data["body_weight_kg"] = round(w_num, 2)
                    data["weight_valid"] = bool(data.get("weight_valid", True))
                    reasons["weight"] = None if data["weight_valid"] else "Sensor disconnected"
                else:
                    data["body_weight_kg"] = None
                    data["weight_valid"] = False
                    reasons["weight"] = "Reading out of valid range"
            except (ValueError, TypeError):
                data["body_weight_kg"] = None
                data["weight_valid"] = False
                reasons["weight"] = "Reading out of valid range"
        else:
            data["body_weight_kg"] = None
            data["weight_valid"] = False
            reasons["weight"] = "Sensor disconnected"

        data["weight_stable"] = bool(data.get("weight_stable", False))
        data["weight_calibrated"] = bool(data.get("weight_calibrated", False))
        if not data.get("weight_sensor_status"):
            if not data["weight_valid"]:
                data["weight_sensor_status"] = "disconnected"
            elif not data["weight_calibrated"]:
                data["weight_sensor_status"] = "uncalibrated"
            elif not data["weight_stable"]:
                data["weight_sensor_status"] = "unstable"
            else:
                data["weight_sensor_status"] = "stable"

        # 10. Build explicit validity and validity_reasons maps
        data["validity"] = {
            "heart_rate": data.get("heart_rate_valid", False),
            "spo2": data.get("spo2_valid", False),
            "temperature": data.get("temperature_valid", False),
            "ph": data.get("ph_valid", False),
            "ldr": data.get("ldr_valid", False),
            "accelerometer": data.get("accelerometer_valid", False),
            "weight": data.get("weight_valid", False),
        }
        data["validity_reasons"] = reasons

        # 11. GPS coordinates to float when possible
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
