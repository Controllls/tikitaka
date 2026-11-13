import argparse
import getpass
from .auth import passwords
from .db import Base, engine, SessionLocal, User
from .config import settings


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["init-db", "create-user"])
    parser.add_argument("--username")
    args = parser.parse_args()
    if args.command == "init-db":
        if settings.profile == "production":
            raise SystemExit("Use alembic upgrade head in production")
        Base.metadata.create_all(engine)
        return
    if not args.username:
        parser.error("--username is required")
    password = getpass.getpass("Password (minimum 12 characters): ")
    if len(password) < 12 or password != getpass.getpass("Repeat password: "):
        raise SystemExit("Password mismatch or too short")
    with SessionLocal() as db:
        db.add(User(username=args.username, password_hash=passwords.hash(password)))
        db.commit()
    print("User created")


if __name__ == "__main__":
    main()
