"""
Smart Cattle Health Monitoring — Centralized Thresholds & Clinical Evaluator.
Authoritative source of physiological reference ranges, anomaly detection,
and recommended veterinary clinical actions for cattle.
"""

from typing import Dict, Any, Optional, List, Tuple
from datetime import datetime, timezone
import uuid

# Physiological Reference Ranges for Adult Cattle (Bovine)
CATTLE_THRESHOLDS: Dict[str, Dict[str, Any]] = {
    "heart_rate": {
        "sensor_name": "Heart Rate",
        "unit": "BPM",
        "min_normal": 48.0,
        "max_normal": 84.0,
        "min_critical": 40.0,
        "max_critical": 105.0,
        "expected_range": "48 - 84 BPM",
        "warning_low_msg": "Heart Rate below normal resting range",
        "warning_high_msg": "Heart Rate elevated above resting range",
        "critical_low_msg": "Severe Bradycardia (Heart rate critically low)",
        "critical_high_msg": "Severe Tachycardia (Heart rate critically high)",
        "recommendation_high": "Inspect for acute heat stress, fever, dehydration, or physical pain. Move cow to shade, offer cool water, and check respiration.",
        "recommendation_low": "Inspect for hypothermia, severe depression, organ failure, or milk fever. Contact a veterinarian immediately."
    },
    "spo2": {
        "sensor_name": "Blood Oxygen (SpO2)",
        "unit": "%",
        "min_normal": 95.0,
        "max_normal": 100.0,
        "min_critical": 90.0,
        "max_critical": 100.0,
        "expected_range": "95% - 100%",
        "warning_low_msg": "Mild Hypoxemia (SpO2 below 95%)",
        "critical_low_msg": "Severe Hypoxemia (Critical respiratory distress)",
        "recommendation_low": "Check airway for obstruction, pneumonia, or pulmonary distress. Ensure barn ventilation is adequate and monitor breathing effort."
    },
    "temperature": {
        "sensor_name": "Body Temperature",
        "unit": "°C",
        "min_normal": 38.0,
        "max_normal": 39.3,
        "min_critical": 37.0,
        "max_critical": 40.5,
        "expected_range": "38.0°C - 39.3°C",
        "warning_low_msg": "Subnormal Body Temperature (< 38.0°C)",
        "warning_high_msg": "Elevated Body Temperature / Mild Fever (> 39.3°C)",
        "critical_low_msg": "Severe Hypothermia (< 37.0°C)",
        "critical_high_msg": "High Fever / Hyperthermia (> 40.5°C)",
        "recommendation_high": "High fever detected. Move cow to shaded/cool area, provide cool water, check for infection/mastitis, and administer antipyretics under veterinary direction.",
        "recommendation_low": "Severe hypothermia. Provide warm bedding, shelter from wind/rain, and administer warm electrolytes. Veterinary check required."
    },
    "ph": {
        "sensor_name": "Rumen / Saliva pH",
        "unit": "pH",
        "min_normal": 6.2,
        "max_normal": 7.2,
        "min_critical": 5.8,
        "max_critical": 7.8,
        "expected_range": "6.2 - 7.2 pH",
        "warning_low_msg": "Subacute Rumen Acidosis (SARA risk)",
        "warning_high_msg": "Elevated pH / Mild Alkalosis",
        "critical_low_msg": "Acute Rumen Acidosis (pH critically low)",
        "critical_high_msg": "Severe Alkalosis (pH critically high)",
        "recommendation_low": "Reduce concentrate/grain intake immediately. Provide good quality long hay and buffer (sodium bicarbonate). Monitor rumen contractions.",
        "recommendation_high": "Inspect feed for urea or excess protein content. Ensure access to fresh balanced roughage."
    },
    "mems": {
        "sensor_name": "Posture & Activity (MEMS)",
        "unit": "m/s²",
        "expected_range": "Normal Standing/Walking (0.8 - 1.2 g)",
        "critical_fall_msg": "Fall Detected! Cow is down or unable to stand",
        "recommendation_fall": "Immediate on-site check required! Cow has collapsed. Check for milk fever, slip/fracture, or calving complications. Assist animal upright safely."
    }
}


def evaluate_sensor_value(sensor_key: str, value: Optional[float]) -> Optional[Dict[str, Any]]:
    """
    Evaluates a single numeric sensor reading against centralized cattle physiological thresholds.
    Returns None if value is None (MISSING/UNAVAILABLE values are NEVER treated as abnormal or zero).
    """
    if value is None:
        return None
        
    cfg = CATTLE_THRESHOLDS.get(sensor_key)
    if not cfg:
        return None
        
    val = float(value)
    
    # Check if within normal bounds
    min_norm = cfg.get("min_normal")
    max_norm = cfg.get("max_normal")
    min_crit = cfg.get("min_critical", min_norm)
    max_crit = cfg.get("max_critical", max_norm)
    unit = cfg.get("unit", "")
    expected = cfg.get("expected_range", f"{min_norm} - {max_norm} {unit}".strip())
    
    if min_norm is not None and max_norm is not None and min_norm <= val <= max_norm:
        return {
            "status": "normal",
            "severity": "NORMAL",
            "value": val,
            "formatted_value": f"{val:.1f} {unit}".strip(),
            "expected_range": expected,
            "direction": "NORMAL",
            "reason": f"{cfg['sensor_name']} is within healthy physiological range ({expected})",
            "recommendation": ""
        }
        
    # Check critical vs warning
    severity = "WARNING"
    direction = "NORMAL"
    reason = ""
    recommendation = ""
    
    if max_norm is not None and val > max_norm:
        direction = "HIGH"
        if max_crit is not None and val >= max_crit:
            severity = "CRITICAL"
            reason = cfg.get("critical_high_msg", f"{cfg['sensor_name']} is critically high ({val:.1f} {unit})")
        else:
            severity = "WARNING"
            reason = cfg.get("warning_high_msg", f"{cfg['sensor_name']} is elevated ({val:.1f} {unit})")
        recommendation = cfg.get("recommendation_high", "Inspect animal and provide supportive care.")
    elif min_norm is not None and val < min_norm:
        direction = "LOW"
        if min_crit is not None and val <= min_crit:
            severity = "CRITICAL"
            reason = cfg.get("critical_low_msg", f"{cfg['sensor_name']} is critically low ({val:.1f} {unit})")
        else:
            severity = "WARNING"
            reason = cfg.get("warning_low_msg", f"{cfg['sensor_name']} is below normal ({val:.1f} {unit})")
        recommendation = cfg.get("recommendation_low", "Inspect animal and provide supportive care.")
        
    return {
        "status": "abnormal",
        "severity": severity,
        "value": val,
        "formatted_value": f"{val:.1f} {unit}".strip(),
        "expected_range": expected,
        "direction": direction,
        "reason": reason,
        "recommendation": recommendation
    }


def evaluate_telemetry_health(data: Dict[str, Any]) -> Dict[str, Any]:
    """
    Evaluates complete telemetry dictionary across all available sensors.
    Preserves missing/invalid sensors as Not Available (never zero or fake abnormal).
    """
    preds: Dict[str, Any] = {}
    
    # 1. SpO2
    spo2_val = data.get("spo2") if data.get("spo2_valid", True) else None
    if spo2_val is not None and spo2_val > 0:
        pred = evaluate_sensor_value("spo2", float(spo2_val))
        if pred:
            preds["spo2"] = pred
            
    # 2. Heart Rate / BPM
    hr_val = data.get("heart_rate") or data.get("bpm") or data.get("heart_rate_bpm")
    if not (data.get("heart_rate_valid", True) or data.get("bpm_valid", True)):
        hr_val = None
    if hr_val is not None and float(hr_val) > 0:
        pred = evaluate_sensor_value("heart_rate", float(hr_val))
        if pred:
            preds["heart_rate"] = pred
            
    # 3. Temperature
    temp_val = data.get("temperature") or data.get("temperature_c")
    if not data.get("temperature_valid", True):
        temp_val = None
    if temp_val is not None:
        pred = evaluate_sensor_value("temperature", float(temp_val))
        if pred:
            preds["temperature"] = pred
            
    # 4. pH
    ph_val = data.get("ph") or data.get("ph_value")
    if not data.get("ph_valid", True):
        ph_val = None
    if ph_val is not None:
        pred = evaluate_sensor_value("ph", float(ph_val))
        if pred:
            preds["ph"] = pred
            
    # 5. MEMS / Fall Detection
    fall_detected = bool(data.get("fall_detected", False))
    mems_cfg = CATTLE_THRESHOLDS["mems"]
    if fall_detected:
        preds["mems"] = {
            "status": "abnormal",
            "severity": "CRITICAL",
            "value": data.get("peak_acceleration", 0.0),
            "formatted_value": f"{data.get('peak_acceleration', 0.0):.1f} m/s²",
            "expected_range": mems_cfg["expected_range"],
            "direction": "FALL DETECTED",
            "reason": mems_cfg["critical_fall_msg"],
            "recommendation": mems_cfg["recommendation_fall"]
        }
    else:
        # Check if accelerometer is valid
        if data.get("mems_valid", True) or data.get("accelerometer_valid", True):
            preds["mems"] = {
                "status": "normal",
                "severity": "NORMAL",
                "value": 9.8,
                "formatted_value": "Stable",
                "expected_range": mems_cfg["expected_range"],
                "direction": "NORMAL",
                "reason": "Posture and acceleration within normal physical bounds",
                "recommendation": ""
            }
            
    # 6. Overall Assessment
    abnormal_sensors = [k for k, v in preds.items() if v.get("status") == "abnormal"]
    has_critical = any(v.get("severity") == "CRITICAL" for v in preds.values())
    
    if abnormal_sensors:
        overall_status = "abnormal"
        overall_severity = "CRITICAL" if has_critical else "WARNING"
        reason = f"Abnormal condition detected in: {', '.join(abnormal_sensors)}"
    else:
        overall_status = "normal"
        overall_severity = "NORMAL"
        reason = "All physiological parameters within normal bounds"
        
    preds["overall"] = {
        "status": overall_status,
        "severity": overall_severity,
        "abnormal_sensors": abnormal_sensors,
        "reason": reason
    }
    
    return preds
