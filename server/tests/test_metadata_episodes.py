from momo.episodes.metadata import Metadata, plan


def payload():
    return dict(schema_version="local-metadata-v1", consent_version="transcript-events-v1",
        source_sha256="a"*64, duration_ms=120000, stt_status="complete", detector="yamnet-local-v1",
        laughter_threshold=.1, captions=[dict(start_ms=1000, end_ms=5000, text="오늘 함께한 대화"),
            dict(start_ms=61000, end_ms=65000, text="다른 이야기")],
        laughter=[dict(start_ms=4000,end_ms=4500,score=.7), dict(start_ms=62000,end_ms=63000,score=.8)])


def test_metadata_requires_auth_and_is_stateless(client, users):
    assert client.post("/v1/episodes/metadata",json=payload()).status_code==401
    headers=users[0][1]
    first=client.post("/v1/episodes/metadata",json=payload(),headers=headers)
    assert first.status_code==200, first.text
    assert first.json()==client.post("/v1/episodes/metadata",json=payload(),headers=headers).json()
    result=first.json()
    assert result["speaker_identity"]=="unknown"
    assert len(result["clips"])==2
    assert all(c["end_ms"]-c["start_ms"]<=40000 for c in result["clips"])
    assert result["clips"][0]["captions"][0]["text"]=="오늘 함께한 대화"
    from momo.db import SessionLocal,Job
    from sqlalchemy import select
    with SessionLocal() as db:
        assert list(db.scalars(select(Job)))==[]


def test_rejects_audio_embeddings_bad_times_and_consent(client, users):
    for extra in (dict(audio="base64"),dict(pcm=[1,2]),dict(embedding=[.1]),dict(consent_version="server-audio-v1")):
        body=payload();body.update(extra)
        assert client.post("/v1/episodes/metadata",json=body,headers=users[0][1]).status_code==422
    for field,value in (("end_ms",120001),("start_ms",5000),("start_ms",-1)):
        body=payload();body["captions"][0][field]=value
        assert client.post("/v1/episodes/metadata",json=body,headers=users[0][1]).status_code==422
    body=payload();body["captions"].append(body["captions"][0])
    assert client.post("/v1/episodes/metadata",json=body,headers=users[0][1]).status_code==422
    assert client.post("/v1/episodes/metadata",content=b' '*4_000_001,headers={**users[0][1],"Content-Type":"application/json"}).status_code==413


def test_laughter_only_and_empty_do_not_invent_text_or_speakers():
    body=payload();body.update(captions=[],stt_status="unavailable")
    result=plan(Metadata(**body))
    assert result["clips"] and all(c["captions"]==[] for c in result["clips"])
    assert len(result["warnings"])==2
    body["laughter"]=[]
    assert plan(Metadata(**body))["clips"]==[]


def test_short_recording_bounds_and_maximum_120_seconds():
    body=payload();body.update(duration_ms=3000,captions=[],laughter=[dict(start_ms=100,end_ms=500,score=.9)],clip_seconds=120)
    clip=plan(Metadata(**body))["clips"][0]
    assert (clip["start_ms"],clip["end_ms"])==(0,3000)
    body.update(duration_ms=600000, laughter=[dict(start_ms=300000,end_ms=301000,score=.9)])
    clip=plan(Metadata(**body))["clips"][0]
    assert clip["end_ms"]-clip["start_ms"]==120000


def test_local_v2_detector_is_accepted_without_changing_audio_policy(client, users):
    body=payload()
    previous=client.post("/v1/episodes/metadata",json=body,headers=users[0][1])
    body["detector"]="yamnet-local-v2"
    current=client.post("/v1/episodes/metadata",json=body,headers=users[0][1])
    assert previous.status_code==current.status_code==200
    assert previous.json()["plan_id"]!=current.json()["plan_id"]
    body["audio"]="never-upload"
    assert client.post("/v1/episodes/metadata",json=body,headers=users[0][1]).status_code==422
