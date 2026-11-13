from sqlalchemy import create_engine
from sqlalchemy.orm import declarative_base, sessionmaker

from app.config import settings

engine = create_engine(
    settings.DATABASE_URL, connect_args={"check_same_thread": False} if "sqlite" in settings.DATABASE_URL else {}
)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

from sqlalchemy import MetaData

convention = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s"
}
metadata = MetaData(naming_convention=convention)

Base = declarative_base(metadata=metadata)

def ensure_sqlite_columns():
    if "sqlite" in settings.DATABASE_URL:
        try:
            from sqlalchemy import text
            with engine.connect() as conn:
                res = conn.execute(text("SELECT name FROM sqlite_master WHERE type='table' AND name='sensor_readings'"))
                if res.fetchone():
                    table_info = conn.execute(text("PRAGMA table_info(sensor_readings)")).fetchall()
                    existing_cols = {row[1] for row in table_info}
                    cols_to_add = [
                        ("device_id", "TEXT"),
                        ("step_count_today", "INTEGER"),
                        ("step_count_lifetime", "INTEGER"),
                        ("acceleration_x", "REAL"),
                        ("acceleration_y", "REAL"),
                        ("acceleration_z", "REAL"),
                        ("acceleration_magnitude", "REAL"),
                        ("activity_state", "TEXT"),
                        ("activity_score", "REAL"),
                        ("fall_detected", "BOOLEAN DEFAULT 0"),
                        ("fall_confidence", "REAL"),
                        ("peak_acceleration", "REAL"),
                        ("accelerometer_valid", "BOOLEAN DEFAULT 0"),
                        ("heart_rate_valid", "BOOLEAN DEFAULT 0"),
                        ("spo2_valid", "BOOLEAN DEFAULT 0"),
                        ("temperature_valid", "BOOLEAN DEFAULT 0"),
                        ("ph_valid", "BOOLEAN DEFAULT 0"),
                        ("ldr_valid", "BOOLEAN DEFAULT 0"),
                        ("device_connected", "BOOLEAN DEFAULT 1"),
                        ("gps_valid", "BOOLEAN DEFAULT 0"),
                        ("gps_lat", "REAL"),
                        ("gps_lon", "REAL"),
                        ("gps_satellites", "INTEGER"),
                        ("gsm_status", "TEXT"),
                        ("source", "TEXT DEFAULT 'serial'"),
                        ("is_simulated", "BOOLEAN DEFAULT 0"),
                        ("raw_packet", "TEXT"),
                        ("checksum_valid", "BOOLEAN DEFAULT 1"),
                        ("created_at", "DATETIME"),
                        ("updated_at", "DATETIME"),
                        ("deleted", "BOOLEAN DEFAULT 0"),
                    ]
                    for col_name, col_type in cols_to_add:
                        if col_name not in existing_cols:
                            conn.execute(text(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_type}"))

                    cattle_info = conn.execute(text("PRAGMA table_info(cattle)")).fetchall()
                    existing_cattle_cols = {row[1] for row in cattle_info}
                    cattle_cols_to_add = [
                        ("photo", "TEXT"),
                        ("weight", "REAL"),
                        ("tag_id", "TEXT"),
                        ("lactation_stage", "TEXT"),
                        ("pregnancy_status", "TEXT"),
                        ("medical_history", "TEXT"),
                        ("created_at", "DATETIME"),
                        ("updated_at", "DATETIME"),
                    ]
                    for col_name, col_type in cattle_cols_to_add:
                        if col_name not in existing_cattle_cols:
                            conn.execute(text(f"ALTER TABLE cattle ADD COLUMN {col_name} {col_type}"))
                    alerts_res = conn.execute(text("SELECT name FROM sqlite_master WHERE type='table' AND name='alerts'"))
                    if alerts_res.fetchone():
                        alerts_info = conn.execute(text("PRAGMA table_info(alerts)")).fetchall()
                        existing_alert_cols = {row[1] for row in alerts_info}
                        alert_cols_to_add = [
                            ("device_id", "TEXT"),
                            ("type", "TEXT"),
                            ("message", "TEXT"),
                            ("value", "REAL"),
                            ("threshold", "REAL"),
                            ("confidence", "REAL"),
                            ("acknowledged_at", "DATETIME"),
                            ("resolved_at", "DATETIME"),
                        ]
                        for col_name, col_type in alert_cols_to_add:
                            if col_name not in existing_alert_cols:
                                conn.execute(text(f"ALTER TABLE alerts ADD COLUMN {col_name} {col_type}"))
                        if "alert_type" in existing_alert_cols and "type" in existing_alert_cols:
                            conn.execute(text("UPDATE alerts SET type = alert_type WHERE type IS NULL AND alert_type IS NOT NULL"))

                    sms_logs_res = conn.execute(text("SELECT name FROM sqlite_master WHERE type='table' AND name='sms_logs'"))
                    if sms_logs_res.fetchone():
                        sms_logs_info = conn.execute(text("PRAGMA table_info(sms_logs)")).fetchall()
                        existing_sms_cols = {row[1] for row in sms_logs_info}
                        sms_cols_to_add = [
                            ("phone_number", "TEXT"),
                            ("message", "TEXT"),
                            ("provider", "TEXT"),
                            ("event_id", "TEXT"),
                        ]
                        for col_name, col_type in sms_cols_to_add:
                            if col_name not in existing_sms_cols:
                                conn.execute(text(f"ALTER TABLE sms_logs ADD COLUMN {col_name} {col_type}"))

                    conn.commit()
        except Exception:
            pass

ensure_sqlite_columns()

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
