from pydantic_settings import BaseSettings, SettingsConfigDict
from typing import List

class Settings(BaseSettings):
    ENVIRONMENT: str = "development"
    DATABASE_URL: str = "sqlite:///./smart_cattle.db"
    SERIAL_PORT: str = "COM6"
    SERIAL_BAUDRATE: int = 9600
    HARDWARE_MODE: str = "arduino"
    ALLOW_SIMULATION: bool = True
    JWT_SECRET: str = "fallback_secret_key"
    SMS_PHONE_NUMBER: str = "+1234567890"
    CORS_ORIGINS: str = "*"
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    ADMIN_USERNAME: str = "admin"
    ADMIN_PASSWORD: str = "admin"
    
    @property
    def cors_origins_list(self) -> List[str]:
        return [origin.strip() for origin in self.CORS_ORIGINS.split(",")]

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8")

settings = Settings()
