"""Local ADB-forward protocol test. No connection to vehicle control processes."""
import argparse
import hashlib
import json
from pathlib import Path
import socket
import struct
import sys
import os
import time
import numpy as np

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, os.environ['JETLINK_REPO'])
from jetlink import protocol as p
from jetlink.spec import ModelSpec

parser = argparse.ArgumentParser()
parser.add_argument('--port', type=int, default=8765)
parser.add_argument('--frames', type=int, default=60)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--duration-seconds', type=int, default=0, help='Optional time cap, up to two hours')
parser.add_argument('--state-every', type=int, default=1000, help='Checkpoint and thermal sampling interval in frames')
args = parser.parse_args()
if not 10 <= args.frames <= 200000 or not 0 <= args.duration_seconds <= 7200 or not 1 <= args.state_every <= 1000:
    parser.error('frames 10..200000, duration-seconds 0..7200, state-every 1..1000 required')
spec = ModelSpec.from_dict(json.loads((ROOT / 'app/src/main/assets/model.json').read_text())['spec'])
args.output.mkdir(parents=True, exist_ok=True)
sock = socket.create_connection(('127.0.0.1', args.port), timeout=30)
sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
sequence = 0

def exact(length):
    result = bytearray()
    while len(result) < length:
        chunk = sock.recv(length - len(result))
        if not chunk:
            raise EOFError('Pixel disconnected')
        result.extend(chunk)
    return bytes(result)

def exchange(kind, payload=b'', *, repeat=False):
    global sequence
    if not repeat:
        sequence += 1
    padding = int((32 + len(payload)) % 1024 == 0)
    start = time.perf_counter()
    sock.sendall(p.pack_header(kind, sequence, len(payload), 128 if padding else 0) + payload + bytes(padding))
    _, _, msgtype, seq, flags, length, _ = p.unpack_header(exact(32))
    assert seq == sequence
    data = exact(length)
    if flags & 128:
        exact(1)
    return msgtype, data, (time.perf_counter() - start) * 1000

def request(kind, data):
    kind, reply, _ = exchange(kind, json.dumps(data).encode())
    return kind, json.loads(reply)

try:
    _, hello = request(p.Msg.HELLO_REQ, {})
    assert hello['protocol'] == 2
    initial_power = hello.get('power', {})
    if initial_power.get('thermal_status', 0) >= 3 or (initial_power.get('battery_temperature_c') or 0) >= 45:
        raise RuntimeError('Thermal guard: phone is already too warm for a desk load test')
    engine = {'sha256': spec.sha256, 'nbytes': spec.nbytes, 'frame_skip': 4}
    _, blocked = request(p.Msg.ENGINE_REQ, engine)
    assert blocked['state'] == 'failed', 'Ordinary driving client must be refused'
    _, wrong = request(p.Msg.ENGINE_REQ, dict(engine, validation_mode='parked', sha256='0' * 64))
    assert wrong['state'] == 'failed'
    _, ready = request(p.Msg.ENGINE_REQ, dict(engine, validation_mode='parked'))
    assert ready['state'] == 'ready' and ready['spec'] == spec.to_dict()
    timings = []
    duplicate_checked = False
    # Synthetic fixed input, with the output feature slice fed recurrently.
    warped = np.zeros(spec.warped_shape, np.uint8)
    packed = np.zeros(spec.packed_nelem, np.float32)
    packed[8] = 1
    started = time.monotonic()
    checkpoints = []
    stop_reason = 'frame_limit'
    for frame in range(args.frames):
        if frame >= 10 and args.duration_seconds and time.monotonic() - started >= args.duration_seconds:
            stop_reason = 'duration_reached'
            break
        payload = p.pack_infer_req(frame, int(p.Flag.RESET_QUEUES) if frame == 0 else 0) + warped.tobytes() + packed.tobytes()
        kind, reply, roundtrip = exchange(p.Msg.INFER_REQ, payload)
        assert kind == p.Msg.INFER_RESP
        frame_id, status, infer_us, queue_us, total_us = p.unpack_infer_resp(reply)
        assert frame_id == frame and status == p.Status.OK, (frame_id, status)
        values = np.frombuffer(reply, dtype='<f4', offset=p.INFER_RESP_SIZE)
        assert values.size == 18452 and np.isfinite(values).all()
        timings.append({'frame': frame, 'roundtrip_ms': roundtrip, 'inference_ms': infer_us / 1000, 'queue_ms': queue_us / 1000, 'server_ms': total_us / 1000})
        if frame == 3:
            _, duplicate, _ = exchange(p.Msg.INFER_REQ, payload, repeat=True)
            assert duplicate == reply, 'Duplicate sequence advanced state'
            duplicate_checked = True
        if frame in (0, args.frames - 1):
            np.save(args.output / f'output-frame-{frame}.npy', values)
        packed[12:] = values[spec.output_slices['hidden_state']]
        if (frame + 1) % args.state_every == 0:
            _, sampled = request(p.Msg.STATE_REQ, {})
            assert sampled['frames_served'] == frame + 1
            power = sampled.get('power', {})
            recent = timings[-args.state_every:]
            checkpoint = {'frames': frame + 1, 'elapsed_seconds': time.monotonic() - started,
                          'power': power, 'phone_profile': sampled.get('phone_profile'),
                          'recent_exchange_mean_ms': float(np.mean([r['roundtrip_ms'] for r in recent])),
                          'recent_exchange_p95_ms': float(np.percentile([r['roundtrip_ms'] for r in recent], 95))}
            checkpoints.append(checkpoint)
            (args.output / 'checkpoint.json').write_text(json.dumps(checkpoint, indent=2))
            print(json.dumps(checkpoint), flush=True)
            if power.get('thermal_status', 0) >= 3 or (power.get('battery_temperature_c') or 0) >= 45:
                stop_reason = 'thermal_guard'
                break
    np.save(args.output / f'output-frame-{len(timings) - 1}.npy', values)
    _, state = request(p.Msg.STATE_REQ, {})
    assert state['frames_served'] == len(timings)
    kind, pong, _ = exchange(p.Msg.PING, b'pixel-link-check')
    assert kind == p.Msg.PONG and pong == b'pixel-link-check'
    # A malformed frame must fail without advancing queues.
    kind, bad, _ = exchange(p.Msg.INFER_REQ, b'')
    assert kind == p.Msg.INFER_RESP and p.unpack_infer_resp(bad)[1] == p.Status.BAD_SHAPE
    summary = {'hello': hello, 'final_state': state, 'frames': len(timings), 'elapsed_seconds': time.monotonic() - started,
               'requested_duration_seconds': args.duration_seconds, 'stop_reason': stop_reason,
               'checkpoints': checkpoints, 'ordinary_driving_refused': True,
               'wrong_model_refused': True, 'duplicate_does_not_advance': duplicate_checked,
               'malformed_frame_refused': True, 'timings': timings}
    warm = timings[5:]
    summary['steady'] = {name: {'mean': float(np.mean([t[name] for t in warm])), 'p95': float(np.percentile([t[name] for t in warm], 95)), 'max': max(t[name] for t in warm)} for name in ('roundtrip_ms', 'inference_ms', 'queue_ms', 'server_ms')}
    (args.output / 'protocol-bench.json').write_text(json.dumps(summary, indent=2))
    print(json.dumps({k: v for k, v in summary.items() if k != 'timings'}, indent=2))
finally:
    sock.close()
