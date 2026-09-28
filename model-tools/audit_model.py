"""Inspect the existing driving model; prepare a separate portable ONNX copy."""
import collections
import hashlib
import json
import pathlib
import sys
import os
import argparse
import onnx

sys.path.insert(0, os.environ['JETLINK_REPO'])
from jetlink.onnx_patch import strip_tinygrad_ops

parser = argparse.ArgumentParser()
parser.add_argument('--onnx', type=pathlib.Path, required=True)
args = parser.parse_args()
source = args.onnx.resolve(strict=True)
out = pathlib.Path(__file__).resolve().parent
model = onnx.load(str(source))
def tensor_info(v):
    t = v.type.tensor_type
    return {'name': v.name, 'dtype': onnx.TensorProto.DataType.Name(t.elem_type),
            'shape': [d.dim_value or d.dim_param for d in t.shape.dim]}
report = {'source': str(source), 'size': source.stat().st_size,
          'sha256': hashlib.file_digest(source.open('rb'), 'sha256').hexdigest(),
          'inputs': [tensor_info(v) for v in model.graph.input],
          'outputs': [tensor_info(v) for v in model.graph.output],
          'operators': dict(collections.Counter((n.domain + ':' + n.op_type) for n in model.graph.node)),
          'opsets': {o.domain: o.version for o in model.opset_import},
          'metadata': {p.key:p.value for p in model.metadata_props}}
report['removed_layout_hints'] = strip_tinygrad_ops(model)
onnx.checker.check_model(model)
onnx.save(model, str(out / 'portable.onnx'))
(out / 'model-audit.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
