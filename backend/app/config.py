from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import List

class Settings(BaseSettings):
    ENVIRONMENT: str = "development"
    DATABASE_URL: str = "sqlite:///./smart_cattle.db"
    REDIS_URL: str = "redis://localhost:6379"
    SERIAL_PORT: str
    SERIAL_BAUDRATE: int = 9600
    JWT_SECRET: str
    SMS_PHONE_NUMBER: str
    CORS_ORIGINS: str = "*"
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    ADMIN_USERNAME: str
    ADMIN_PASSWORD: str
    
    HARDWARE_MODE: str = "real"
    ALLOW_SIMULATION: bool = False
    TIMEZONE: str = "Asia/Kolkata"
    
    @property
    def cors_origins_list(self) -> List[str]:
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",")]

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

settings = Settings()
