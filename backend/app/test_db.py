import sys
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..")))
from database import models
from database.database import SessionLocal, engine
models.Base.metadata.create_all(bind=engine)
from services import DatabaseService
print('Reading ID:', DatabaseService.save_reading({'cattle_id': 'SC-001', 'timestamp': 6139}))
