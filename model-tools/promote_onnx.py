"""Promote FP16 arithmetic to FP32 for LiteRT CPU/GPU kernel compatibility.

Original ONNX remains the reference. This changes arithmetic precision and must
be validated; it is not asserted to be bit-exact.
"""
from pathlib import Path
import numpy as np
import onnx
from onnx import TensorProto, numpy_helper

root = Path(__file__).resolve().parent
m = onnx.load(str(root / 'portable.onnx'))
def promote_tensor(t):
    if t.data_type == TensorProto.FLOAT16:
        t.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(t).astype(np.float32), t.name))
def promote_graph(g):
    for t in g.initializer:
        promote_tensor(t)
    for v in list(g.input) + list(g.output) + list(g.value_info):
        if v.type.tensor_type.elem_type == TensorProto.FLOAT16:
            v.type.tensor_type.elem_type = TensorProto.FLOAT
    for n in g.node:
        for a in n.attribute:
            if n.op_type == 'Cast' and a.name == 'to' and a.i == TensorProto.FLOAT16:
                a.i = TensorProto.FLOAT
            if a.type == onnx.AttributeProto.TENSOR:
                promote_tensor(a.t)
            if a.type == onnx.AttributeProto.GRAPH:
                promote_graph(a.g)
promote_graph(m.graph)
onnx.checker.check_model(m)
onnx.save(m, str(root / 'portable-fp32.onnx'))
print('Saved FP32 arithmetic model; source unchanged.', flush=True)
