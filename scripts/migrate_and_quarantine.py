import sqlite3
import shutil
import os
from datetime import datetime

DB_PATH = "backend/smart_cattle.db"
BACKUP_DIR = "backups"

os.makedirs(BACKUP_DIR, exist_ok=True)
timestamp_str = datetime.now().strftime("%Y%m%d_%H%M%S")
backup_path = os.path.join(BACKUP_DIR, f"smart_cattle_backup_{timestamp_str}.db")

print(f"1. Backing up {DB_PATH} -> {backup_path}")
shutil.copy2(DB_PATH, backup_path)
print(f"Backup verified. Size: {os.path.getsize(backup_path)} bytes")

conn = sqlite3.connect(DB_PATH)
cur = conn.cursor()

# 2. Check and add columns
cur.execute("PRAGMA table_info(sensor_readings)")
cols = {r[1]: r[2] for r in cur.fetchall()}

new_cols = [
    ("source", "VARCHAR DEFAULT 'serial'"),
    ("is_simulated", "BOOLEAN DEFAULT 0"),
    ("raw_packet", "VARCHAR NULL"),
    ("checksum_valid", "BOOLEAN DEFAULT 1")
]

for col_name, col_def in new_cols:
    if col_name not in cols:
        print(f"Adding column {col_name} ({col_def}) to sensor_readings")
        cur.execute(f"ALTER TABLE sensor_readings ADD COLUMN {col_name} {col_def}")

# 3. Create index on is_simulated
cur.execute("CREATE INDEX IF NOT EXISTS idx_sensor_readings_is_simulated ON sensor_readings(is_simulated)")
cur.execute("CREATE INDEX IF NOT EXISTS idx_sensor_readings_cattle_sim ON sensor_readings(cattle_id, is_simulated, timestamp)")

# 4. Fix historical 1970 timestamps using created_at
cur.execute("SELECT COUNT(*) FROM sensor_readings WHERE timestamp < '2000-01-01'")
count_1970 = cur.fetchone()[0]
print(f"Fixing {count_1970} historical readings where timestamp < year 2000...")
cur.execute("UPDATE sensor_readings SET timestamp = created_at WHERE timestamp < '2000-01-01' AND created_at IS NOT NULL")

# 5. Quarantine synthetic rows
cur.execute("""
    UPDATE sensor_readings 
    SET is_simulated = 1, source = 'simulator'
    WHERE cattle_id IN ('DEV-101', 'DEV-102', 'DEV-103', 'TEST-COW-99', 'TEST-COW-DARK-01')
""")
print(f"Flagged dev/test cow readings as simulated. Rows affected: {cur.rowcount}")

cur.execute("""
    UPDATE sensor_readings 
    SET is_simulated = 1, source = 'simulator'
    WHERE cattle_id = 'CATTLE-001' AND spo2 = 97 AND heart_rate = 76
""")
print(f"Flagged static synthetic test readings (spo2=97, hr=76) as simulated. Rows affected: {cur.rowcount}")

# 6. Ensure real rows are marked is_simulated = 0
cur.execute("UPDATE sensor_readings SET is_simulated = 0, source = 'serial' WHERE is_simulated IS NULL")

conn.commit()

# 7. Verification Summary
cur.execute("SELECT is_simulated, source, COUNT(*) FROM sensor_readings GROUP BY is_simulated, source")
print("\n=== QUARANTINE VERIFICATION SUMMARY ===")
for r in cur.fetchall():
    print(f"is_simulated={r[0]} | source={r[1]} | count={r[2]}")

cur.execute("""
    SELECT id, timestamp, heart_rate, spo2, temperature, ph, ldr, step_count_today, is_simulated, source 
    FROM sensor_readings 
    WHERE cattle_id = 'CATTLE-001' AND is_simulated = 0
    ORDER BY timestamp DESC LIMIT 5
""")
print("\nLatest 5 REAL hardware readings for CATTLE-001:")
for r in cur.fetchall():
    print(" ", r)

conn.close()
print("\nMigration and quarantine completed successfully.")
