import asyncio
import io
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile

from fastapi import HTTPException, Request
from fastapi.responses import FileResponse, Response

from .scene import Scene, character_files, image_size, resolve_scene, unpack_character
from ..config import settings


def public_asset(asset):
    return {key:asset[key] for key in ("id","kind","name","format","poses","suggested_layout","default_source") if key in asset}


def validate_background(path):
    if path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"}:
        image_size(path)
        return
    process = subprocess.run([settings.ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "v:0",
        "-show_entries", "stream=width,height:format=duration", "-of", "json", str(path)], capture_output=True, timeout=20)
    try:
        data = json.loads(process.stdout)
        stream = data["streams"][0]
        duration = float(data["format"]["duration"])
        if process.returncode or not 0 < duration <= 600 or not 0 < max(stream["width"],stream["height"]) <= 4096:
            raise ValueError()
    except (KeyError, IndexError, ValueError):
        raise ValueError("배경은 10분·4096px 이하 영상 또는 이미지여야 합니다.")


def install_video_routes(app, lab, session_for):
    @app.post("/api/video/packages")
    async def upload_package(request: Request, category: str):
        owner = session_for(request).csrf
        if category not in {"background", "character", "vehicles"}:
            raise HTTPException(422, "background·character·vehicles 중에서 선택하세요.")
        if lab.uploading or any(j.process.poll() is None for j in lab.jobs.values()):
            raise HTTPException(409, "진행 중인 작업이 끝난 뒤 소스를 추가하세요.")
        directory = lab.root / ("package-" + secrets.token_hex(16))
        directory.mkdir()
        path = directory / "sources.zip"
        lab.uploading = True
        committed = False
        try:
            count = 0
            with path.open("wb") as file:
                async for chunk in request.stream():
                    count += len(chunk)
                    if count > 80_000_000:
                        raise HTTPException(413, "소스 묶음은 한 번에 80 MB 이하여야 합니다.")
                    file.write(chunk)
            from .packages import package_assets
            rows = await asyncio.to_thread(package_assets, path, directory / "sources", category, validate_background)
            if sum(a["owner"] is not None for a in lab.assets.values()) + len(rows) > 40:
                raise HTTPException(409, "추가 소스는 총 40개까지 보관할 수 있습니다.")
            additions = [{**row, "id": secrets.token_hex(16), "owner": owner} for row in rows]
            lab.assets.update({row["id"]: row for row in additions})
            committed = True
            return {"assets": [public_asset(a) for a in additions]}
        except (ValueError, KeyError, TypeError, zipfile.BadZipFile, OSError, subprocess.TimeoutExpired) as exc:
            raise HTTPException(422, "소스 묶음을 확인하세요. " + (str(exc)[:300] if isinstance(exc, ValueError) else "파일이 손상되었거나 필수 PNG 파츠가 없습니다.")) from exc
        finally:
            lab.uploading = False
            if not committed:
                shutil.rmtree(directory)

    @app.get("/api/video/assets")
    async def assets(request: Request):
        owner = session_for(request).csrf
        from .default_sources import load
        _, catalog = load()
        return {"assets": [public_asset(a) for a in lab.assets.values() if a["owner"] in (None,owner)],
                "defaults": catalog["defaults"] if catalog else Scene().model_dump(),
                "default_characters": catalog["default_characters"] if catalog else [],
                "default_source": {"label": catalog["source_label"], **catalog["counts"]} if catalog else None}

    @app.post("/api/video/assets")
    async def upload_asset(request: Request, kind: str, filename: str):
        owner = session_for(request).csrf
        if kind not in {"background","character","vehicle","seatbelts","steering_wheel"}:
            raise HTTPException(422,"지원하지 않는 소스 종류입니다.")
        if lab.uploading or any(j.process.poll() is None for j in lab.jobs.values()):
            raise HTTPException(409,"진행 중인 작업이 끝난 뒤 소스를 추가하세요.")
        if sum(a["owner"] is not None for a in lab.assets.values()) >= 40:
            raise HTTPException(409,"추가 소스 40개 한도입니다. 결과를 저장한 뒤 서버를 다시 시작하세요.")
        suffix = Path(filename).suffix.lower()
        allowed = {".zip"} if kind == "character" else {".png"} if kind != "background" else {".mp4",".webm",".mov",".png",".jpg",".jpeg",".webp"}
        if suffix not in allowed or len(filename)>250:
            raise HTTPException(422,"배경은 MP4·WebM·MOV·이미지, 캐릭터는 ZIP, 차량·오버레이는 PNG를 사용하세요.")
        identity = secrets.token_hex(16)
        directory = lab.root / ("asset-"+identity)
        directory.mkdir()
        path = directory / ("source"+suffix)
        lab.uploading = True
        try:
            size = 0
            with path.open("wb") as file:
                async for chunk in request.stream():
                    size += len(chunk)
                    if size>80_000_000:
                        raise HTTPException(413,"소스 파일은 80 MB 이하여야 합니다.")
                    file.write(chunk)
            if kind == "character":
                path = await asyncio.to_thread(unpack_character,path,directory/"character")
            elif kind == "background":
                await asyncio.to_thread(validate_background,path)
            else:
                await asyncio.to_thread(image_size,path)
            asset = dict(id=identity,kind=kind,name=Path(filename.replace("\\","/")).name,path=str(path),owner=owner)
            lab.assets[identity] = asset
            return {k:asset[k] for k in ("id","kind","name")}
        except (ValueError,KeyError,TypeError,zipfile.BadZipFile,OSError,subprocess.TimeoutExpired) as exc:
            raise HTTPException(422,"소스 형식·PNG 파츠·manifest.json을 확인하세요. " + (str(exc)[:180] if isinstance(exc,ValueError) else "")) from exc
        finally:
            lab.uploading = False
            if identity not in lab.assets:
                # Server-generated direct child of the dedicated temporary root only.
                shutil.rmtree(directory)

    @app.post("/api/video/preview")
    async def scene_preview(body: Scene, request: Request):
        owner = session_for(request).csrf
        if any(j.process.poll() is None for j in lab.jobs.values()):
            raise HTTPException(409,"영상 제작이 끝난 뒤 미리보기를 갱신하세요.")
        try:
            scene = resolve_scene(body,lab.assets,owner)
            from .render import preview
            image = await asyncio.to_thread(preview,scene)
            stream = io.BytesIO()
            image.save(stream,format="PNG")
            return Response(stream.getvalue(),media_type="image/png")
        except ValueError as exc:
            raise HTTPException(422,str(exc)) from exc

    @app.get("/api/video/character-template")
    async def template(request: Request):
        session_for(request,csrf=False)
        path = lab.root / "character-template.zip"
        if not path.is_file():
            manifest = Path(lab.assets["builtin-character-1"]["path"])
            _, parts = character_files(manifest)
            with zipfile.ZipFile(path,"w",zipfile.ZIP_DEFLATED) as archive:
                archive.write(manifest,"manifest.json")
                for file in parts:
                    archive.write(file,file.relative_to(manifest.parent).as_posix())
        return FileResponse(path,media_type="application/zip",filename="character-template.zip")
