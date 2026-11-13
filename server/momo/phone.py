"""Opt-in companion: completed videos only. No raw microphone or STT endpoint."""
import hashlib
import json
import secrets
import subprocess
import threading
import shutil
import time
from pathlib import Path
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from sqlalchemy import Boolean, Float, ForeignKey, JSON, String, select, update
from sqlalchemy.orm import Mapped, mapped_column
from .auth import current_user, check_installation
from .config import settings
from .db import Base, get_db, uid

router = APIRouter()
STATIC = Path(__file__).parent / "phone_web"
MAX_VIDEO = 150_000_000
_share_lock = threading.Lock()


class Pair(Base):
    __tablename__ = "phone_pairs"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires: Mapped[float] = mapped_column(Float)
    used: Mapped[bool] = mapped_column(Boolean, default=False)


class Device(Base):
    __tablename__ = "phone_devices"
    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=uid)
    owner: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    name: Mapped[str] = mapped_column(String(80))
    approved: Mapped[bool] = mapped_column(Boolean, default=False)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    expires: Mapped[float] = mapped_column(Float)


class PhoneEpisode(Base):
    __tablename__ = "phone_episodes"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)
    owner: Mapped[str] = mapped_column(ForeignKey("users.id"), index=True)
    metadata_json: Mapped[dict] = mapped_column(JSON)
    deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class PhoneJobs(Base):
    __tablename__ = "phone_jobs"
    owner: Mapped[str] = mapped_column(ForeignKey("users.id"), primary_key=True)
    jobs: Mapped[list] = mapped_column(JSON)


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def folder(owner):
    result = settings.storage_root / "phone" / digest(owner)
    result.mkdir(parents=True, exist_ok=True)
    return result


def device(request: Request, db=Depends(get_db)):
    token = request.cookies.get("tiki_phone", "")
    item = db.scalar(select(Device).where(Device.token_hash == digest(token))) if token else None
    if not item or item.revoked or item.expires <= time.time():
        raise HTTPException(401, "차량에서 휴대폰을 다시 연결해 주세요.")
    check_installation(db, item.owner)
    return item


def connected(item=Depends(device)):
    if not item.approved:
        raise HTTPException(403, "차량 화면에서 연결을 확인해 주세요.")
    return item


def same_origin(request: Request):
    # No CORS is enabled. A non-simple header plus strict origin check protects cookie mutations.
    origin = request.headers.get("origin")
    if request.headers.get("x-tiki-client") != "phone" or (origin and urlsplit(origin).netloc != request.url.netloc):
        raise HTTPException(403, "같은 휴대폰 화면에서 다시 시도해 주세요.")


def active(db, owner):
    return db.scalar(select(Device).where(Device.owner == owner, Device.approved.is_(True), Device.revoked.is_(False), Device.expires > time.time()))


def public_url(request):
    if settings.phone_public_url:
        url=urlsplit(settings.phone_public_url)
        if url.username or url.password or url.query or url.fragment or url.scheme not in ('http','https') or (settings.profile=='production' and url.scheme!='https'):
            raise HTTPException(503,"올바른 HTTPS 휴대폰 주소를 설정해 주세요.")
        return settings.phone_public_url.rstrip("/") + "/phone/"
    if settings.profile == "production":
        raise HTTPException(503, "휴대폰 접속 주소를 서버에 설정해 주세요.")
    # Development stays on loopback until the operator configures an explicit phone URL.
    # Never infer and advertise a public network interface on a developer PC.
    return f"http://127.0.0.1:{request.url.port or 8765}/phone/"


@router.post("/v1/phone/pair")
def pair(request: Request, owner=Depends(current_user), db=Depends(get_db)):
    db.execute(update(Pair).where(Pair.owner == owner, Pair.used.is_(False)).values(used=True))
    code = secrets.token_hex(5).upper()
    item = Pair(owner=owner, code_hash=digest(code), expires=time.time()+300)
    db.add(item); db.commit()
    return {"code": code, "expires": item.expires, "url": public_url(request)}


class Claim(BaseModel):
    code: str = Field(min_length=10, max_length=10, pattern=r"^[0-9A-Fa-f]+$")
    name: str = Field(default="내 휴대폰", min_length=1, max_length=80)


@router.post("/phone/api/claim", dependencies=[Depends(same_origin)])
def claim(body: Claim, request: Request, response: Response, db=Depends(get_db)):
    from .api import limit_login
    limit_login(request,"phone-pair")
    # 40-bit random code, five-minute lifetime, atomic single use, and approval on the car.
    item = db.scalar(select(Pair).where(Pair.code_hash == digest(body.code.upper()), Pair.used.is_(False), Pair.expires > time.time()))
    if not item:
        raise HTTPException(400, "연결 코드가 만료됐거나 올바르지 않아요.")
    changed=db.execute(update(Pair).where(Pair.id==item.id, Pair.used.is_(False)).values(used=True))
    if changed.rowcount != 1:
        db.rollback();raise HTTPException(409, "이미 사용한 연결 코드예요.")
    token=secrets.token_urlsafe(32)
    d=Device(owner=item.owner, token_hash=digest(token), name=body.name, expires=time.time()+300)
    db.add(d);db.commit()
    response.set_cookie("tiki_phone",token,httponly=True,secure=settings.profile=="production",samesite="strict",max_age=30*86400,path="/phone")
    response.headers["Cache-Control"]="no-store"
    return {"state":"pending"}


@router.get("/v1/phone/state")
def state(request: Request, owner=Depends(current_user), db=Depends(get_db)):
    items=db.scalars(select(Device).where(Device.owner==owner,Device.revoked.is_(False),Device.expires>time.time())).all()
    return {"devices":[{"id":d.id,"name":d.name,"approved":d.approved} for d in items],"url":public_url(request)}


@router.post("/v1/phone/devices/{device_id}/approve")
def approve(device_id: str, owner=Depends(current_user), db=Depends(get_db)):
    d=db.get(Device,device_id)
    if not d or d.owner!=owner or d.revoked or d.expires<=time.time():raise HTTPException(404)
    d.approved=True;d.expires=time.time()+30*86400;db.commit()
    return {"connected":True}


@router.delete("/v1/phone/devices")
def unlink(owner=Depends(current_user), db=Depends(get_db)):
    db.execute(update(Device).where(Device.owner==owner).values(revoked=True))
    db.execute(update(Pair).where(Pair.owner==owner).values(used=True));db.commit()
    return {"connected":False}


@router.get("/phone/api/session")
def phone_session(d=Depends(device)):
    return {"state":"connected" if d.approved else "pending","name":d.name}


@router.delete("/phone/api/session", dependencies=[Depends(same_origin)])
def phone_unlink(response: Response,d=Depends(device),db=Depends(get_db)):
    d.revoked=True;db.commit();response.delete_cookie("tiki_phone",path="/phone")
    return {"connected":False}


@router.put("/v1/phone/jobs")
def sync_jobs(body: list[dict], owner=Depends(current_user),db=Depends(get_db)):
    if not active(db,owner):raise HTTPException(409,"연결한 휴대폰이 없어요.")
    if len(body)>100 or len(json.dumps(body))>100000:raise HTTPException(413)
    row=db.get(PhoneJobs,owner)
    if row:row.jobs=body
    else:db.add(PhoneJobs(owner=owner,jobs=body))
    db.commit();return {"saved":True}


@router.post("/v1/phone/episodes")
async def upload(video: UploadFile=File(), metadata: str=Form(), poster: UploadFile|None=File(None), owner=Depends(current_user),db=Depends(get_db)):
    if not active(db,owner):raise HTTPException(409,"휴대폰 연결을 먼저 확인해 주세요.")
    try:
        info=json.loads(metadata)
        assert len(metadata)<200000 and isinstance(info,dict)
        assert isinstance(info['sha256'],str) and len(info['sha256'])==64 and all(c in '0123456789abcdef' for c in info['sha256'])
        assert isinstance(info['title'],str) and 0<len(info['title'])<=200
        assert isinstance(info['number'],int) and 0<info['number']<1000000000
        assert 0<int(info['durationMs'])<=125000
        assert isinstance(info.get('captions',[]),list) and len(info.get('captions',[]))<=2000
        captions=[]
        for caption in info.get('captions',[]):
            assert isinstance(caption,dict) and isinstance(caption.get('text'),str) and len(caption['text'])<=2000
            start=int(caption['startMs']);end=int(caption['endMs'])
            assert 0<=start<end<=int(info['durationMs'])+100
            captions.append({'startMs':start,'endMs':end,'text':caption['text']})
        info={name:info[name] for name in ('sha256','title','number','durationMs')}
        info['captions']=captions
    except (AssertionError,KeyError,TypeError,ValueError):raise HTTPException(422,"영상 정보를 확인해 주세요.")
    key=digest(owner+":"+info['sha256']);existing=db.get(PhoneEpisode,key)
    if existing:return {"id":key,"deleted":existing.deleted}
    dest=folder(owner);pending=dest/(key+"."+secrets.token_hex(8)+".pending")
    total=0;sha=hashlib.sha256()
    try:
        with pending.open('wb') as stream:
            while chunk:=await video.read(1024*1024):
                total+=len(chunk)
                if total>MAX_VIDEO:raise HTTPException(413,"완성 영상 용량이 너무 커요.")
                sha.update(chunk);stream.write(chunk)
        if sha.hexdigest()!=info['sha256']:raise HTTPException(422,"영상 전송을 다시 시도해 주세요.")
        probe=subprocess.run([settings.ffprobe,'-v','error','-show_entries','stream=codec_type:format=duration','-of','json',str(pending)],capture_output=True,timeout=15)
        try:
            data=json.loads(probe.stdout)
            assert probe.returncode==0 and any(s.get('codec_type')=='video' for s in data['streams']) and 0<float(data['format']['duration'])<=125
        except (AssertionError,KeyError,ValueError):raise HTTPException(422,"재생 가능한 완성 영상이 필요해요.")
        pending.replace(dest/(key+'.mp4'))
        if poster:
            content=await poster.read(4_000_001)
            if len(content)<=4_000_000 and content.startswith(b'\x89PNG\r\n\x1a\n'):(dest/(key+'.png')).write_bytes(content)
        info['createdAt']=time.time();db.add(PhoneEpisode(id=key,owner=owner,metadata_json=info));db.commit()
        return {"id":key,"deleted":False}
    finally:
        pending.unlink(missing_ok=True);await video.close()
        if poster:await poster.close()


@router.get("/phone/api/episodes")
def episodes(response: Response,d=Depends(connected),db=Depends(get_db)):
    response.headers['Cache-Control']='no-store'
    rows=db.scalars(select(PhoneEpisode).where(PhoneEpisode.owner==d.owner,PhoneEpisode.deleted.is_(False))).all()
    jobs=db.get(PhoneJobs,d.owner)
    return {"episodes":[{"id":r.id,**r.metadata_json} for r in sorted(rows,key=lambda r:r.metadata_json['number'],reverse=True)],"jobs":jobs.jobs if jobs else []}


def owned(key,owner,db):
    row=db.get(PhoneEpisode,key)
    if not row or row.owner!=owner or row.deleted:raise HTTPException(404)
    return row


@router.get("/phone/api/episodes/{key}/{kind}")
def media(key: str,kind: str,d=Depends(connected),db=Depends(get_db)):
    owned(key,d.owner,db)
    if kind not in ('video','poster','share'):raise HTTPException(404)
    if kind=='share':
        path=share_movie(owned(key,d.owner,db))
        return FileResponse(path,media_type='video/mp4',headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})
    path=folder(d.owner)/(key+('.mp4' if kind=='video' else '.png'))
    if not path.is_file():raise HTTPException(404)
    return FileResponse(path,media_type='video/mp4' if kind=='video' else 'image/png',headers={'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'})


@router.delete("/phone/api/episodes/{key}",dependencies=[Depends(same_origin)])
def delete(key: str,d=Depends(connected),db=Depends(get_db)):
    row=owned(key,d.owner,db);row.deleted=True;row.metadata_json={};db.commit()
    with _share_lock:
        for ext in ('.mp4','.png','.share.mp4','.share.ass'):(folder(d.owner)/(key+ext)).unlink(missing_ok=True)
    return {"deleted":True,"vehicleOriginalPreserved":True}


def share_movie(row):
    """Bake the supplied captions into an opt-in share copy, preserving the original audio stream."""
    dest=folder(row.owner);source=dest/(row.id+'.mp4');output=dest/(row.id+'.share.mp4')
    if not row.metadata_json.get('captions'):return source
    with _share_lock:
        if output.is_file():return output
        if not source.is_file():raise HTTPException(404)
        probe=subprocess.run([settings.ffprobe,'-v','error','-select_streams','v:0','-show_entries','stream=width,height','-of','json',str(source)],capture_output=True,timeout=15)
        stream=json.loads(probe.stdout)['streams'][0];width,height=int(stream['width']),int(stream['height'])
        font=dest/'AstaSans.ttf'
        if not font.exists():shutil.copyfile(STATIC/'assets/asta.ttf',font)
        subtitle=dest/(row.id+'.share.ass');size=max(18,round(height*.033))
        script=f'[Script Info]\nScriptType: v4.00+\nPlayResX: {width}\nPlayResY: {height}\nWrapStyle: 0\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\nStyle: Default,Asta Sans,{size},&H00FFFFFF,&H00FFFFFF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,2,0,2,24,24,{round(height*.08)},1\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n'
        def stamp(ms):
            centis=max(0,int(ms)//10)
            return f'{centis//360000}:{centis//6000%60:02}:{centis//100%60:02}.{centis%100:02}'
        for caption in row.metadata_json['captions']:
            try:
                start=max(0,int(caption['startMs']));end=min(int(row.metadata_json['durationMs']),int(caption['endMs']))
                text=str(caption['text'])[:2000].replace('\\','＼').replace('{','｛').replace('}','｝').replace('\r','').replace('\n','\\N')
                if end>start:script+=f'Dialogue: 0,{stamp(start)},{stamp(end)},Default,,0,0,0,,{text}\n'
            except (KeyError,TypeError,ValueError):continue
        subtitle.write_text(script,encoding='utf-8')
        pending=dest/(row.id+'.share.pending.mp4')
        try:
            result=subprocess.run([settings.ffmpeg,'-nostdin','-hide_banner','-loglevel','error','-y','-i',source.name,'-vf',f'ass={subtitle.name}:fontsdir=.','-c:v','libx264','-preset','veryfast','-crf','20','-c:a','copy','-movflags','+faststart',pending.name],cwd=dest,capture_output=True,timeout=180)
            if result.returncode!=0 or not pending.is_file():raise HTTPException(503,'공유 영상을 준비하지 못했어요. 다시 시도해 주세요.')
            pending.replace(output);return output
        except subprocess.TimeoutExpired:raise HTTPException(503,'공유 영상 준비가 지연되고 있어요. 다시 시도해 주세요.')
        finally:pending.unlink(missing_ok=True)


def install(app):
    app.include_router(router)
    app.mount('/phone',StaticFiles(directory=STATIC,html=True),name='phone')
