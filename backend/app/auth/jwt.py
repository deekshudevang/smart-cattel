from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.security import OAuth2PasswordBearer, OAuth2PasswordRequestForm
from typing import Annotated

# Scaffold for Phase 14: Authentication
router = APIRouter()
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")

@router.post("/token")
async def login(form_data: Annotated[OAuth2PasswordRequestForm, Depends()]):
    if form_data.username == "admin" and form_data.password == "admin123":
        return {"access_token": "fake-jwt-token", "token_type": "bearer"}
    raise HTTPException(status_code=400, detail="Incorrect username or password")

async def get_current_user(token: Annotated[str, Depends(oauth2_scheme)]):
    if token != "fake-jwt-token":
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    return {"user": "admin"}
