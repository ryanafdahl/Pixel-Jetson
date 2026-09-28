"""Compare phone-produced history hashes with the original JetLink implementation."""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import os
import numpy as np

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, os.environ['JETLINK_REPO'])
from jetlink.spec import ModelSpec
from jetlink.queues import PolicyQueues

parser = argparse.ArgumentParser()
parser.add_argument('phone_report', type=Path)
args = parser.parse_args()
spec = ModelSpec.from_dict(json.loads((ROOT / 'app/src/main/assets/model.json').read_text())['spec'])
queues = PolicyQueues(spec)
actual = {row['frame']: row for row in json.loads(args.phone_report.read_text())}
assert set(actual) == {1, 4, 5, 128, 132, 140, 141, 142, 150}
checked = 0
for frame in range(1, 151):
    if frame == 141:
        queues.reset()
    pixels = np.arange(6 * 128 * 256, dtype=np.int32)
    warped = np.stack(((frame + pixels).astype(np.uint8), (3 * frame + pixels).astype(np.uint8))).reshape(spec.warped_shape)
    desire = ((frame + np.arange(8)) % 11 == 0).astype(np.float32)
    scalars = np.array([1, 0, np.float32(frame) / np.float32(7), -np.float32(frame) / np.float32(11)], np.float32)
    features = ((frame * 37 + np.arange(16384)) % 10007).astype(np.float32) / np.float32(1000) - np.float32(5)
    result = queues.step(warped, np.concatenate((desire, scalars, features)))
    if frame in actual:
        for name, values in result.items():
            data = values.astype(np.uint8 if name in ('img', 'big_img') else '<f4').tobytes()
            expected = hashlib.sha256(data).hexdigest()
            assert actual[frame][name] == expected, (frame, name, expected, actual[frame][name])
            checked += 1
print(json.dumps({'passed': True, 'frames': 150, 'reset_at': 141, 'tensor_hashes_checked': checked}))
