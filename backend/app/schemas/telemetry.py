from pydantic import BaseModel, ValidationError
from typing import Optional, Tuple, Dict, Any

class TelemetryPacket(BaseModel):
    protocol_version: str
    device_id: str
    cattle_id: str
    sequence: int
    timestamp: int

    # SPO2
    spo2: Optional[float] = None
    spo2_valid: bool
    spo2_quality: Optional[float] = None

    # BPM
    bpm: Optional[float] = None
    bpm_valid: bool
    bpm_quality: Optional[float] = None
    max_state: Optional[str] = None

    # Temperature
    temperature: Optional[float] = None
    temperature_valid: bool

    # Humidity
    humidity: Optional[float] = None
    humidity_valid: bool
    
    # DHT Health
    sensor_quality: Optional[float] = None
    last_successful_read: Optional[int] = None

    # MEMS (Accelerometer/Gyro)
    mems_x: Optional[float] = None
    mems_y: Optional[float] = None
    mems_z: Optional[float] = None
    mems_valid: bool

    # pH
    ph: Optional[float] = None
    ph_valid: bool

    # LDR (Light)
    ldr: Optional[float] = None
    ldr_valid: bool

    # GPS
    gps_valid: bool
    gps_lat: Optional[float] = None
    gps_lon: Optional[float] = None
    gps_satellites: Optional[int] = None


class TelemetryValidator:
    def __init__(self):
        self.last_sequences: Dict[str, int] = {}

    def process(self, data: Any) -> Tuple[bool, Optional[TelemetryPacket], str]:
        """
        Validates the incoming data dictionary against the TelemetryPacket schema
        and checks for sequence anomalies (duplicates, out-of-order).
        Returns a tuple: (is_valid, validated_packet, rejection_reason)
        """
        try:
            packet = TelemetryPacket(**data)
        except ValidationError as e:
            return False, None, f"schema_error: {e.errors()[0]['msg']}"
        except Exception as e:
            return False, None, f"malformed_data: {str(e)}"

        dev_id = packet.device_id
        last_seq = self.last_sequences.get(dev_id)

        if last_seq is not None:
            if packet.sequence == last_seq:
                return False, None, "duplicate_sequence"
            if packet.sequence < last_seq:
                # Handle potential integer wrap-around (e.g. if seq resets)
                # But strict logic demands detecting out of order
                if last_seq - packet.sequence > 1000:
                    # Accept wrap around / restart
                    pass
                else:
                    return False, None, "out_of_order_sequence"

        self.last_sequences[dev_id] = packet.sequence
        return True, packet, "ok"
