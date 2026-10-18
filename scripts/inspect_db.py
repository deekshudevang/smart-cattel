import sqlite3

conn = sqlite3.connect("smart_cattle.db")
cur = conn.cursor()
cur.execute("SELECT id, timestamp, created_at, ldr, step_count_today FROM sensor_readings WHERE cattle_id='CATTLE-001' ORDER BY id DESC LIMIT 5")
for r in cur.fetchall():
    print(r)
conn.close()
