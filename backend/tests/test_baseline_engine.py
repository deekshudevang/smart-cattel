import pytest
from unittest.mock import MagicMock, patch
from datetime import datetime, timedelta
from app.baseline_engine import BaselineEngine

class MockQueryResult:
    def __init__(self, **kwargs):
        for k, v in kwargs.items():
            setattr(self, k, v)

def test_calculate_metrics_insufficient_data():
    res = BaselineEngine._calculate_metrics(None, 60.0)
    assert res["status"] == "insufficient_data"
    assert res["current_value"] is None
    
    res = BaselineEngine._calculate_metrics(60.0, None)
    assert res["status"] == "insufficient_data"
    assert res["baseline"] is None

def test_calculate_metrics_stable():
    res = BaselineEngine._calculate_metrics(62.0, 60.0)
    assert res["status"] == "ok"
    assert res["trend"] == "stable"
    assert res["baseline"] == 60.0
    assert res["current_value"] == 62.0
    assert res["difference"] == 2.0
    assert res["percentage_deviation"] == round((2.0 / 60.0) * 100, 2)

def test_calculate_metrics_increasing():
    res = BaselineEngine._calculate_metrics(70.0, 60.0)
    assert res["status"] == "ok"
    assert res["trend"] == "increasing"

def test_calculate_metrics_decreasing():
    res = BaselineEngine._calculate_metrics(50.0, 60.0)
    assert res["status"] == "ok"
    assert res["trend"] == "decreasing"

def test_calculate_baselines_invalid_window():
    db = MagicMock()
    with pytest.raises(ValueError):
        BaselineEngine.calculate_baselines(db, "C-1234", "invalid_window")

def test_calculate_baselines_valid():
    db = MagicMock()
    
    # Mocking the query chain is complex, but we can set up side effects for first() and scalar()
    # Query 1: latest_sensor
    mock_latest_sensor = MockQueryResult(heart_rate=65.0, spo2=98.0, temperature=38.5)
    # Query 2: sensor_avg
    mock_sensor_avg = MockQueryResult(avg_bpm=60.0, avg_spo2=99.0, avg_temp=38.0)
    
    # Query 3: latest_activity
    mock_latest_activity = MockQueryResult(steps=500)
    # Query 4: activity_avg (scalar)
    mock_activity_avg = 400.0
    
    # Query 5: latest_feed
    mock_latest_feed = MockQueryResult(quantity=5.0)
    # Query 6: feed_avg (scalar)
    mock_feed_avg = 5.0
    
    # Query 7: latest_milk
    mock_latest_milk = MockQueryResult(quantity=10.0)
    # Query 8: milk_avg (scalar)
    mock_milk_avg = 12.0
    
    def mock_query(*args, **kwargs):
        mock_chain = MagicMock()
        
        # We differentiate by looking at the arguments if possible, or just sequence it.
        # But for simplicity, we can mock the filter().order_by().first() chain
        # Because we call db.query() 8 times in this exact order, we can use side_effect on a method
        return mock_chain

    # It's easier to mock db.query().filter().order_by().first() / db.query().filter().first() / scalar()
    # Let's set up a stateful mock
    call_counter = {"first": 0, "scalar": 0}
    
    def mock_first(*args, **kwargs):
        call_counter["first"] += 1
        if call_counter["first"] == 1:
            return mock_latest_sensor
        elif call_counter["first"] == 2:
            return mock_sensor_avg
        elif call_counter["first"] == 3:
            return mock_latest_activity
        elif call_counter["first"] == 4:
            return mock_latest_feed
        elif call_counter["first"] == 5:
            return mock_latest_milk
        return None
        
    def mock_scalar(*args, **kwargs):
        call_counter["scalar"] += 1
        if call_counter["scalar"] == 1:
            return mock_activity_avg
        elif call_counter["scalar"] == 2:
            return mock_feed_avg
        elif call_counter["scalar"] == 3:
            return mock_milk_avg
        return None

    # Setup the mock chain
    mock_filter = MagicMock()
    mock_order_by = MagicMock()
    
    db.query.return_value.filter.return_value = mock_filter
    
    # filter() can return something that has order_by() or first() or scalar()
    mock_filter.order_by.return_value.first.side_effect = lambda: mock_first()
    mock_filter.first.side_effect = lambda: mock_first()
    mock_filter.scalar.side_effect = lambda: mock_scalar()

    # Wait, the sensor avg query uses first() directly after filter: db.query().filter(...).first()
    # The others use scalar().
    # The latest queries use order_by().first()
    
    results = BaselineEngine.calculate_baselines(db, "C-123", "24_hours")
    
    assert "bpm" in results
    assert results["bpm"]["status"] == "ok"
    assert results["bpm"]["current_value"] == 65.0
    assert results["bpm"]["baseline"] == 60.0
    
    assert "activity" in results
    assert results["activity"]["current_value"] == 500
    assert results["activity"]["baseline"] == 400.0
    
    assert "feed" in results
    assert results["feed"]["trend"] == "stable"
    
    assert "milk" in results
    assert results["milk"]["trend"] == "decreasing"

