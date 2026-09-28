"""Report binary app fixture results against the source model, by output head."""
import argparse
import json
from pathlib import Path
import numpy as np
import hashlib

parser = argparse.ArgumentParser()
parser.add_argument('--bench', type=Path, required=True)
parser.add_argument('--runtime', type=Path, help='Device-specific report directory')
args = parser.parse_args()
runtime = args.runtime or args.bench / 'tensor-sdk/runtime'
root = Path(__file__).resolve().parent
spec = json.loads((root / 'app/src/main/assets/model.json').read_text())['spec']
actual = np.fromfile(runtime / 'app-fixture-output.bin', dtype='<f4')
reference = np.load(args.bench / 'onnx-output.npy').reshape(-1).astype(np.float32)
host = np.load(args.bench / 'host-tflite-output.npy').reshape(-1).astype(np.float32)
assert actual.size == reference.size == host.size == 18452
hashes = json.loads((runtime / 'app-fixture-input-hashes.json').read_text())
assert all(hashlib.sha256((args.bench / 'tensor-sdk/runtime/driving-input-padded' / f'{name}.raw').read_bytes()).hexdigest() == digest for name, digest in hashes.items())
last = np.fromfile(runtime / 'app-fixture-output-last.bin', dtype='<f4')
diff = np.abs(actual - reference)
report = {'fixture': 'synthetic seed 745, not recorded driving', 'finite': bool(np.isfinite(actual).all()),
          'input_file_hashes_match': True, 'first_last_output_exact': bool(np.array_equal(actual, last)),
          'max_abs': float(diff.max()), 'mean_abs': float(diff.mean()),
          'historical_runner_comparison_invalid': 'Runner input dump shows traffic_convention=[0,0] rather than fixture [1,0]. The official input helper discards the status returned by padded tensor writes.',
          'heads': {}}
for name, (start, stop) in spec['output_slices'].items():
    diff = np.abs(actual[start:stop] - reference[start:stop])
    report['heads'][name] = {'max_abs': float(diff.max()), 'mean_abs': float(diff.mean()),
                           'source_max_abs': float(np.max(np.abs(reference[start:stop]))),
                           'converted_cpu_max_abs': float(np.max(np.abs(host[start:stop] - reference[start:stop])))}
(runtime / 'app-fixture-comparison.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
