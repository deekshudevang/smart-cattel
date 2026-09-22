import serial
import time

print("Opening COM3...")
try:
    ser = serial.Serial('COM3', 9600, timeout=1)
    print("Opened successfully.")
    for i in range(10):
        line = ser.readline()
        if line:
            print("Received:", line.decode(errors='ignore').strip())
        else:
            print("No data...")
    ser.close()
except Exception as e:
    print("Error:", e)
