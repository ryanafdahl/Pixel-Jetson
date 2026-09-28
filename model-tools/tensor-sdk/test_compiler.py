"""Prepare a deterministic smoke model and probe the installed Tensor compiler.

Runs the real public LiteRT compiler. A missing SDK is reported as a failure,
never treated as a successful compile or an accelerator benchmark.
"""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import ai_edge_litert
from ai_edge_litert import schema_py_generated as s
from ai_edge_litert.interpreter import Interpreter
import flatbuffers
import numpy as np

ROOT = Path(__file__).resolve().parent
PACKAGE = Path(ai_edge_litert.__file__).parent


def make_smoke():
    model = s.ModelT()
    model.version = 3
    model.description = b'JetLink Tensor G5 compiler smoke test; Conv2D y=2*x+0.5'
    model.buffers = [s.BufferT(), s.BufferT(), s.BufferT()]
    model.buffers[1].data = np.frombuffer(np.array([2.0], dtype=np.float32).tobytes(), dtype=np.uint8)
    model.buffers[2].data = np.frombuffer(np.array([0.5], dtype=np.float32).tobytes(), dtype=np.uint8)
    graph = s.SubGraphT()
    graph.name = b'main'
    graph.tensors = []
    for name, shape, buffer in [('input',[1,8,8,1],0),('weights',[1,1,1,1],1),('bias',[1],2),('output',[1,8,8,1],0)]:
        t = s.TensorT()
        t.name, t.shape, t.buffer, t.type = name.encode(), shape, buffer, s.TensorType.FLOAT32
        graph.tensors.append(t)
    graph.inputs, graph.outputs = [0], [3]
    code = s.OperatorCodeT()
    code.builtinCode = code.deprecatedBuiltinCode = s.BuiltinOperator.CONV_2D
    code.version = 1
    model.operatorCodes = [code]
    op = s.OperatorT()
    op.opcodeIndex, op.inputs, op.outputs = 0, [0,1,2], [3]
    options = s.Conv2DOptionsT()
    options.padding = s.Padding.VALID
    options.strideW = options.strideH = options.dilationWFactor = options.dilationHFactor = 1
    options.fusedActivationFunction = s.ActivationFunctionType.NONE
    op.builtinOptionsType, op.builtinOptions = s.BuiltinOptions.Conv2DOptions, options
    graph.operators = [op]
    model.subgraphs = [graph]
    builder = flatbuffers.Builder(1024)
    offset = model.Pack(builder)
    builder.Finish(offset, file_identifier=b'TFL3')
    path = ROOT/'smoke.tflite'
    path.write_bytes(builder.Output())
    interpreter = Interpreter(model_path=str(path), num_threads=1)
    interpreter.allocate_tensors()
    value = np.linspace(-1,1,64,dtype=np.float32).reshape(1,8,8,1)
    interpreter.set_tensor(0,value)
    interpreter.invoke()
    actual = interpreter.get_tensor(3)
    np.testing.assert_allclose(actual, 2*value+0.5, atol=1e-6)
    value.tofile(ROOT/'smoke-input.bin')
    actual.tofile(ROOT/'smoke-expected.bin')
    return path


def run_stage(stage, model, timeout, truncation=None, soc='Tensor_G5'):
    suffix = f'-{truncation}' if truncation else ''
    output = ROOT/f'{model.stem}-{stage}{suffix}-{soc}.tflite'
    report_suffix = '' if soc == 'Tensor_G5' else f'-{soc}'
    # Use a distinct output per stage. A stale artifact is never evidence of success.
    args = [str(PACKAGE/'tools/apply_plugin_main'), f'--cmd={stage}',
            f'--libs={PACKAGE / "vendors/google_tensor/compiler"}',
            '--soc_manufacturer=Google', f'--soc_model={soc}',
            f'--model={model}', f'--o={output}', '--err=--']
    if truncation:
        args.append(f'--google_tensor_truncation_type={truncation}')
    env = os.environ.copy()
    sdk_path = None
    try:
        import ai_edge_litert_sdk_google_tensor as sdk
        sdk_path = str(sdk.path_to_sdk_libs())
        env['LD_LIBRARY_PATH'] = sdk_path + os.pathsep + env.get('LD_LIBRARY_PATH','')
    except ImportError:
        pass
    started = time.time()
    try:
        proc = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, timeout=timeout, env=env)
        code, log = proc.returncode, proc.stdout
    except subprocess.TimeoutExpired as exc:
        code = 124
        log = exc.stdout or b''
        if isinstance(log, bytes):
            log = log.decode(errors='replace')
        log += '\nTIMEOUT: compiler was stopped.\n'
    (ROOT/f'{model.stem}-{stage}{suffix}{report_suffix}.log').write_text(log)
    fresh_output = output.exists() and output.stat().st_mtime >= started - 1
    output_bytes = output.stat().st_size if output.exists() else 0
    report = {'command': args, 'exit_code': code, 'seconds': time.time()-started,
              'sdk_libs_path': sdk_path, 'fresh_output': fresh_output,
              'output_bytes': output_bytes,
              'success': code == 0 and fresh_output and output_bytes > 0}
    (ROOT/f'{model.stem}-{stage}{suffix}{report_suffix}.json').write_text(json.dumps(report,indent=2))
    print(log[-6000:], flush=True)
    print(json.dumps(report,indent=2), flush=True)
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--stage', choices=['partition','apply'], default='partition')
    parser.add_argument('--soc', choices=['Tensor_G5', 'Tensor_G6'], default='Tensor_G5')
    parser.add_argument('--driving', action='store_true')
    parser.add_argument('--model', type=Path, help='Explicit TFLite model to compile')
    parser.add_argument('--truncation', choices=['half'], help='Explicit FP16 compilation; preserve default artifacts separately')
    args = parser.parse_args()
    smoke = make_smoke()
    print('Smoke model CPU check passed (y=2*x+0.5).', flush=True)
    (ROOT/'environment.json').write_text(json.dumps({'python':sys.version,
        'litert':importlib.metadata.version('ai-edge-litert-nightly'),
        'platform':sys.platform, 'package':str(PACKAGE)},indent=2))
    selected_model = args.model.resolve(strict=True) if args.model else ROOT.parent/'model.tflite' if args.driving else smoke
    report = run_stage(args.stage, selected_model, 180 if args.driving or args.model else 60, args.truncation, args.soc)
    return 0 if report['success'] else 2

if __name__ == '__main__':
    raise SystemExit(main())
