"""Prepare local licensed assets; neither the model nor vendor binaries enter git."""
import argparse
import hashlib
import json
import mmap
from pathlib import Path
import shutil
import sys
import os

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, os.environ['JETLINK_REPO'])
from jetlink.spec import spec_from_onnx
from ai_edge_litert import schema_py_generated as schema

parser = argparse.ArgumentParser()
parser.add_argument('--bench', type=Path, required=True)
parser.add_argument('--onnx', type=Path, required=True, help='Original unmodified TGCv2 ONNX')
parser.add_argument('--dispatch-lib', type=Path, required=True, help='LiteRT 2.2.0 arm64 Google Tensor dispatch .so')
parser.add_argument('--soc', choices=['Tensor_G5', 'Tensor_G6'], default='Tensor_G5')
args = parser.parse_args()
compiled = args.bench / f'model-static-shapes-apply-{args.soc}.tflite'
original = args.onnx.resolve(strict=True)
spec = spec_from_onnx(str(original))
with compiled.open('rb') as f, mmap.mmap(f.fileno(), 0, access=mmap.ACCESS_READ) as data:
    model = schema.Model.GetRootAsModel(data, 0)
    graph = model.Subgraphs(0)
    inputs = [graph.Tensors(int(i)).Name().decode() for i in graph.InputsAsNumpy()]
    outputs = [graph.Tensors(int(i)).Name().decode() for i in graph.OutputsAsNumpy()]
    assert graph.OperatorsLength() == 1, 'Require one compiled TPU dispatch'
    print('Input order:', inputs, 'Output order:', outputs)
    assert set(inputs) == set(spec.input_shapes)
manifest = {'spec': spec.to_dict(), 'input_order': inputs, 'output_order': outputs,
            'compiled_bytes': compiled.stat().st_size,
            'compiled_sha256': hashlib.file_digest(compiled.open('rb'), 'sha256').hexdigest(),
            'validation': 'parked_only', 'runtime': '2.2.0', 'soc': args.soc.replace('_', ' ')}
assets = ROOT / 'app/src/main/assets'
assets.mkdir(parents=True, exist_ok=True)
(assets / 'model.json').write_text(json.dumps(manifest, indent=2))
libs = ROOT / 'app/src/main/jniLibs/arm64-v8a'
libs.mkdir(parents=True, exist_ok=True)
shutil.copy2(args.dispatch_lib, libs / 'libLiteRtDispatch_GoogleTensor.so')
print('Prepared metadata and runtime library; APK excludes the large model.')
