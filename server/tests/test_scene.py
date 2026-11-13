import pytest
from pydantic import ValidationError
from momo.contracts import SceneProject


def test_local_override_null_empty_multiple_remain_distinct():
    project = SceneProject.model_validate(dict(project_id="fixture-local", title="sample",
        audio_local_ref="private/original.wav", source_audio_sha256="0"*64, duration_us=3000000,
        people=[dict(person_id="P1",name="one",seat="driver"), dict(person_id="P2",name="two",seat="front_passenger")],
        event_overrides=[dict(event_id="auto",person_ids=None), dict(event_id="stop",person_ids=[]),
                         dict(event_id="shared",person_ids=["P1","P2"])]))
    restored = SceneProject.model_validate_json(project.model_dump_json())
    assert [o.person_ids for o in restored.event_overrides] == [None, [], ["P1", "P2"]]
    duplicate = project.model_dump()
    duplicate["people"][1]["seat"] = "driver"
    with pytest.raises(ValidationError, match="Duplicate"):
        SceneProject.model_validate(duplicate)
