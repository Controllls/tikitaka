import os
import shutil
import tempfile
import uuid
from pathlib import Path
from .config import settings


class FileStorage:
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def path(self, key):
        path = (self.root / key).resolve()
        if not path.is_relative_to(self.root) or path == self.root:
            raise ValueError("Invalid object key")
        return path

    def put_file(self, key, source):
        target = self.path(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, temp = tempfile.mkstemp(dir=target.parent)
        os.close(fd)
        try:
            shutil.copyfile(source, temp)
            os.replace(temp, target)
        finally:
            Path(temp).unlink(missing_ok=True)

    def get_file(self, key):
        return self.path(key)

    def delete(self, key):
        if key:
            self.path(key).unlink(missing_ok=True)

    def exists(self, key):
        return bool(key) and self.path(key).is_file()

    def copy(self, source, target):
        self.put_file(target, self.get_file(source))

    def health(self):
        key = f"_health/{uuid.uuid4().hex}"
        fd, temp = tempfile.mkstemp(dir=self.root)
        os.close(fd)
        try:
            self.put_file(key, temp)
            if not self.exists(key):
                raise OSError("Storage probe not readable")
        finally:
            self.delete(key)
            Path(temp).unlink(missing_ok=True)

    def local_entries(self):
        for prefix in ("sources", "results", "tracks", "highlights", "episodes", "_uploads", "_work", "_health"):
            for path in (self.root/prefix).rglob("*"):
                try:
                    if path.is_file() and not path.is_symlink():
                        yield path.relative_to(self.root).as_posix(), path.stat().st_mtime
                except FileNotFoundError:
                    pass  # A worker may have completed its temporary-file cleanup.

    def entries(self):
        yield from self.local_entries()


class S3Storage(FileStorage):
    """Private S3 objects; local read-through cache. No public/signed audio URLs."""
    def __init__(self, root, bucket, endpoint_url=None):
        super().__init__(root)
        import boto3
        self.client = boto3.client("s3", endpoint_url=endpoint_url)
        self.bucket = bucket

    def put_file(self, key, source):
        self.path(key)  # Validate key for both storage backends.
        self.client.upload_file(str(source), self.bucket, key)

    def get_file(self, key):
        target = self.path(key)
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            fd, temp = tempfile.mkstemp(dir=target.parent)
            os.close(fd)
            try:
                self.client.download_file(self.bucket, key, temp)
                os.replace(temp, target)
            finally:
                Path(temp).unlink(missing_ok=True)
        return target

    def exists(self, key):
        if not key:
            return False
        from botocore.exceptions import ClientError
        try:
            self.client.head_object(Bucket=self.bucket, Key=key)
            return True
        except ClientError as exc:
            if exc.response["ResponseMetadata"]["HTTPStatusCode"] == 404:
                return False
            raise

    def delete(self, key):
        if key:
            self.client.delete_object(Bucket=self.bucket, Key=key)
            super().delete(key)

    def copy(self, source, target):
        self.path(source)
        self.path(target)
        self.client.copy_object(Bucket=self.bucket, Key=target, CopySource={"Bucket": self.bucket, "Key": source})

    def entries(self):
        # The bucket may contain other applications; only inspect MOMO-owned prefixes.
        for prefix in ("sources/", "results/", "tracks/", "highlights/", "episodes/", "_health/"):
            pages = self.client.get_paginator("list_objects_v2").paginate(Bucket=self.bucket, Prefix=prefix)
            for page in pages:
                for item in page.get("Contents", []):
                    yield item["Key"], item["LastModified"].timestamp()
        yield from self.local_entries()


def make_storage():
    if settings.storage_backend == "s3":
        return S3Storage(settings.storage_root, settings.s3_bucket, settings.s3_endpoint_url)
    return FileStorage(settings.storage_root)


storage = make_storage()
