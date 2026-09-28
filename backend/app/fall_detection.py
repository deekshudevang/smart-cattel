import math

class FallDetector:
    def __init__(self):
        # Store history per device_id
        self.history = {}

    def process(self, device_id: str, x: float, y: float, z: float, timestamp: int) -> dict:
        if x is None or y is None or z is None:
            return {
                "acceleration_magnitude": None,
                "activity_state": "unknown",
                "activity_score": 0.0,
                "fall_detected": False,
                "fall_confidence": 0.0,
                "peak_acceleration": 0.0
            }
            
        # 1. Acceleration magnitude
        mag = math.sqrt(x**2 + y**2 + z**2)
        
        if device_id not in self.history:
            self.history[device_id] = []
            
        self.history[device_id].append({
            "timestamp": timestamp,
            "mag": mag,
            "x": x, "y": y, "z": z
        })
        
        # Keep last 100 readings
        if len(self.history[device_id]) > 100:
            self.history[device_id].pop(0)
            
        history = self.history[device_id]
        
        # 2. Activity Level & Movement Detection
        recent = history[-10:]
        if len(recent) < 2:
            return {
                "acceleration_magnitude": mag,
                "activity_state": "normal activity",
                "activity_score": 0.0,
                "fall_detected": False,
                "fall_confidence": 0.0,
                "peak_acceleration": mag
            }
            
        mags = [r["mag"] for r in recent]
        avg_mag = sum(mags) / len(mags)
        variance = sum((m - avg_mag)**2 for m in mags) / len(mags)
        
        activity_score = min(10.0, variance * 10)
        peak_accel = max(mags)
        
        if activity_score < 0.2:
            activity_state = "low activity"
        elif activity_score < 2.5:
            activity_state = "normal activity"
        else:
            activity_state = "high activity"
            
        # 3. Fall sequence detection
        # Need at least 30 samples to detect a full sequence (pre-impact, impact, post-impact)
        fall_detected = False
        fall_confidence = 0.0
        
        if len(history) >= 30:
            # Detect impact (sudden acceleration)
            # Find the peak in the middle window to allow for pre and post analysis
            middle_window = history[-25:-5]
            
            impact_idx = -1
            impact_mag = 0
            for i, r in enumerate(middle_window):
                if r["mag"] > 2.5 and r["mag"] > impact_mag:
                    impact_idx = len(history) - 25 + i
                    impact_mag = r["mag"]
                    
            if impact_idx != -1:
                peak_accel = max(peak_accel, impact_mag)
                
                # Check pre-impact (normal/high activity)
                pre_impact = history[max(0, impact_idx-10):impact_idx]
                
                # Check post-impact inactivity
                post_impact = history[impact_idx+1:impact_idx+6]
                
                if len(pre_impact) >= 2 and len(post_impact) >= 5:
                    post_mags = [r["mag"] for r in post_impact]
                    post_avg = sum(post_mags) / len(post_mags)
                    post_var = sum((m - post_avg)**2 for m in post_mags) / len(post_mags)
                    
                    if post_var < 0.15: # post-impact inactivity
                        # Orientation change check
                        avg_z_pre = sum(r["z"] for r in pre_impact) / len(pre_impact)
                        avg_z_post = sum(r["z"] for r in post_impact) / len(post_impact)
                        
                        z_diff = abs(avg_z_pre - avg_z_post)
                        if z_diff > 0.4: # orientation change
                            fall_detected = True
                            fall_confidence = min(1.0, 0.4 + (z_diff * 0.2) + (impact_mag * 0.1) + (0.1 / (post_var + 0.01)))

        return {
            "acceleration_magnitude": mag,
            "activity_state": activity_state,
            "activity_score": round(activity_score, 2),
            "fall_detected": fall_detected,
            "fall_confidence": round(fall_confidence, 2),
            "peak_acceleration": round(peak_accel, 2)
        }

fall_detector = FallDetector()
