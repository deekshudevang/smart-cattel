import sqlite3
import glob
import os

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

def migrate():
    base_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    db_files = glob.glob(os.path.join(base_dir, "*.db"))
    for db_path in db_files:
        print(f"Checking {db_path}...")
        conn = sqlite3.connect(db_path)
        cursor = conn.cursor()
        cursor.execute("SELECT name FROM sqlite_master WHERE type='table' AND name='sensor_readings'")
        if cursor.fetchone():
            existing = [r[1] for r in cursor.execute("PRAGMA table_info(sensor_readings)").fetchall()]
            for col_name, col_type in cols_to_add:
                if col_name not in existing:
                    print(f"  Adding {col_name} {col_type} to {os.path.basename(db_path)}")
                    cursor.execute(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_type}")
            conn.commit()
        conn.close()
    print("Database migration completed!")

if __name__ == "__main__":
    migrate()
