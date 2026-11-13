import hashlib
import json
from pathlib import Path
from fastapi.testclient import TestClient
from momo.api import app
from momo.db import SessionLocal
from momo.phone import Device, Pair
from sqlalchemy import select

PHONE={'X-Tiki-Client':'phone','Origin':'http://testserver'}

def link(client, headers):
    pair=client.post('/v1/phone/pair',headers=headers)
    assert pair.status_code==200,pair.text
    code=pair.json()['code']
    claim=client.post('/phone/api/claim',headers=PHONE,json={'code':code})
    assert claim.status_code==200,claim.text
    state=client.get('/v1/phone/state',headers=headers).json()
    device=state['devices'][-1]['id']
    return code,device

def upload(client,headers):
    path=Path(__file__).parents[2]/'app/src/androidTest/assets/pleos-playback.mp4'
    data=path.read_bytes()
    info={'sha256':hashlib.sha256(data).hexdigest(),'title':'함께 나눈 이야기','number':1,'durationMs':3000,'captions':[{'startMs':0,'endMs':1000,'text':'안녕'}]}
    response=client.post('/v1/phone/episodes',headers=headers,data={'metadata':json.dumps(info)},files={'video':('video.mp4',data,'video/mp4')})
    assert response.status_code==200,response.text
    return response.json()['id'],data

def test_approval_is_required_and_codes_are_single_use(client,users):
    _,h,_=users[0];code,d=link(client,h)
    assert client.get('/phone/api/episodes').status_code==403
    assert client.post('/phone/api/claim',headers=PHONE,json={'code':code}).status_code==400
    assert client.post(f'/v1/phone/devices/{d}/approve',headers=users[1][1]).status_code==404
    assert client.post(f'/v1/phone/devices/{d}/approve',headers=h).status_code==200
    assert client.get('/phone/api/episodes').status_code==200

def test_upload_range_delete_tombstone_and_owner_isolation(client,users):
    _,h,_=users[0];_,d=link(client,h)
    client.post(f'/v1/phone/devices/{d}/approve',headers=h)
    key,raw=upload(client,h)
    assert client.get('/phone/api/episodes').json()['episodes'][0]['title']=='함께 나눈 이야기'
    got=client.get(f'/phone/api/episodes/{key}/video',headers={'Range':'bytes=0-31'})
    assert got.status_code==206 and got.content==raw[:32]
    with TestClient(app) as stranger:
        _,d2=link(stranger,users[1][1]);stranger.post(f'/v1/phone/devices/{d2}/approve',headers=users[1][1])
        assert stranger.get(f'/phone/api/episodes/{key}/video').status_code==404
        assert stranger.delete(f'/phone/api/episodes/{key}',headers=PHONE).status_code==404
    assert client.delete(f'/phone/api/episodes/{key}',headers=PHONE).json()['vehicleOriginalPreserved']
    assert client.get('/phone/api/episodes').json()['episodes']==[]
    assert client.get(f'/phone/api/episodes/{key}/video').status_code==404
    upload(client,h)
    assert client.get('/phone/api/episodes').json()['episodes']==[]

def test_revocation_blocks_previously_authorized_phone(client,users):
    _,h,_=users[0];_,d=link(client,h);client.post(f'/v1/phone/devices/{d}/approve',headers=h)
    client.delete('/v1/phone/devices',headers=h)
    assert client.get('/phone/api/session').status_code==401
    assert client.get('/phone/api/episodes').status_code==401

def test_csrf_cookie_and_expiry(client,users):
    h=users[0][1];pair=client.post('/v1/phone/pair',headers=h).json()
    assert client.post('/phone/api/claim',json={'code':pair['code']}).status_code==403
    assert client.post('/phone/api/claim',headers={**PHONE,'Origin':'https://attacker.example'},json={'code':pair['code']}).status_code==403
    r=client.post('/phone/api/claim',headers=PHONE,json={'code':pair['code']})
    assert 'HttpOnly' in r.headers['set-cookie'] and 'SameSite=strict' in r.headers['set-cookie']
    with SessionLocal() as db:
        d=db.scalar(select(Device));d.expires=0;db.commit()
    assert client.get('/phone/api/session').status_code==401

def test_raw_audio_and_invalid_hash_rejected(client,users):
    h=users[0][1];_,d=link(client,h);client.post(f'/v1/phone/devices/{d}/approve',headers=h)
    data=b'not a finished video';info={'sha256':hashlib.sha256(data).hexdigest(),'number':1,'title':'x','durationMs':1000}
    response=client.post('/v1/phone/episodes',headers=h,data={'metadata':json.dumps(info)},files={'video':('voice.m4a',data,'audio/mp4')})
    assert response.status_code==422

def test_jobs_and_local_static_page(client,users):
    h=users[0][1];_,d=link(client,h);client.post(f'/v1/phone/devices/{d}/approve',headers=h)
    job=[{'state':'waiting','message':'기다리고 있어요'}]
    assert client.put('/v1/phone/jobs',headers=h,json=job).status_code==200
    assert client.get('/phone/api/episodes').json()['jobs']==job
    assert client.get('/phone/').status_code==200
    assert client.get('/phone/assets/logo.svg').status_code==200

def test_share_rendition_contains_captions_and_keeps_original(client,users):
    h=users[0][1];_,d=link(client,h);client.post(f'/v1/phone/devices/{d}/approve',headers=h)
    key,raw=upload(client,h)
    shared=client.get(f'/phone/api/episodes/{key}/share')
    assert shared.status_code==200,shared.text[:300] if shared.status_code!=200 else ''
    assert shared.content!=raw and len(shared.content)>1000
    assert client.get(f'/phone/api/episodes/{key}/video').content==raw
    assert client.get(f'/phone/api/episodes/{key}/share').content==shared.content
