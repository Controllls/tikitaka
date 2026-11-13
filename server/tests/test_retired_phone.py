import pytest


@pytest.mark.parametrize("method,path", [
    ("GET", "/phone/"), ("GET", "/phone/assets/logo.svg"),
    ("POST", "/v1/phone/pair"), ("GET", "/v1/phone/state"),
    ("POST", "/v1/phone/devices/old/approve"), ("DELETE", "/v1/phone/devices"),
    ("PUT", "/v1/phone/jobs"), ("POST", "/v1/phone/episodes"),
    ("POST", "/phone/api/claim"), ("GET", "/phone/api/session"),
    ("DELETE", "/phone/api/session"), ("GET", "/phone/api/episodes"),
    ("GET", "/phone/api/episodes/old/video"), ("GET", "/phone/api/episodes/old/share"),
    ("DELETE", "/phone/api/episodes/old"),
])
def test_phone_feature_is_no_longer_served(client, method, path):
    # No pairing, upload, streaming or public website survives removal, even with old cookies.
    response = client.request(method, path, headers={"Cookie": "tiki_phone=old-session"})
    assert response.status_code == 404
