import serial
import time

try:
    print("Opening COM3...")
    with serial.Serial('COM3', 9600, timeout=2) as ser:
        print("Port opened successfully.")
        # DTR/RTS reset
        ser.dtr = False
        ser.rts = False
        time.sleep(0.1)
        ser.dtr = True
        ser.rts = True
        
        print("Reading data...")
        start_time = time.time()
        while time.time() - start_time < 10:
            if ser.in_waiting > 0:
                data = ser.readline()
                print(f"Received: {data}")
            else:
                time.sleep(0.1)
        print("Done.")
except Exception as e:
    print(f"Error: {e}")
