import logging
from datetime import datetime, date, timezone
from typing import Optional, Tuple, Dict, Any
from sqlalchemy.orm import Session
from database import models
from config import settings

logger = logging.getLogger("step_manager")

class AuthoritativeStepManager:
    """
    Safeguard 11 & Safeguard 4: Authoritative Daily Step Counter & Anomaly Protection.
    
    The Arduino Mega has no real-time clock. It only tracks a wear-leveled persistent
    lifetime counter in EEPROM plus a boot count.
    
    This manager is the single authoritative source of daily step totals:
    - Calculates daily steps strictly from persisted lifetime counter deltas.
    - Day boundary is governed by `settings.TIMEZONE`.
    - Handles device reboots, duplicate packets, reconnects, counter rollovers.
    - Anomaly protection: Unexpected lifetime decreases (e.g., power loss before EEPROM checkpoint,
      hardware replacement, or memory corruption) are detected, logged, and baseline-reconciled
      WITHOUT subtracting from today's steps or double-counting.
    """
    
    # In-memory fast cache keyed by cattle_id:
    # {
    #   cattle_id: {
    #       "date": date,
    #       "lifetime": int,
    #       "steps_today": int,
    #       "boot_count": Optional[int]
    #   }
    # }
    _cache: Dict[str, Dict[str, Any]] = {}
    
    # Plausible maximum step delta in a single packet interval (e.g. 5,000 steps)
    MAX_PLAUSIBLE_STEP_DELTA: int = 5000

    @classmethod
    def get_local_now(cls, dt_val: Optional[datetime] = None) -> datetime:
        """Resolve current datetime in configured timezone."""
        try:
            import zoneinfo
            tz = zoneinfo.ZoneInfo(settings.TIMEZONE)
        except Exception:
            tz = timezone.utc

        if dt_val is None:
            return datetime.now(tz)
        
        if dt_val.tzinfo is None:
            # Assume UTC if naive, then convert to local timezone
            dt_val = dt_val.replace(tzinfo=timezone.utc)
        return dt_val.astimezone(tz)

    @classmethod
    def reset_cache_for_testing(cls):
        """Helper for unit tests."""
        cls._cache.clear()

    @classmethod
    def process_step_reading(
        cls,
        db: Session,
        cattle_id: str,
        incoming_lifetime: Optional[int],
        boot_count: Optional[int] = None,
        reading_dt: Optional[datetime] = None
    ) -> Tuple[int, int]:
        """
        Process a step reading for cattle_id.
        Returns: (steps_today, lifetime_steps)
        """
        local_now = cls.get_local_now(reading_dt)
        today = local_now.date()

        # If incoming lifetime is None, return whatever current state is known
        if incoming_lifetime is None:
            state = cls._cache.get(cattle_id)
            if state:
                if state["date"] != today:
                    state["date"] = today
                    state["steps_today"] = 0
                return state["steps_today"], state["lifetime"]
            return 0, 0

        # Load or initialize state
        if cattle_id not in cls._cache:
            cls._init_cattle_state(db, cattle_id, today, incoming_lifetime, boot_count)

        state = cls._cache[cattle_id]

        # 1. Check for day rollover (midnight transition in settings.TIMEZONE)
        if state["date"] != today:
            logger.info(
                f"[STEP MGR] Day rollover for {cattle_id} from {state['date']} to {today}. "
                f"Resetting steps_today to 0. Lifetime={state['lifetime']}"
            )
            state["date"] = today
            state["steps_today"] = 0

        last_lifetime = state["lifetime"]
        last_boot = state.get("boot_count")
        delta = incoming_lifetime - last_lifetime

        if delta == 0:
            # Duplicate packet or stationary cow (no new steps)
            pass
        elif delta > 0:
            if delta > cls.MAX_PLAUSIBLE_STEP_DELTA:
                logger.warning(
                    f"[STEP MGR] Anomaly: Implausibly large step delta {delta} for {cattle_id} "
                    f"(prev_lifetime={last_lifetime}, incoming={incoming_lifetime}). "
                    f"Clamping delta to 0 and reconciling baseline."
                )
                state["lifetime"] = incoming_lifetime
            else:
                # Normal steps registered
                state["steps_today"] += delta
                state["lifetime"] = incoming_lifetime
        else:
            # delta < 0: Counter anomaly! (Unexpected decrease)
            # Causes:
            # 1. Power failure between EEPROM commit checkpoints (Arduino lost uncommitted RAM steps)
            # 2. Hardware replacement / board reset
            # 3. 32-bit counter rollover
            logger.warning(
                f"[STEP MGR] Counter Anomaly Detected for {cattle_id}: "
                f"incoming_lifetime ({incoming_lifetime}) < last_trusted_lifetime ({last_lifetime}) "
                f"[boot_count: prev={last_boot}, incoming={boot_count}]. "
                f"Preserving steps_today={state['steps_today']} and reconciling new baseline."
            )
            # Preserve current steps_today and set baseline to incoming_lifetime
            state["lifetime"] = incoming_lifetime

        state["boot_count"] = boot_count

        return state["steps_today"], state["lifetime"]

    @classmethod
    def _init_cattle_state(
        cls,
        db: Session,
        cattle_id: str,
        today: date,
        incoming_lifetime: int,
        boot_count: Optional[int]
    ):
        """Initialize state from database records if available."""
        last_reading = (
            db.query(models.SensorReading)
            .filter(
                models.SensorReading.cattle_id == cattle_id,
                models.SensorReading.step_count_lifetime != None
            )
            .order_by(models.SensorReading.timestamp.desc())
            .first()
        )

        if last_reading:
            reading_dt = last_reading.timestamp
            local_reading_dt = cls.get_local_now(reading_dt)
            reading_date = local_reading_dt.date()
            prev_lifetime = last_reading.step_count_lifetime or incoming_lifetime

            if reading_date == today:
                steps_today = last_reading.step_count_today or 0
            else:
                steps_today = 0

            cls._cache[cattle_id] = {
                "date": today,
                "lifetime": prev_lifetime,
                "steps_today": steps_today,
                "boot_count": boot_count
            }
        else:
            # First time ever seeing this cattle
            cls._cache[cattle_id] = {
                "date": today,
                "lifetime": incoming_lifetime,
                "steps_today": 0,
                "boot_count": boot_count
            }
