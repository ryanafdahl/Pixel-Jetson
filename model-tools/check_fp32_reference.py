"""Separate precision-change error from LiteRT conversion error on one fixture."""
from pathlib import Path
import json
import numpy as np
import onnxruntime as ort
root = Path(__file__).resolve().parent
opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
sess = ort.InferenceSession(str(root / 'portable-fp32.onnx'), sess_options=opts, providers=['CPUExecutionProvider'])
fixture = np.load(root / 'fixture.npz')
inputs = {i.name: fixture[i.name] for i in sess.get_inputs()}
value = sess.run(None, inputs)[0]
np.save(root / 'onnx-fp32-output.npy', value)
host = np.load(root / 'host-tflite-output.npy')
diff = np.abs(value.astype(np.float64)-host.astype(np.float64))
report = {'scope': 'single synthetic fixture', 'finite': bool(np.isfinite(value).all()),
          'max_absolute_error_vs_host_tflite': float(diff.max()),
          'mean_absolute_error_vs_host_tflite': float(diff.mean())}
(root / 'fp32-conversion-comparison.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
