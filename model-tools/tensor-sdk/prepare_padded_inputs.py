"""Add only trailing allocation padding required by the Tensor dispatch runtime.

Logical input bytes are copied unchanged from the original deterministic fixture.
The Android app reads logical values from these files. Do not use run_model's
padded scalar input loader for numerical validation: it silently failed writes
in the original investigation.
"""
import json
from pathlib import Path
import numpy as np

root = Path(__file__).resolve().parent
fixture = np.load(root.parent / 'fixture.npz')
dest = root / 'runtime' / 'driving-input-padded'
dest.mkdir(parents=True, exist_ok=True)
report = {}
for name in ['img', 'big_img', 'desire_pulse', 'traffic_convention', 'action_t', 'features_buffer']:
    raw = fixture[name].tobytes()
    padding = (-len(raw)) % 64
    (dest / f'{name}.raw').write_bytes(raw + bytes(padding))
    report[name] = {'shape': list(fixture[name].shape), 'dtype': str(fixture[name].dtype),
                    'logical_bytes': len(raw), 'allocation_bytes': len(raw) + padding}
(dest / 'manifest.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
