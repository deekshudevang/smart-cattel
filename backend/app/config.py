from pydantic_settings import BaseSettings
from typing import List

class Settings(BaseSettings):
    SERIAL_PORT: str = "COM4"
    SERIAL_BAUDRATE: int = 9600
    DATABASE_URL: str = "sqlite:///./smart_cattle.db"
    API_HOST: str = "0.0.0.0"
    API_PORT: int = 8000
    SECRET_KEY: str = "fallback_secret_key"
    JWT_ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 30
    ALLOWED_ORIGINS: str = "*"

    @property
    def cors_origins(self) -> List[str]:
        return [origin.strip() for origin in self.ALLOWED_ORIGINS.split(",")]

    class Config:
        env_file = ".env"

settings = Settings()
