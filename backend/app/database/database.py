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
                        ("step_count_today", "INTEGER"),
                        ("step_count_lifetime", "INTEGER"),
                        ("acceleration_x", "REAL"),
                        ("acceleration_y", "REAL"),
                        ("acceleration_z", "REAL"),
                        ("accelerometer_valid", "BOOLEAN DEFAULT 0"),
                        ("heart_rate_valid", "BOOLEAN DEFAULT 0"),
                        ("spo2_valid", "BOOLEAN DEFAULT 0"),
                        ("temperature_valid", "BOOLEAN DEFAULT 0"),
                        ("ph_valid", "BOOLEAN DEFAULT 0"),
                        ("ldr_valid", "BOOLEAN DEFAULT 0"),
                        ("device_connected", "BOOLEAN DEFAULT 1"),
                    ]
                    for col_name, col_type in cols_to_add:
                        if col_name not in existing_cols:
                            conn.execute(text(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_type}"))
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
