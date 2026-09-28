"""Normalize constant negative GatherND indices without changing their meaning."""
from pathlib import Path
import json
import numpy as np
from ai_edge_litert import schema_py_generated as schema

path = Path(__file__).resolve().parent / 'model.tflite'
data = bytearray(path.read_bytes())
m = schema.Model.GetRootAsModel(data, 0)
g = m.Subgraphs(0)
changes = []
for i in range(g.OperatorsLength()):
    op = g.Operators(i)
    if m.OperatorCodes(op.OpcodeIndex()).BuiltinCode() != schema.BuiltinOperator.GATHER_ND:
        continue
    source = g.Tensors(op.Inputs(0))
    indices = g.Tensors(op.Inputs(1))
    raw = m.Buffers(indices.Buffer()).DataAsNumpy()
    dtype = {schema.TensorType.INT32: np.int32, schema.TensorType.INT64: np.int64}[indices.Type()]
    values = raw.view(dtype).reshape(indices.ShapeAsNumpy())
    original = values.copy()
    for axis in range(values.shape[-1]):
        size = source.Shape(axis)
        if size <= 0 or np.any(values[..., axis] < -size) or np.any(values[..., axis] >= size):
            raise ValueError('Cannot safely normalize out-of-bounds GatherND indices')
        values[..., axis] = np.where(values[..., axis] < 0, values[..., axis] + size, values[..., axis])
    changes.append({'operator': i, 'source_shape': source.ShapeAsNumpy().tolist(),
                    'before': original.tolist(), 'after': values.tolist()})
path.write_bytes(data)
(path.parent / 'gather-fix.json').write_text(json.dumps(changes, indent=2))
print(json.dumps(changes))
