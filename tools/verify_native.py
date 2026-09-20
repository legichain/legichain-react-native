"""Check packaged native sources against the release manifest (Python 3)."""
from pathlib import Path
import hashlib
import json

root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "native-source.json").read_text(encoding="utf-8"))
for name, expected in manifest["sha256"].items():
    path = (root / name).resolve()
    if root not in path.parents:
        raise SystemExit("Manifest path escapes the package")
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit("Native source drift: " + name)
print(f"Verified {len(manifest['sha256'])} native sources and resources")
