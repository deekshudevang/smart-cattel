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
                "fall_confidence": 0.0
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
        
        # Keep last 60 readings (approx 90 seconds)
        if len(self.history[device_id]) > 60:
            self.history[device_id].pop(0)
            
        # 2. Activity Level & Movement Detection
        recent = self.history[device_id][-10:]
        if len(recent) < 2:
            return {
                "acceleration_magnitude": mag,
                "activity_state": "idle",
                "activity_score": 0.0,
                "fall_detected": False,
                "fall_confidence": 0.0
            }
            
        mags = [r["mag"] for r in recent]
        avg_mag = sum(mags) / len(mags)
        variance = sum((m - avg_mag)**2 for m in mags) / len(mags)
        
        activity_score = min(10.0, variance * 10)
        
        if activity_score < 0.5:
            activity_state = "idle"
        elif activity_score < 3.0:
            activity_state = "walking"
        else:
            activity_state = "active"
            
        # 3. Sudden acceleration, 4. Orientation change, 5. Fall candidate, 6. Post-fall inactivity
        fall_detected = False
        fall_confidence = 0.0
        
        history = self.history[device_id]
        if len(history) >= 20:
            # Look for impact in the recent window
            impact_idx = -1
            for i, r in enumerate(history[-20:]):
                # Standard gravity is ~1g, sudden acceleration > 2.5g could be an impact
                if r["mag"] > 2.5:
                    impact_idx = i
                    break
                    
            if impact_idx != -1 and impact_idx < 15:
                # Post-fall inactivity check
                post_impact = history[-20:][impact_idx+1:]
                if len(post_impact) >= 5:
                    post_mags = [r["mag"] for r in post_impact]
                    post_avg = sum(post_mags) / len(post_mags)
                    post_var = sum((m - post_avg)**2 for m in post_mags) / len(post_mags)
                    
                    if post_var < 0.1: # Very still after fall
                        # Orientation change check
                        pre_impact = history[-20:][:impact_idx]
                        if len(pre_impact) > 0:
                            avg_z_pre = sum(r["z"] for r in pre_impact) / len(pre_impact)
                            avg_z_post = sum(r["z"] for r in post_impact) / len(post_impact)
                            
                            # If orientation changed significantly (e.g. from upright to side)
                            z_diff = abs(avg_z_pre - avg_z_post)
                            if z_diff > 0.5:
                                fall_detected = True
                                # 7. Confidence score
                                fall_confidence = min(1.0, 0.5 + (z_diff * 0.2) + (0.1 / (post_var + 0.01)))

        return {
            "acceleration_magnitude": mag,
            "activity_state": activity_state,
            "activity_score": round(activity_score, 2),
            "fall_detected": fall_detected,
            "fall_confidence": round(fall_confidence, 2)
        }

fall_detector = FallDetector()
