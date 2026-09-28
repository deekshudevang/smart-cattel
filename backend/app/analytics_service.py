from sqlalchemy.orm import Session
from sqlalchemy import func, desc, asc
from datetime import datetime, timedelta
from database import models
from collections import defaultdict
import numpy as np

class AnalyticsService:
    @staticmethod
    def get_milk_analytics(db: Session, cattle_id: str = None, days: int = 30):
        start_date = datetime.utcnow() - timedelta(days=days)
        
        query = db.query(models.MilkProduction).filter(models.MilkProduction.date >= start_date)
        if cattle_id:
            query = query.filter(models.MilkProduction.cattle_id == cattle_id)
            
        records = query.order_by(asc(models.MilkProduction.date)).all()
        
        # Group by day
        daily_totals = defaultdict(float)
        cow_totals = defaultdict(float)
        total_qty = 0
        
        for r in records:
            day_str = r.date.strftime('%Y-%m-%d')
            daily_totals[day_str] += r.quantity
            cow_totals[r.cattle_id] += r.quantity
            total_qty += r.quantity
            
        avg_production = total_qty / len(daily_totals) if daily_totals else 0
        
        trend = [{"date": k, "quantity": v} for k, v in sorted(daily_totals.items())]
        
        return {
            "daily_production": trend[-1]["quantity"] if trend else 0,
            "weekly_production": sum(v for k, v in list(daily_totals.items())[-7:]),
            "monthly_production": total_qty,
            "average_production": avg_production,
            "cow_wise": [{"cattle_id": k, "quantity": v} for k, v in cow_totals.items()],
            "trend": trend
        }

    @staticmethod
    def get_feed_analytics(db: Session, cattle_id: str = None, days: int = 30):
        start_date = datetime.utcnow() - timedelta(days=days)
        
        feed_query = db.query(models.FeedConsumption).filter(models.FeedConsumption.date >= start_date)
        milk_query = db.query(models.MilkProduction).filter(models.MilkProduction.date >= start_date)
        
        if cattle_id:
            feed_query = feed_query.filter(models.FeedConsumption.cattle_id == cattle_id)
            milk_query = milk_query.filter(models.MilkProduction.cattle_id == cattle_id)
            
        feed_records = feed_query.order_by(asc(models.FeedConsumption.date)).all()
        milk_records = milk_query.all()
        
        daily_feed = defaultdict(float)
        cow_feed = defaultdict(float)
        total_feed = 0
        
        for r in feed_records:
            day_str = r.date.strftime('%Y-%m-%d')
            daily_feed[day_str] += r.quantity
            cow_feed[r.cattle_id] += r.quantity
            total_feed += r.quantity
            
        daily_milk = defaultdict(float)
        for r in milk_records:
            day_str = r.date.strftime('%Y-%m-%d')
            daily_milk[day_str] += r.quantity
            
        # Feed efficiency: milk produced / feed consumed per day
        efficiency_trend = []
        for day in sorted(daily_feed.keys()):
            feed_qty = daily_feed[day]
            milk_qty = daily_milk.get(day, 0)
            eff = milk_qty / feed_qty if feed_qty > 0 else 0
            efficiency_trend.append({"date": day, "efficiency": round(eff, 2)})
            
        trend = [{"date": k, "quantity": v} for k, v in sorted(daily_feed.items())]
        
        return {
            "daily_consumption": trend[-1]["quantity"] if trend else 0,
            "weekly_consumption": sum(v for k, v in list(daily_feed.items())[-7:]),
            "cow_wise": [{"cattle_id": k, "quantity": v} for k, v in cow_feed.items()],
            "trend": trend,
            "efficiency_trend": efficiency_trend
        }

    @staticmethod
    def get_activity_analytics(db: Session, cattle_id: str = None, days: int = 30):
        start_date = datetime.utcnow() - timedelta(days=days)
        
        query = db.query(models.Activity).filter(models.Activity.date >= start_date)
        if cattle_id:
            query = query.filter(models.Activity.cattle_id == cattle_id)
            
        records = query.order_by(asc(models.Activity.date)).all()
        
        daily_steps = defaultdict(int)
        for r in records:
            day_str = r.date.strftime('%Y-%m-%d')
            daily_steps[day_str] += r.steps
            
        trend = [{"date": k, "steps": v} for k, v in sorted(daily_steps.items())]
        
        # Inactivity periods (e.g. days with < 500 steps)
        inactivity_periods = [
            {"date": k, "steps": v} for k, v in daily_steps.items() if v < 500
        ]
        
        return {
            "daily_steps": trend[-1]["steps"] if trend else 0,
            "trend": trend,
            "inactivity_periods": sorted(inactivity_periods, key=lambda x: x["date"])
        }

    @staticmethod
    def get_correlations(db: Session, cattle_id: str):
        # Calculate recent trends (last 7 days vs previous 7 days)
        now = datetime.utcnow()
        recent_start = now - timedelta(days=7)
        prev_start = recent_start - timedelta(days=7)
        
        def get_metric_sum(model, field, start, end):
            query = db.query(func.sum(getattr(model, field))).filter(
                model.cattle_id == cattle_id,
                model.date >= start,
                model.date < end
            ).scalar()
            return query or 0

        # Milk
        recent_milk = get_metric_sum(models.MilkProduction, "quantity", recent_start, now)
        prev_milk = get_metric_sum(models.MilkProduction, "quantity", prev_start, recent_start)
        
        # Feed
        recent_feed = get_metric_sum(models.FeedConsumption, "quantity", recent_start, now)
        prev_feed = get_metric_sum(models.FeedConsumption, "quantity", prev_start, recent_start)
        
        # Activity
        recent_activity = get_metric_sum(models.Activity, "steps", recent_start, now)
        prev_activity = get_metric_sum(models.Activity, "steps", prev_start, recent_start)

        def calc_trend(recent, prev):
            if prev == 0:
                return 0 # Not enough data
            return (recent - prev) / prev

        milk_trend = calc_trend(recent_milk, prev_milk)
        feed_trend = calc_trend(recent_feed, prev_feed)
        activity_trend = calc_trend(recent_activity, prev_activity)

        observations = []
        
        if milk_trend < -0.1 and feed_trend < -0.1 and activity_trend < -0.1:
            observations.append({
                "type": "CORRELATION_OBSERVED",
                "message": "Observed correlation: Milk production, feed intake, and activity have all decreased over the last 7 days compared to the previous week.",
                "note": "This is an observed statistical correlation based on recorded data, not a medical diagnosis. Please inspect the animal.",
                "metrics": {
                    "milk_change_pct": round(milk_trend * 100, 1),
                    "feed_change_pct": round(feed_trend * 100, 1),
                    "activity_change_pct": round(activity_trend * 100, 1)
                }
            })
            
        elif milk_trend < -0.1 and feed_trend < -0.1:
             observations.append({
                "type": "CORRELATION_OBSERVED",
                "message": "Observed correlation: Both milk production and feed intake have decreased over the last 7 days.",
                "note": "This is an observed statistical correlation based on recorded data, not a medical diagnosis. Please inspect the animal.",
                "metrics": {
                    "milk_change_pct": round(milk_trend * 100, 1),
                    "feed_change_pct": round(feed_trend * 100, 1),
                }
            })

        return observations
