"""Deterministic synthetic smoke fixture, not a recorded-driving validation."""
import json
import pathlib
import time
import numpy as np
import onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter

out = pathlib.Path(__file__).resolve().parent
converted = out / 'model.tflite'
itp = Interpreter(model_path=str(converted), num_threads=4)
itp.allocate_tensors()
details = [{'name': d['name'], 'shape': d['shape'].tolist(), 'dtype': str(d['dtype'])} for d in itp.get_input_details()]
print(json.dumps(details, indent=2), flush=True)
(out / 'tflite-inputs.json').write_text(json.dumps(details, indent=2))
rng = np.random.default_rng(745)
fixtures = {}
for d in itp.get_input_details():
    name, shape = d['name'], tuple(d['shape'])
    if name in ('img', 'big_img'):
        a = rng.integers(0, 256, size=shape, dtype=np.uint8)
    elif name == 'features_buffer':
        a = rng.normal(0, 0.1, size=shape)
    else:
        a = np.zeros(shape)
        if name == 'traffic_convention':
            a.flat[0] = 1
    a = a.astype(d['dtype'])
    fixtures[name] = a
    a.tofile(out / (name + '.bin'))
    itp.set_tensor(d['index'], a)
np.savez(out / 'fixture.npz', **fixtures)
t = time.perf_counter()
itp.invoke()
converted_output = itp.get_tensor(itp.get_output_details()[0]['index'])
np.save(out / 'host-tflite-output.npy', converted_output)
print('host LiteRT seconds', time.perf_counter() - t, 'finite', bool(np.isfinite(converted_output).all()), flush=True)
del itp
opts = ort.SessionOptions()
opts.intra_op_num_threads = 4
sess = ort.InferenceSession(str(out / 'portable.onnx'), sess_options=opts, providers=['CPUExecutionProvider'])
dt = {'tensor(float16)': np.float16, 'tensor(float)': np.float32, 'tensor(uint8)': np.uint8}
inputs = {d.name: fixtures[d.name].astype(dt[d.type]) for d in sess.get_inputs()}
t = time.perf_counter()
reference = sess.run(None, inputs)[0]
np.save(out / 'onnx-output.npy', reference)
diff = np.abs(reference.astype(np.float64) - converted_output.astype(np.float64))
report = {'fixture': 'synthetic seed 745; not driving replay', 'reference_seconds': time.perf_counter()-t,
          'reference_finite': bool(np.isfinite(reference).all()), 'tflite_finite': bool(np.isfinite(converted_output).all()),
          'shape': list(reference.shape), 'max_absolute_error': float(diff.max()), 'mean_absolute_error': float(diff.mean()),
          'correlation': float(np.corrcoef(reference.flatten(), converted_output.flatten())[0,1])}
(out / 'host-parity.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2), flush=True)
