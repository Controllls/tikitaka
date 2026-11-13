import hashlib
import secrets
import time
import jwt
from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pwdlib import PasswordHash
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from .config import settings
from .db import Installation, Session, RefreshToken, User, get_db, uid
from .errors import ServiceError

passwords = PasswordHash.recommended()
bearer = HTTPBearer(auto_error=False)
dummy_hash = passwords.hash(secrets.token_urlsafe(32))


def token_hash(token):
    return hashlib.sha256(token.encode()).hexdigest()


def unauthorized():
    return ServiceError("UNAUTHORIZED", "앱 인증을 확인하지 못했습니다. 다시 시도해 주세요.", 401)


def check_installation(db, user_id):
    installation = db.scalar(select(Installation).where(Installation.user_id == user_id))
    if installation and installation.revoked_at is not None:
        raise ServiceError("INSTALLATION_REVOKED", "이 앱의 서버 이용이 중지되었습니다.", 403)


def authenticate_installation(db, installation_id, secret):
    """An ID is public; only possession of the independently random secret authenticates it."""
    installation = db.get(Installation, installation_id)
    if installation is None:
        try:
            user = User(username=f"installation:{uid()}", password_hash=passwords.hash(secrets.token_urlsafe(48)))
            db.add(user)
            db.flush()
            installation = Installation(id=installation_id, user_id=user.id, secret_hash=token_hash(secret))
            db.add(installation)
            db.commit()
        except IntegrityError:
            # A concurrent first request may have registered the same ID. Never replace its secret.
            db.rollback()
            installation = db.get(Installation, installation_id)
    if installation is None or not secrets.compare_digest(installation.secret_hash, token_hash(secret)):
        raise unauthorized()
    check_installation(db, installation.user_id)
    return start_session(db, installation.user_id)


def start_session(db, user_id):
    session = Session(user_id=user_id, expires_at=time.time() + 30 * 86400)
    db.add(session)
    db.flush()
    result = issue(db, session)
    db.commit()
    return result


def issue(db, session, now=None):
    now = now or time.time()
    refresh = secrets.token_urlsafe(48)
    db.add(RefreshToken(token_hash=token_hash(refresh), session_id=session.id,
                        expires_at=session.expires_at))
    access = jwt.encode(dict(sub=session.user_id, sid=session.id, iss=settings.jwt_issuer,
                             aud=settings.jwt_audience, iat=int(now), exp=int(now + 900)),
                        settings.jwt_secret, algorithm="HS256")
    return dict(access_token=access, refresh_token=refresh, token_type="Bearer", expires_in=900)


def login(db, username, password):
    user = db.scalar(select(User).where(User.username == username))
    valid = passwords.verify(password, user.password_hash if user else dummy_hash)
    if not user or not valid:
        raise unauthorized()
    check_installation(db, user.id)
    return start_session(db, user.id)


def rotate(db, refresh):
    now = time.time()
    token = db.scalar(select(RefreshToken).where(RefreshToken.token_hash == token_hash(refresh)).with_for_update())
    if not token:
        raise unauthorized()
    session = db.get(Session, token.session_id)
    check_installation(db, session.user_id)
    if token.used_at:
        # Keep used hashes until expiry: replay revokes the entire family, including new JWTs.
        db.execute(update(Session).where(Session.family_id == session.family_id).values(revoked_at=now))
        db.commit()
        raise unauthorized()
    if token.revoked_at or token.expires_at <= now or session.revoked_at or session.expires_at <= now:
        raise unauthorized()
    claimed = db.execute(update(RefreshToken).where(RefreshToken.token_hash == token.token_hash,
                         RefreshToken.used_at.is_(None)).values(used_at=now)).rowcount
    if not claimed:
        db.execute(update(Session).where(Session.family_id == session.family_id).values(revoked_at=now))
        db.commit()
        raise unauthorized()
    result = issue(db, session, now)
    db.commit()
    return result


def logout(db, refresh):
    token = db.get(RefreshToken, token_hash(refresh))
    if token:
        session = db.get(Session, token.session_id)
        session.revoked_at = time.time()
        db.commit()


def current_user(credentials: HTTPAuthorizationCredentials | None = Depends(bearer), db=Depends(get_db)):
    if not credentials:
        raise unauthorized()
    try:
        claims = jwt.decode(credentials.credentials, settings.jwt_secret, algorithms=["HS256"],
                            audience=settings.jwt_audience, issuer=settings.jwt_issuer,
                            options={"require": ["exp", "iss", "aud", "sub", "sid"]})
    except jwt.InvalidTokenError:
        raise unauthorized()
    session = db.get(Session, claims["sid"])
    if not session or session.user_id != claims["sub"] or session.revoked_at or session.expires_at <= time.time():
        raise unauthorized()
    check_installation(db, session.user_id)
    return session.user_id
