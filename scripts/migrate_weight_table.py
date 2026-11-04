import sqlite3
import os
import shutil
from datetime import datetime

DB_PATH = "backend/smart_cattle.db"
BACKUP_DIR = "backups"

os.makedirs(BACKUP_DIR, exist_ok=True)
timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
backup_path = os.path.join(BACKUP_DIR, f"smart_cattle_backup_pre_weight_{timestamp}.db")
if os.path.exists(DB_PATH):
    shutil.copy2(DB_PATH, backup_path)
    print(f"Backed up database to {backup_path}")

conn = sqlite3.connect(DB_PATH)
cursor = conn.cursor()

# 1. Create cattle_weight_readings table
cursor.execute("""
CREATE TABLE IF NOT EXISTS cattle_weight_readings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    cattle_id VARCHAR NOT NULL,
    device_id VARCHAR,
    weight_kg FLOAT NOT NULL,
    raw_value INTEGER,
    is_stable BOOLEAN DEFAULT 1,
    is_calibrated BOOLEAN DEFAULT 1,
    measured_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted BOOLEAN DEFAULT 0,
    source VARCHAR DEFAULT 'serial',
    is_simulated BOOLEAN DEFAULT 0,
    FOREIGN KEY(cattle_id) REFERENCES cattle(cattle_id),
    FOREIGN KEY(device_id) REFERENCES devices(device_id)
);
""")

cursor.execute("CREATE INDEX IF NOT EXISTS idx_weight_cattle_id ON cattle_weight_readings (cattle_id);")
cursor.execute("CREATE INDEX IF NOT EXISTS idx_weight_measured_at ON cattle_weight_readings (measured_at);")
cursor.execute("CREATE INDEX IF NOT EXISTS idx_weight_cattle_measured ON cattle_weight_readings (cattle_id, measured_at);")

# 2. Add weight columns to sensor_readings if missing
cursor.execute("PRAGMA table_info(sensor_readings);")
existing_cols = [col[1] for col in cursor.fetchall()]

new_cols = [
    ("body_weight_kg", "FLOAT"),
    ("weight_raw", "INTEGER"),
    ("weight_stable", "BOOLEAN DEFAULT 0"),
    ("weight_calibrated", "BOOLEAN DEFAULT 0"),
    ("weight_valid", "BOOLEAN DEFAULT 0"),
    ("weight_sensor_status", "VARCHAR")
]

for col_name, col_type in new_cols:
    if col_name not in existing_cols:
        cursor.execute(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_type};")
        print(f"Added column {col_name} to sensor_readings.")

conn.commit()

# Also check root db smart_cattle.db if it exists
ROOT_DB = "smart_cattle.db"
if os.path.exists(ROOT_DB) and os.path.abspath(ROOT_DB) != os.path.abspath(DB_PATH):
    root_conn = sqlite3.connect(ROOT_DB)
    root_cur = root_conn.cursor()
    root_cur.execute("""
    CREATE TABLE IF NOT EXISTS cattle_weight_readings (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        cattle_id VARCHAR NOT NULL,
        device_id VARCHAR,
        weight_kg FLOAT NOT NULL,
        raw_value INTEGER,
        is_stable BOOLEAN DEFAULT 1,
        is_calibrated BOOLEAN DEFAULT 1,
        measured_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
        deleted BOOLEAN DEFAULT 0,
        source VARCHAR DEFAULT 'serial',
        is_simulated BOOLEAN DEFAULT 0
    );
    """)
    root_cur.execute("CREATE INDEX IF NOT EXISTS idx_weight_cattle_id ON cattle_weight_readings (cattle_id);")
    root_cur.execute("CREATE INDEX IF NOT EXISTS idx_weight_measured_at ON cattle_weight_readings (measured_at);")
    root_cur.execute("CREATE INDEX IF NOT EXISTS idx_weight_cattle_measured ON cattle_weight_readings (cattle_id, measured_at);")
    root_cur.execute("PRAGMA table_info(sensor_readings);")
    r_cols = [c[1] for c in root_cur.fetchall()]
    for col_name, col_type in new_cols:
        if col_name not in r_cols:
            root_cur.execute(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_type};")
    root_conn.commit()
    root_conn.close()
    print("Migrated root smart_cattle.db as well.")

conn.close()
print("Migration completed successfully.")

