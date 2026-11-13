import serial
import time

try:
    print("Opening COM10...")
    with serial.Serial('COM10', 9600, timeout=2, dsrdtr=True) as ser:
        print("Port opened successfully.")
        
        # Reset standard for Arduino
        ser.setDTR(False)
        time.sleep(1)
        ser.setDTR(True)
        time.sleep(2) # Give Mega time to boot

        print("Reading data...")
        start_time = time.time()
        while time.time() - start_time < 15:
            if ser.in_waiting > 0:
                data = ser.readline()
                print(f"Received: {data}")
            else:
                time.sleep(0.1)
        print("Done.")
except Exception as e:
    print(f"Error: {e}")
