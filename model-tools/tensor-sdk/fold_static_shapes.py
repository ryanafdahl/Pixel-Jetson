"""Specialize SHAPE reads for the driving model's fixed input signature.

Checks serialized and allocated shapes, proves annotated dynamic slices from
constant slice parameters and a static parent, and
compares CPU outputs on three synthetic fixtures before accepting the artifact.
Does not establish equivalence to the source ONNX model or driving suitability.
"""
import gc
import json
import mmap
from pathlib import Path
import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.interpreter import Interpreter

root = Path(__file__).resolve().parent
source = root.parent / 'model.tflite'
destination = root / 'model-static-shapes.tflite'
original_fixture = dict(np.load(root.parent / 'fixture.npz'))
fixtures = [original_fixture]
for seed in [746, 747]:
    rng = np.random.default_rng(seed)
    sample = {name: value.copy() for name, value in original_fixture.items()}
    for name in ['img', 'big_img']:
        sample[name] = rng.integers(0, 256, size=sample[name].shape, dtype=np.uint8)
    sample['features_buffer'] = rng.normal(0, 0.1, size=sample['features_buffer'].shape).astype(np.float32)
    fixtures.append(sample)

def execute(model):
    interpreter = Interpreter(model_path=str(model), num_threads=4)
    interpreter.allocate_tensors()
    allocated = {d['index']: d['shape'].tolist() for d in interpreter.get_tensor_details()}
    result = []
    for fixture in fixtures:
        for d in interpreter.get_input_details():
            interpreter.set_tensor(d['index'], fixture[d['name']])
        interpreter.invoke()
        result.append(interpreter.get_tensor(interpreter.get_output_details()[0]['index']))
    return allocated, result

allocated, baseline = execute(source)
print('Captured three baseline CPU outputs.', flush=True)
gc.collect()
with source.open('rb') as handle:
    data = mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ)
    model = s.ModelT.InitFromObj(s.Model.GetRootAsModel(data, 0))
    assert len(model.subgraphs) == 1
    graph = model.subgraphs[0]
    producers = {int(t): op for op in graph.operators for t in op.outputs}
    remaining = []
    changes = []
    for index, op in enumerate(graph.operators):
        if model.operatorCodes[op.opcodeIndex].builtinCode != s.BuiltinOperator.SHAPE:
            remaining.append(op)
            continue
        source_tensor = graph.tensors[op.inputs[0]]
        output_tensor = graph.tensors[op.outputs[0]]
        shape = list(map(int, source_tensor.shape))
        assert shape and all(x > 0 for x in shape)
        proof = 'static shape signature'
        if source_tensor.shapeSignature is not None and any(x < 0 for x in source_tensor.shapeSignature):
            # onnx2tf marked these SLICE outputs dynamic despite constant begin
            # and size and a fully static parent. Prove their actual shape.
            producer = producers[int(op.inputs[0])]
            assert model.operatorCodes[producer.opcodeIndex].builtinCode == s.BuiltinOperator.SLICE
            parent = graph.tensors[producer.inputs[0]]
            parent_shape = np.asarray(parent.shape, dtype=np.int64)
            if parent.shapeSignature is not None:
                np.testing.assert_array_equal(parent.shapeSignature, parent_shape)
            assert np.all(parent_shape > 0)
            constants = []
            for tensor_id in producer.inputs[1:]:
                param = graph.tensors[tensor_id]
                kind = {s.TensorType.INT32: np.int32, s.TensorType.INT64: np.int64}[param.type]
                raw = model.buffers[param.buffer].data
                assert raw is not None and len(raw) > 0, 'Slice parameter is not constant'
                constants.append(np.asarray(raw).view(kind).astype(np.int64))
            begin, size = constants
            assert len(begin) == len(size) == len(shape)
            assert np.all(begin >= 0) and np.all(begin < parent_shape)
            derived = np.where(size == -1, parent_shape - begin, size)
            assert np.all(derived > 0) and np.all(begin + derived <= parent_shape)
            np.testing.assert_array_equal(derived, shape)
            proof = 'constant SLICE begin/size with fully static parent'
        assert shape == allocated[int(op.inputs[0])], 'Serialized/allocated shape mismatch'
        assert not source_tensor.isVariable
        dtype = {s.TensorType.INT32: np.int32, s.TensorType.INT64: np.int64}[output_tensor.type]
        values = np.asarray(shape, dtype=dtype)
        assert list(output_tensor.shape) == [len(shape)]
        buffer = s.BufferT()
        buffer.data = values.view(np.uint8)
        output_tensor.buffer = len(model.buffers)
        model.buffers.append(buffer)
        changes.append({'operator': index, 'source': source_tensor.name.decode(), 'fixed_shape': shape, 'proof': proof})
    assert len(changes) == 24, f'Expected audited 24 SHAPE ops, found {len(changes)}'
    graph.operators = remaining
    builder = flatbuffers.Builder(source.stat().st_size + 1024 * 1024)
    offset = model.Pack(builder)
    builder.Finish(offset, file_identifier=b'TFL3')
    with destination.open('wb') as out:
        out.write(memoryview(builder.Bytes)[builder.Head():])
    del builder, model, graph, remaining, op, source_tensor, output_tensor, buffer, producers, producer, parent, param, raw
    gc.collect()
    data.close()
print('Saved fixed-shape model; validating CPU outputs.', flush=True)
_, actual = execute(destination)
validation = []
for index, (expected, observed) in enumerate(zip(baseline, actual)):
    assert np.isfinite(observed).all()
    delta = np.abs(expected - observed)
    validation.append({'fixture_seed': 745 + index, 'max_abs': float(delta.max()),
                       'mean_abs': float(delta.mean()), 'exact_equal': bool(np.array_equal(expected, observed))})
    np.testing.assert_allclose(observed, expected, rtol=1e-5, atol=1e-5)
report = {'source': str(source), 'output': str(destination), 'folded': changes,
          'validation': validation, 'success': True}
(root / 'static-shape-validation.json').write_text(json.dumps(report, indent=2))
print(json.dumps(validation, indent=2), flush=True)
