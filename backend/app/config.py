from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import List

class Settings(BaseSettings):
    ENVIRONMENT: str = "development"
    DATABASE_URL: str = "sqlite:///./smart_cattle.db"
    REDIS_URL: str = "redis://localhost:6379"
    SERIAL_PORT: str = "AUTO"
    SERIAL_BAUDRATE: int = 9600
    PAUSE_SERIAL: bool = False
    JWT_SECRET: str = "default_secret_key_smart_cattle"
    
    # SMS Alert Configuration
    ALERT_PHONE_NUMBER: str = "+91XXXXXXXXXX"
    SMS_ALERT_ENABLED: bool = True
    SMS_PHONE_NUMBER: str = "+91XXXXXXXXXX"
    SMS_PROVIDER: str = "fast2sms"  # Options: "fast2sms", "twilio", "msg91", "mock"
    
    # SMS Gateway Credentials (leave empty in git; set in .env)
    FAST2SMS_API_KEY: str = ""
    TWILIO_ACCOUNT_SID: str = ""
    TWILIO_AUTH_TOKEN: str = ""
    TWILIO_FROM_NUMBER: str = ""
    MSG91_AUTH_KEY: str = ""
    MSG91_SENDER_ID: str = "SMTCAT"
    
    CORS_ORIGINS: str = "*"
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    ADMIN_USERNAME: str = "admin"
    ADMIN_PASSWORD: str = "admin"
    
    HARDWARE_MODE: str = "real"
    ALLOW_SIMULATION: bool = False
    TIMEZONE: str = "Asia/Kolkata"
    
    @property
    def cors_origins_list(self) -> List[str]:
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",")]

    @property
    def target_phone_number(self) -> str:
        if self.ALERT_PHONE_NUMBER and "X" not in self.ALERT_PHONE_NUMBER:
            return self.ALERT_PHONE_NUMBER
        if self.SMS_PHONE_NUMBER and "X" not in self.SMS_PHONE_NUMBER:
            return self.SMS_PHONE_NUMBER
        return self.ALERT_PHONE_NUMBER

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

settings = Settings()
