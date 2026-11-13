"""Run with server/.venv Python from the repository root."""
import json
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "server"))
from momo.contracts import AnalysisResult, MotionTracks, SceneProject  # noqa: E402
from momo.highlights.contracts import HighlightResult  # noqa: E402
from momo.api import app  # noqa: E402

for name, model in [("analysis-result", AnalysisResult), ("motion-tracks", MotionTracks), ("scene-project", SceneProject),
                    ("highlight-result", HighlightResult)]:
    (root / "contracts" / f"{name}.schema.json").write_text(json.dumps(model.model_json_schema(), ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
(root / "contracts/openapi.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
