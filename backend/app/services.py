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
        payload = {
            "type": "sensor_update",
            "cattle_id": cattle_id,
            "data": data,
            "health": health_with_overall,
            "source": data.get("source", "serial"),
            "is_simulated": data.get("is_simulated", False),
        }
        import logging as _log
        _logger = _log.getLogger("alert_service")
        _logger.info(
            f"[WS OUT] cattle={cattle_id} ldr={data.get('ldr')} "
            f"spo2={data.get('spo2')} bpm={data.get('bpm')} "
            f"temp={data.get('temperature')} overall={overall}"
        )
        try:
            ws_event = manager.format_event(
                event_type="sensor_update",
                payload=payload,
                cattle_id=cattle_id
            )
            await manager.broadcast(ws_event)
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
                    
            alert = models.Alert(
                cattle_id=payload.get("cattle_id"),
                device_id=payload.get("device_id"),
                type=payload.get("type"),
                severity=payload.get("severity"),
                message=payload.get("message"),
                value=val,
                threshold=payload.get("threshold"),
                confidence=payload.get("confidence"),
                status=payload.get("status", AlertLifecycle.ACTIVE)
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

        # Sync heart_rate / bpm
        if "bpm" in data and "heart_rate" not in data:
            data["heart_rate"] = data["bpm"]
        elif "heart_rate_bpm" in data and "heart_rate" not in data:
            data["heart_rate"] = data["heart_rate_bpm"]
        elif "heart_rate" in data and "bpm" not in data:
            data["bpm"] = data["heart_rate"]

        # Sync step counts
        if "step_count_today" not in data and "steps" in data:
            data["step_count_today"] = data["steps"]

        return data

class PredictionService:
    def __init__(self):
        self.predictor = HealthPredictor()

    def predict(self, data: dict) -> dict:
        return self.predictor.predict(data)

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
        
        logger.info(
            f"[SENSOR IN] cattle={validated_data.get('cattle_id')} "
            f"steps_today={validated_data.get('step_count_today')} lifetime={validated_data.get('step_count_lifetime')} "
            f"heart_rate={validated_data.get('heart_rate')} spo2={validated_data.get('spo2')} "
            f"temp={validated_data.get('temperature')} ph={validated_data.get('ph')} "
            f"ldr={validated_data.get('ldr')} "
            f"mems=({validated_data.get('mems_x')},{validated_data.get('mems_y')},{validated_data.get('mems_z')}) "
            f"fall_detected={validated_data.get('fall_detected')}"
        )

        # Check if cattle is registered and active (ignore unknown or deleted)
        _db = SessionLocal()
        try:
            existing = _db.query(models.Cattle).filter(
                models.Cattle.cattle_id == cattle_id
            ).first()
            if not existing:
                logger.warning(f"[SENSOR SVC] Dropping packet for unknown cattle: {cattle_id}")
                return {"status": "dropped", "reason": "unknown_cattle"}
            if existing.deleted:
                logger.warning(f"[SENSOR SVC] Dropping packet for deleted cattle: {cattle_id}")
                return {"status": "dropped", "reason": "deleted_cattle"}

            # Authoritative Step Management (Safeguard 11 & Safeguard 4)
            incoming_lifetime = validated_data.get("step_count_lifetime")
            boot_count = validated_data.get("boot_count")
            auth_today, auth_life = AuthoritativeStepManager.process_step_reading(
                db=_db,
                cattle_id=cattle_id,
                incoming_lifetime=incoming_lifetime,
                boot_count=boot_count
            )
            validated_data["step_count_today"] = auth_today
            if incoming_lifetime is not None:
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
            _db.commit()
        except Exception as _e:
            _db.rollback()
            logger.warning(f"[SENSOR SVC] DB error updating cattle/activity {cattle_id}: {_e}")
        finally:
            _db.close()
        
        reading_id = DatabaseService.save_reading(validated_data)
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
            if pred.get("status") == "abnormal" and sensor in mapping:
                val = validated_data.get(sensor)
                if val is None:
                    continue
                s_payload = await EngineAlertService.process_alert_async(
                    cattle_id=cattle_id,
                    device_id=validated_data.get("device_id", "UNKNOWN"),
                    alert_type=mapping[sensor],
                    severity=AlertSeverity.WARNING,
                    message=f"{sensor.upper()} is abnormal",
                    value=val,
                    confidence=0.8,
                    websocket_manager=manager
                )
                if s_payload:
                    AlertService.create_db_alert_from_payload(s_payload)
        
        await AlertService.broadcast_alert(manager, validated_data["cattle_id"], validated_data, preds, assessment)
        logger.info(f"[SENSOR SAVED] reading_id={reading_id} overall={'abnormal' if any(v.get('status')=='abnormal' for v in preds.values()) else 'normal'} risk={assessment.get('risk_level')}")
        return {"reading_id": reading_id, "predictions": preds}
