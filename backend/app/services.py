from sqlalchemy.orm import Session
from database import models
from database.database import SessionLocal
import sys
import os

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(PROJECT_ROOT, "ml", "inference"))
from datetime import datetime, date, timezone
from step_manager import AuthoritativeStepManager
from predictor import HealthPredictor
from fall_detection import fall_detector
from engine import HealthRiskEngine
from baseline_engine import BaselineEngine
from alert_engine import AlertService as EngineAlertService, AlertType, AlertSeverity, AlertLifecycle
from thresholds import evaluate_telemetry_health, CATTLE_THRESHOLDS

class DatabaseService:
    @staticmethod
    def save_reading(data: dict) -> int:
        db = SessionLocal()
        try:
            valid_keys = models.SensorReading.__table__.columns.keys()
            filtered_data = {k: v for k, v in data.items() if k in valid_keys}
            
            ts = filtered_data.get("timestamp")
            from datetime import datetime, date, timezone
            if ts is not None and not isinstance(ts, (datetime, date)):
                try:
                    ts_float = float(ts)
                    if ts_float > 1e10:
                        ts_float = ts_float / 1000.0
                    if ts_float < 1_000_000_000:
                        # Arduino uptime millis, not epoch timestamp
                        filtered_data["timestamp"] = datetime.now(timezone.utc)
                    else:
                        filtered_data["timestamp"] = datetime.fromtimestamp(ts_float)
                except (ValueError, TypeError):
                    filtered_data["timestamp"] = datetime.now(timezone.utc)
            elif ts is None:
                filtered_data["timestamp"] = datetime.now(timezone.utc)

            filtered_data.setdefault("source", data.get("source", "serial"))
            filtered_data.setdefault("is_simulated", data.get("is_simulated", False))
            filtered_data.setdefault("raw_packet", data.get("raw_packet"))
            filtered_data.setdefault("checksum_valid", data.get("checksum_valid", True))
                
            reading = models.SensorReading(**filtered_data)
            db.add(reading)

            # Sync steps to Activity table for today if step_count_today is present
            cid = filtered_data.get("cattle_id")
            steps_today = filtered_data.get("step_count_today")
            if cid and steps_today is not None:
                try:
                    local_now = AuthoritativeStepManager.get_local_now()
                    today_start = datetime.combine(local_now.date(), datetime.min.time())
                    today_act = db.query(models.Activity).filter(
                        models.Activity.cattle_id == cid,
                        models.Activity.date >= today_start
                    ).first()
                    if today_act:
                        today_act.steps = int(steps_today)
                    else:
                        new_act = models.Activity(
                            cattle_id=cid,
                            date=datetime.now(timezone.utc),
                            steps=int(steps_today)
                        )
                        db.add(new_act)
                except Exception:
                    pass

            db.commit()
            db.refresh(reading)
            return reading.id
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def save_prediction(reading_id: int, preds: dict):
        db = SessionLocal()
        try:
            overall = "abnormal" if any(v.get("status") == "abnormal" for v in preds.values()) else "normal"
            prediction = models.Prediction(
                reading_id=reading_id,
                spo2_status=preds.get("spo2", {}).get("status", "normal"),
                heart_rate_status=preds.get("heart_rate", {}).get("status", "normal"),
                temperature_status=preds.get("temperature", {}).get("status", "normal"),
                mems_status=preds.get("mems", {}).get("status", "normal"),
                ph_status=preds.get("ph", {}).get("status", "normal"),
                ldr_status=preds.get("ldr", {}).get("status", "normal"),
                overall_status=overall,
            )
            db.add(prediction)
            db.commit()
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def save_health_assessment(cattle_id: str, assessment: dict):
        import json
        db = SessionLocal()
        try:
            ha = models.HealthAssessment(
                cattle_id=cattle_id,
                health_score=assessment.get("health_score"),
                risk_level=assessment.get("risk_level"),
                confidence=assessment.get("confidence"),
                factors=json.dumps(assessment.get("factors", []))
            )
            db.add(ha)
            db.commit()
        except Exception:
            db.rollback()
        finally:
            db.close()

    @staticmethod
    def save_weight_reading(
        cattle_id: str,
        weight_kg: float,
        device_id: str = None,
        raw_value: int = None,
        is_stable: bool = True,
        is_calibrated: bool = True,
        measured_at: datetime = None,
        source: str = "manual",
        is_simulated: bool = False
    ) -> models.CattleWeightReading:
        db = SessionLocal()
        try:
            if measured_at is None:
                measured_at = datetime.now(timezone.utc)
            reading = models.CattleWeightReading(
                cattle_id=cattle_id,
                device_id=device_id,
                weight_kg=round(weight_kg, 2),
                raw_value=raw_value,
                is_stable=is_stable,
                is_calibrated=is_calibrated,
                measured_at=measured_at,
                source=source,
                is_simulated=is_simulated
            )
            db.add(reading)
            cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id).first()
            if cow:
                cow.weight = round(weight_kg, 2)
            db.commit()
            db.refresh(reading)
            return reading
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def get_weight_statistics(cattle_id: str) -> dict:
        db = SessionLocal()
        try:
            from datetime import timedelta
            now = datetime.now(timezone.utc)

            readings = db.query(models.CattleWeightReading).filter(
                models.CattleWeightReading.cattle_id == cattle_id
            ).order_by(models.CattleWeightReading.measured_at.desc()).limit(100).all()

            count = len(readings)
            if not readings:
                cow = db.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id).first()
                cow_w = cow.weight if cow else None
                return {
                    "cattle_id": cattle_id,
                    "current_weight_kg": cow_w,
                    "previous_weight_kg": None,
                    "difference_kg": 0.0 if cow_w else None,
                    "percentage_change": 0.0 if cow_w else None,
                    "trend": "stable",
                    "average_7d": cow_w,
                    "average_30d": cow_w,
                    "last_measured_at": None,
                    "is_stable": True if cow_w else False,
                    "is_calibrated": True if cow_w else False,
                    "readings_count": 1 if cow_w else 0
                }

            latest = readings[0]
            current_w = latest.weight_kg
            previous_w = readings[1].weight_kg if len(readings) > 1 else current_w
            diff = round(current_w - previous_w, 2)
            pct = round((diff / previous_w * 100.0), 2) if previous_w else 0.0
            trend = "gain" if diff > 0.5 else ("loss" if diff < -0.5 else "stable")

            weights_7d = [r.weight_kg for r in readings if (now - (r.measured_at if r.measured_at.tzinfo else r.measured_at.replace(tzinfo=timezone.utc))).days <= 7]
            weights_30d = [r.weight_kg for r in readings if (now - (r.measured_at if r.measured_at.tzinfo else r.measured_at.replace(tzinfo=timezone.utc))).days <= 30]

            avg_7d = round(sum(weights_7d) / len(weights_7d), 2) if weights_7d else current_w
            avg_30d = round(sum(weights_30d) / len(weights_30d), 2) if weights_30d else current_w

            return {
                "cattle_id": cattle_id,
                "current_weight_kg": current_w,
                "previous_weight_kg": previous_w,
                "difference_kg": diff,
                "percentage_change": pct,
                "trend": trend,
                "average_7d": avg_7d,
                "average_30d": avg_30d,
                "last_measured_at": latest.measured_at,
                "is_stable": latest.is_stable,
                "is_calibrated": latest.is_calibrated,
                "readings_count": count
            }
        finally:
            db.close()
    @staticmethod
    def save_step_reading(
        cattle_id: str,
        steps_today: int,
        total_steps: int,
        step_increment: int = 0,
        device_id: str = None,
        step_sensor_status: str = "active",
        step_algorithm_version: str = "1.0",
        measured_at: datetime = None,
        is_simulated: bool = False
    ) -> models.CattleStepReading:
        db = SessionLocal()
        try:
            if measured_at is None:
                measured_at = datetime.now(timezone.utc)
            reading = models.CattleStepReading(
                cattle_id=cattle_id,
                device_id=device_id,
                steps_today=int(steps_today),
                total_steps=int(total_steps),
                step_increment=int(step_increment),
                step_sensor_status=step_sensor_status,
                step_algorithm_version=step_algorithm_version,
                measured_at=measured_at,
                is_simulated=is_simulated
            )
            db.add(reading)
            db.commit()
            db.refresh(reading)
            return reading
        except Exception:
            db.rollback()
            raise
        finally:
            db.close()

    @staticmethod
    def get_latest_step_reading(cattle_id: str, include_simulated: bool = False):
        db = SessionLocal()
        try:
            q = db.query(models.CattleStepReading).filter(
                models.CattleStepReading.cattle_id == cattle_id
            )
            if not include_simulated:
                q = q.filter(models.CattleStepReading.is_simulated == False)
            return q.order_by(models.CattleStepReading.measured_at.desc()).first()
        finally:
            db.close()

    @staticmethod
    def get_step_history(cattle_id: str, limit: int = 50, include_simulated: bool = False):
        db = SessionLocal()
        try:
            q = db.query(models.CattleStepReading).filter(
                models.CattleStepReading.cattle_id == cattle_id
            )
            if not include_simulated:
                q = q.filter(models.CattleStepReading.is_simulated == False)
            return q.order_by(models.CattleStepReading.measured_at.desc()).limit(limit).all()
        finally:
            db.close()

    @staticmethod
    def get_step_statistics(cattle_id: str, include_simulated: bool = False):
        db = SessionLocal()
        try:
            now = datetime.now(timezone.utc)
            q = db.query(models.CattleStepReading).filter(
                models.CattleStepReading.cattle_id == cattle_id
            )
            if not include_simulated:
                q = q.filter(models.CattleStepReading.is_simulated == False)
            readings = q.order_by(models.CattleStepReading.measured_at.desc()).limit(200).all()

            if not readings:
                latest_sr = db.query(models.SensorReading).filter(
                    models.SensorReading.cattle_id == cattle_id
                ).order_by(models.SensorReading.timestamp.desc()).first()
                steps_today = (latest_sr.step_count_today or 0) if latest_sr else 0
                total_steps = (latest_sr.step_count_lifetime or 0) if latest_sr else 0
                return {
                    "cattle_id": cattle_id,
                    "steps_today": steps_today,
                    "total_steps": total_steps,
                    "daily_average": float(steps_today),
                    "hourly_average": round(steps_today / 24.0, 1),
                    "peak_hour": None,
                    "activity_status": "resting" if steps_today < 100 else "normal",
                    "step_sensor_status": "active" if latest_sr else "offline",
                    "last_updated": latest_sr.timestamp if latest_sr else None
                }

            latest = readings[0]
            steps_today = latest.steps_today
            total_steps = latest.total_steps

            increments_7d = [r.step_increment for r in readings if (now - (r.measured_at if r.measured_at.tzinfo else r.measured_at.replace(tzinfo=timezone.utc))).days <= 7]
            sum_7d = sum(increments_7d)
            daily_avg = round(sum_7d / 7.0, 1) if sum_7d > 0 else float(steps_today)

            hourly_avg = round(steps_today / 24.0, 1)
            activity_status = "high" if steps_today > 3000 else ("active" if steps_today > 1000 else ("normal" if steps_today > 200 else "resting"))

            return {
                "cattle_id": cattle_id,
                "steps_today": steps_today,
                "total_steps": total_steps,
                "daily_average": daily_avg,
                "hourly_average": hourly_avg,
                "peak_hour": latest.measured_at.hour if latest.measured_at else None,
                "activity_status": activity_status,
                "step_sensor_status": latest.step_sensor_status,
                "last_updated": latest.measured_at
            }
        finally:
            db.close()



class AlertService:
    @staticmethod
    async def broadcast_alert(manager, cattle_id: str, data: dict, preds: dict, assessment: dict = None):
        import json
        overall = "abnormal" if any(v.get("status") == "abnormal" for v in preds.values()) else "normal"
        fall_detected = preds.get("mems", {}).get("status") == "abnormal"
        health_with_overall = dict(preds)
        health_with_overall["overall"] = overall
        health_with_overall["fall_detected"] = fall_detected
        if assessment:
            health_with_overall["assessment"] = assessment

        # Retrieve cow name for notifications
        cow_name = cattle_id
        try:
            db_c = SessionLocal()
            c_row = db_c.query(models.Cattle).filter(models.Cattle.cattle_id == cattle_id).first()
            if c_row and c_row.name:
                cow_name = c_row.name
            db_c.close()
        except Exception:
            pass

        sensor_name_map = {
            "temperature": "Body Temperature",
            "heart_rate": "Heart Rate",
            "spo2": "SpO2",
            "ph": "pH Level",
            "mems": "Activity / Posture",
            "ldr": "Ambient Light",
            "weight": "Body Weight"
        }

        abnormal_events = []
        now_utc = datetime.now(timezone.utc)
        now_iso = now_utc.isoformat()
        now_ms = int(now_utc.timestamp() * 1000)

        for sensor_key, pred in preds.items():
            if sensor_key == "overall":
                continue
            if isinstance(pred, dict) and pred.get("status") == "abnormal":
                s_name = sensor_name_map.get(sensor_key, sensor_key.replace("_", " ").title())
                raw_val = pred.get("value")
                fmt_val = pred.get("formatted_value") or (f"{raw_val}" if raw_val is not None else "Abnormal")
                abnormal_events.append({
                    "event_id": f"EVT-{cattle_id}-{sensor_key.upper()}-{now_ms}",
                    "cattle_id": cattle_id,
                    "cattle_name": cow_name,
                    "sensor_key": sensor_key,
                    "sensor_name": s_name,
                    "current_value": fmt_val,
                    "raw_value": raw_val,
                    "status": pred.get("direction", "ABNORMAL"),
                    "expected_range": pred.get("expected_range", ""),
                    "timestamp": now_iso,
                    "severity": pred.get("severity", "WARNING"),
                    "recommended_action": pred.get("recommendation", "Inspect animal and provide veterinary care.")
                })

        payload = {
            "type": "sensor_update",
            "cattle_id": cattle_id,
            "data": data,
            "health": health_with_overall,
            "abnormal_events": abnormal_events,
            "validity": data.get("validity", {}),
            "validity_reasons": data.get("validity_reasons", {}),
            "source": data.get("source", "serial"),
            "is_simulated": data.get("is_simulated", False),
        }
        import logging as _log
        _logger = _log.getLogger("alert_service")
        _logger.info(f"[HOP 5: WS BROADCAST] cattle_id={cattle_id} clients={len(manager.active_connections)} overall={overall} abnormal_count={len(abnormal_events)}")
        _logger.info(
            f"[WS OUT] cattle={cattle_id} ldr={data.get('ldr')} "
            f"spo2={data.get('spo2')} bpm={data.get('bpm')} "
            f"temp={data.get('temperature')} weight={data.get('body_weight_kg')} overall={overall}"
        )
        try:
            ws_event = manager.format_event(
                event_type="sensor_update",
                payload=payload,
                cattle_id=cattle_id
            )
            await manager.broadcast(ws_event)

            # Also broadcast individual dedicated alert events if new abnormalities exist
            for evt in abnormal_events:
                try:
                    alert_event = manager.format_event(
                        event_type="alert",
                        payload=evt,
                        cattle_id=cattle_id
                    )
                    await manager.broadcast(alert_event)
                except Exception as _e:
                    _logger.warning(f"Failed to broadcast dedicated alert event: {_e}")
        except Exception as e:
            print(f"Failed to broadcast alert: {e}")

    @staticmethod
    def create_db_alert(cattle_id: str, alert_type: str, parameter: str, value: str, severity: str = "Critical"):
        # Legacy compat
        pass

    @staticmethod
    def create_db_alert_from_payload(payload: dict):
        db = SessionLocal()
        try:
            # Convert value to float if possible
            val = payload.get("value")
            if val is not None:
                try:
                    val = float(val)
                except ValueError:
                    val = None
                    
            thresh = payload.get("threshold")
            thresh_str = str(thresh) if thresh is not None else None
            alert = models.Alert(
                cattle_id=payload.get("cattle_id"),
                device_id=payload.get("device_id"),
                type=payload.get("type"),
                severity=payload.get("severity"),
                message=payload.get("message"),
                value=val,
                threshold=thresh_str,
                confidence=payload.get("confidence"),
                status=str(payload.get("status", "active")).lower()
            )
            db.add(alert)
            db.commit()
            
            if payload.get("severity") == "CRITICAL":
                from emergency_system import EmergencySystem
                EmergencySystem.process_critical_event(
                    db,
                    cattle_id=payload.get("cattle_id"),
                    alert_type=payload.get("type"),
                    severity=payload.get("severity"),
                    message=payload.get("message")
                )
        except Exception as e:
            db.rollback()
            print(f"Failed to save alert to DB: {e}")
        finally:
            db.close()

class ValidationService:
    _last_sequence = {}

    @classmethod
    def validate(cls, data: dict) -> dict:
        device_id = data.get("device_id", "SC-001")
        seq = data.get("sequence", 0)
        
        last_seq = cls._last_sequence.get(device_id, -1)
        if seq <= last_seq and seq != 0:
            if last_seq - seq <= 1000 and seq >= 10:
                raise ValueError(f"Stale sequence {seq}, expected > {last_seq}")
        cls._last_sequence[device_id] = seq

        # Preserve incoming cattle_id, fallback to device_id
        if not data.get("cattle_id"):
            data["cattle_id"] = device_id

        # Bidirectionally sync canonical keys and legacy aliases
        # Heart rate: canonical "heart_rate", aliases "bpm", "heart_rate_bpm"
        hr = data.get("heart_rate")
        if hr is None:
            hr = data.get("bpm") if data.get("bpm") is not None else data.get("heart_rate_bpm")
        if hr is not None:
            data["heart_rate"] = hr
            data["bpm"] = hr
            data["heart_rate_bpm"] = hr

        # SpO2: canonical "spo2", alias "spo2_percent"
        spo2 = data.get("spo2") if data.get("spo2") is not None else data.get("spo2_percent")
        if spo2 is not None:
            data["spo2"] = spo2
            data["spo2_percent"] = spo2

        # Temperature: canonical "temperature", alias "temperature_c"
        temp = data.get("temperature") if data.get("temperature") is not None else data.get("temperature_c")
        if temp is not None:
            data["temperature"] = temp
            data["temperature_c"] = temp

        # pH: canonical "ph", alias "ph_value"
        ph = data.get("ph") if data.get("ph") is not None else data.get("ph_value")
        if ph is not None:
            data["ph"] = ph
            data["ph_value"] = ph

        # LDR: canonical "ldr", alias "ldr_value"
        ldr = data.get("ldr") if data.get("ldr") is not None else data.get("ldr_value")
        if ldr is not None:
            data["ldr"] = ldr
            data["ldr_value"] = ldr

        # Accelerometer / MEMS
        if "mems_x" not in data and "accel_x" in data:
            data["mems_x"] = data["accel_x"]
        if "accel_x" not in data and "mems_x" in data:
            data["accel_x"] = data["mems_x"]
        if "mems_y" not in data and "accel_y" in data:
            data["mems_y"] = data["accel_y"]
        if "accel_y" not in data and "mems_y" in data:
            data["accel_y"] = data["mems_y"]
        if "mems_z" not in data and "accel_z" in data:
            data["mems_z"] = data["accel_z"]
        if "accel_z" not in data and "mems_z" in data:
            data["accel_z"] = data["mems_z"]

        # Sync step counts
        if "step_count_today" not in data and "steps" in data:
            data["step_count_today"] = data["steps"]
        if "steps" not in data and "step_count_today" in data:
            data["steps"] = data["step_count_today"]

        # Weight / Load Cell reconciliation
        w = data.get("body_weight_kg")
        if w is None:
            w = data.get("weight_kg") if data.get("weight_kg") is not None else data.get("weight")
        if w is not None:
            try:
                w_float = float(w)
                if 0.0 <= w_float <= 1500.0:
                    data["body_weight_kg"] = round(w_float, 2)
                    data["weight_kg"] = round(w_float, 2)
                    data["weight_valid"] = bool(data.get("weight_valid", True))
                else:
                    data["body_weight_kg"] = None
                    data["weight_kg"] = None
                    data["weight_valid"] = False
            except (ValueError, TypeError):
                data["body_weight_kg"] = None
                data["weight_kg"] = None
                data["weight_valid"] = False
        else:
            data["body_weight_kg"] = None
            data["weight_kg"] = None
            data["weight_valid"] = False

        data["weight_stable"] = bool(data.get("weight_stable", False))
        data["weight_calibrated"] = bool(data.get("weight_calibrated", False))
        if not data.get("weight_sensor_status"):
            if not data.get("weight_valid"):
                data["weight_sensor_status"] = "disconnected"
            elif not data.get("weight_calibrated"):
                data["weight_sensor_status"] = "uncalibrated"
            elif not data.get("weight_stable"):
                data["weight_sensor_status"] = "unstable"
            else:
                data["weight_sensor_status"] = "stable"

        return data

class PredictionService:
    def __init__(self):
        self.predictor = HealthPredictor()

    def predict(self, data: dict) -> dict:
        preds = evaluate_telemetry_health(data)
        try:
            ml_preds = self.predictor.predict(data)
            if isinstance(ml_preds, dict):
                for k, v in ml_preds.items():
                    if k != "overall" and isinstance(v, dict):
                        if k not in preds:
                            preds[k] = v
                        else:
                            preds[k].update(v)
                if "overall" in ml_preds and isinstance(ml_preds["overall"], dict):
                    if ml_preds["overall"].get("status") == "abnormal":
                        preds["overall"]["status"] = "abnormal"
        except Exception:
            pass
        return preds

class SensorService:
    def __init__(self, prediction_service: PredictionService):
        self.prediction_service = prediction_service

    async def process_reading(self, data: dict, manager):
        validated_data = ValidationService.validate(data)
        import logging
        logger = logging.getLogger("sensor_service")
        
        # Fall detection processing
        cattle_id = validated_data.get('cattle_id')
        mems_x = validated_data.get('mems_x')
        mems_y = validated_data.get('mems_y')
        mems_z = validated_data.get('mems_z')
        timestamp = validated_data.get('timestamp', 0)
        
        fall_info = fall_detector.process(cattle_id, mems_x, mems_y, mems_z, timestamp)
        validated_data.update(fall_info)
        cattle_id = str(cattle_id or 'CATTLE-001').strip().upper()
        validated_data['cattle_id'] = cattle_id
        logger.info(f"[HOP 3: INGEST START] cattle_id={cattle_id} hr={validated_data.get('heart_rate')} spo2={validated_data.get('spo2')} temp={validated_data.get('temperature')} weight={validated_data.get('body_weight_kg')}")
        
        logger.info(
            f"[SENSOR IN] cattle={validated_data.get('cattle_id')} "
            f"steps_today={validated_data.get('step_count_today')} lifetime={validated_data.get('step_count_lifetime')} "
            f"heart_rate={validated_data.get('heart_rate')} spo2={validated_data.get('spo2')} "
            f"temp={validated_data.get('temperature')} ph={validated_data.get('ph')} "
            f"ldr={validated_data.get('ldr')} weight={validated_data.get('body_weight_kg')} "
            f"mems=({validated_data.get('mems_x')},{validated_data.get('mems_y')},{validated_data.get('mems_z')}) "
            f"fall_detected={validated_data.get('fall_detected')}"
        )

        # Check if cattle is registered and active (auto-register on fresh deployments, ignore deleted)
        _db = SessionLocal()
        try:
            existing = _db.query(models.Cattle).filter(
                models.Cattle.cattle_id == cattle_id
            ).first()
            if not existing:
                logger.info(f"[SENSOR SVC] Auto-registering new incoming cattle profile: {cattle_id}")
                existing = models.Cattle(
                    cattle_id=cattle_id,
                    name=cattle_id,
                    status="normal",
                    deleted=False
                )
                _db.add(existing)
                _db.commit()
                _db.refresh(existing)
            elif existing.deleted:
                logger.warning(f"[SENSOR SVC] Dropping packet for deleted cattle: {cattle_id}")
                return {"status": "dropped", "reason": "deleted_cattle"}

            # Authoritative Step Management (Safeguard 11 & Safeguard 4)
            # Only count steps if accelerometer_valid is True
            accel_valid = validated_data.get("accelerometer_valid", True)
            incoming_lifetime = validated_data.get("step_count_lifetime") if accel_valid else None
            boot_count = validated_data.get("boot_count")
            auth_today, auth_life = AuthoritativeStepManager.process_step_reading(
                db=_db,
                cattle_id=cattle_id,
                incoming_lifetime=incoming_lifetime,
                boot_count=boot_count
            )
            validated_data["step_count_today"] = auth_today
            validated_data["step_count_lifetime"] = auth_life

            # Persist steps to Activity table for today in configured timezone
            local_now = AuthoritativeStepManager.get_local_now()
            today_start = datetime.combine(local_now.date(), datetime.min.time())
            today_act = _db.query(models.Activity).filter(
                models.Activity.cattle_id == cattle_id,
                models.Activity.date >= today_start
            ).first()
            if today_act:
                today_act.steps = int(auth_today)
            else:
                new_act = models.Activity(
                    cattle_id=cattle_id,
                    date=datetime.now(timezone.utc),
                    steps=int(auth_today)
                )
                _db.add(new_act)

            # Persist steps to cattle_step_readings table
            if accel_valid and incoming_lifetime is not None:
                last_step = _db.query(models.CattleStepReading).filter(
                    models.CattleStepReading.cattle_id == cattle_id
                ).order_by(models.CattleStepReading.measured_at.desc()).first()

                prev_tot = last_step.total_steps if last_step else 0
                step_inc = max(0, auth_life - prev_tot)

                now_utc = datetime.now(timezone.utc)
                should_save_step = False
                if not last_step:
                    should_save_step = True
                elif step_inc > 0:
                    should_save_step = True
                else:
                    m_time = last_step.measured_at if last_step.measured_at.tzinfo else last_step.measured_at.replace(tzinfo=timezone.utc)
                    if (now_utc - m_time).total_seconds() >= 60:
                        should_save_step = True

                if should_save_step:
                    new_step_rec = models.CattleStepReading(
                        cattle_id=cattle_id,
                        device_id=validated_data.get("device_id"),
                        steps_today=int(auth_today),
                        total_steps=int(auth_life),
                        step_increment=step_inc,
                        step_sensor_status="active",
                        step_algorithm_version="1.0",
                        measured_at=now_utc,
                        is_simulated=validated_data.get("is_simulated", False)
                    )
                    _db.add(new_step_rec)

            # Persist confirmed weight to Cattle profile and CattleWeightReading table
            w_valid = validated_data.get("weight_valid", False)
            w_stable = validated_data.get("weight_stable", False)
            w_kg = validated_data.get("body_weight_kg")
            if w_valid and w_stable and w_kg is not None and w_kg >= 10.0:
                existing.weight = round(w_kg, 2)
                last_w = _db.query(models.CattleWeightReading).filter(
                    models.CattleWeightReading.cattle_id == cattle_id
                ).order_by(models.CattleWeightReading.measured_at.desc()).first()
                now_utc = datetime.now(timezone.utc)
                should_save = False
                if not last_w:
                    should_save = True
                else:
                    m_time = last_w.measured_at if last_w.measured_at.tzinfo else last_w.measured_at.replace(tzinfo=timezone.utc)
                    elapsed = (now_utc - m_time).total_seconds()
                    if elapsed >= 60 or abs(last_w.weight_kg - w_kg) >= 2.0:
                        should_save = True
                if should_save:
                    new_w_rec = models.CattleWeightReading(
                        cattle_id=cattle_id,
                        device_id=validated_data.get("device_id"),
                        weight_kg=round(w_kg, 2),
                        raw_value=validated_data.get("weight_raw"),
                        is_stable=w_stable,
                        is_calibrated=validated_data.get("weight_calibrated", True),
                        measured_at=now_utc,
                        source=validated_data.get("source", "serial"),
                        is_simulated=validated_data.get("is_simulated", False)
                    )
                    _db.add(new_w_rec)

            _db.commit()
        except Exception as _e:
            _db.rollback()
            logger.warning(f"[SENSOR SVC] DB error updating cattle/activity {cattle_id}: {_e}")
        finally:
            _db.close()
        
        reading_id = DatabaseService.save_reading(validated_data)
        logger.info(f"[HOP 4: DB WRITE OK] cattle_id={cattle_id} reading_id={reading_id}")
        preds = self.prediction_service.predict(validated_data)
        
        # Override mems status if fall is detected
        if validated_data.get('fall_detected'):
            if "mems" not in preds:
                preds["mems"] = {}
            preds["mems"]["status"] = "abnormal"
            preds["mems"]["reason"] = f"Fall Detected! Confidence: {validated_data.get('fall_confidence')}"
            
            # Save critical alert
            fall_payload = await EngineAlertService.process_alert_async(
                cattle_id=cattle_id,
                device_id=validated_data.get("device_id", "UNKNOWN"),
                alert_type=AlertType.FALL,
                severity=AlertSeverity.CRITICAL,
                message="Fall detected",
                value=validated_data.get("peak_acceleration"),
                confidence=validated_data.get('fall_confidence'),
                websocket_manager=manager
            )
            if fall_payload:
                AlertService.create_db_alert_from_payload(fall_payload)
            
            # Store fall event separately
            db = SessionLocal()
            try:
                fall_event = models.FallEvent(
                    cattle_id=cattle_id,
                    peak_acceleration=validated_data.get("peak_acceleration"),
                    activity_score=validated_data.get("activity_score"),
                    fall_confidence=validated_data.get("fall_confidence")
                )
                db.add(fall_event)
                db.commit()
            except Exception as e:
                db.rollback()
                logger.error(f"Failed to store fall event: {e}")
            finally:
                db.close()
            
        DatabaseService.save_prediction(reading_id, preds)
        
        # Engine evaluation
        ml_pred_for_engine = {"overall": {"status": "abnormal" if any(v.get('status') == 'abnormal' for v in preds.values()) else "normal"}}
        
        # Calculate baselines
        db = SessionLocal()
        try:
            cow_baseline = BaselineEngine.calculate_baselines(db, cattle_id, window="24_hours")
        except Exception as e:
            logger.error(f"Failed to calculate baselines: {e}")
            cow_baseline = None
        finally:
            db.close()
        
        assessment = HealthRiskEngine.evaluate(validated_data, ml_pred_for_engine, cow_baseline=cow_baseline)
        DatabaseService.save_health_assessment(cattle_id, assessment)
        
        # Generate HEALTH_RISK alert if needed
        risk_level = assessment.get("risk_level")
        if risk_level in ["WARNING", "CRITICAL"]:
            severity = AlertSeverity.CRITICAL if risk_level == "CRITICAL" else AlertSeverity.WARNING
            risk_payload = await EngineAlertService.process_alert_async(
                cattle_id=cattle_id,
                device_id=validated_data.get("device_id", "UNKNOWN"),
                alert_type=AlertType.HEALTH_RISK,
                severity=severity,
                message=f"Health Risk Level: {risk_level}",
                value=float(assessment.get("health_score", 0)),
                confidence=assessment.get("confidence", 0.0),
                websocket_manager=manager
            )
            if risk_payload:
                AlertService.create_db_alert_from_payload(risk_payload)

        # Generate specific sensor alerts
        mapping = {
            "spo2": AlertType.SPO2,
            "heart_rate": AlertType.HEART_RATE,
            "temperature": AlertType.TEMPERATURE,
            "ph": AlertType.PH,
            "activity": AlertType.ACTIVITY
        }
        for sensor, pred in preds.items():
            if sensor in mapping:
                val = validated_data.get(sensor)
                if val is None:
                    continue
                if pred.get("status") == "abnormal":
                    severity = AlertSeverity.CRITICAL if pred.get("severity") == "CRITICAL" else AlertSeverity.WARNING
                    s_payload = await EngineAlertService.process_alert_async(
                        cattle_id=cattle_id,
                        device_id=validated_data.get("device_id", "UNKNOWN"),
                        alert_type=mapping[sensor],
                        severity=severity,
                        message=pred.get("reason", f"{sensor.upper()} is abnormal"),
                        value=val,
                        confidence=0.85,
                        threshold=pred.get("expected_range"),
                        websocket_manager=manager
                    )
                    if s_payload:
                        AlertService.create_db_alert_from_payload(s_payload)
                elif pred.get("status") == "normal":
                    # Sensor recovered to normal: resolve previous alert in engine and DB
                    EngineAlertService.resolve_alert(cattle_id, mapping[sensor])
                    db_r = SessionLocal()
                    try:
                        active_alerts = db_r.query(models.Alert).filter(
                            models.Alert.cattle_id == cattle_id,
                            models.Alert.type == mapping[sensor],
                            models.Alert.status == "active"
                        ).all()
                        for a in active_alerts:
                            a.status = "resolved"
                            a.resolved_at = datetime.utcnow()
                        if active_alerts:
                            db_r.commit()
                    except Exception:
                        db_r.rollback()
                    finally:
                        db_r.close()

        # Automatic SMS Alert Integration
        try:
            from sms_service import sms_service
            db_sms = SessionLocal()
            try:
                await sms_service.process_telemetry_for_sms(
                    cattle_id=cattle_id,
                    telemetry=validated_data,
                    predictions=preds,
                    db=db_sms
                )
            finally:
                db_sms.close()
        except Exception as sms_err:
            logger.error(f"[SMS Pipeline Error] Failed to process SMS alerts: {sms_err}")
        
        await AlertService.broadcast_alert(manager, validated_data["cattle_id"], validated_data, preds, assessment)
        logger.info(f"[SENSOR SAVED] reading_id={reading_id} overall={'abnormal' if any(v.get('status')=='abnormal' for v in preds.values()) else 'normal'} risk={assessment.get('risk_level')}")
        return {"reading_id": reading_id, "predictions": preds}
