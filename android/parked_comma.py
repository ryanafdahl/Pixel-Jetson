#!/usr/bin/env python3
"""Synthetic JetLink protocol test on a parked comma; never starts vehicle control.

TCP leaves live settings alone. USB temporarily releases jetlinkd's link and
restores its enable setting in finally. This does not publish modelV2 or prove
camera-to-prediction performance, numerical acceptance, or driving readiness.
"""
import argparse
import json
from pathlib import Path
import signal
import sys
import time
import numpy as np

sys.path.insert(0, '/data/openpilot')
sys.path.insert(0, '/data/openpilot/jetlink_repo')
from openpilot.common.params import Params
from jetlink.client import JetlinkClient
from jetlink import protocol as protocol


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument('--tcp', help='host:port; use only a localhost test tunnel')
    modes.add_argument('--usb', action='store_true')
    parser.add_argument('--manifest', type=Path, default=Path(__file__).with_name('model.json'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--frames', type=int, default=120)
    parser.add_argument('--connect-timeout', type=int, default=30,
                        help='Seconds allowed for the initial USB permission step (30..300)')
    args = parser.parse_args()
    if not 10 <= args.frames <= 1200:
        parser.error('frames must be 10..1200')
    if not 30 <= args.connect_timeout <= 300:
        parser.error('connect-timeout must be 30..300')
    live = Params()
    def parked():
        if not live.get_bool('IsOffroad'):
            raise RuntimeError('Stopped: comma must remain offroad with ignition off')
    parked()
    for path in Path('/proc').glob('[0-9]*/cmdline'):
        try:
            argv = path.read_bytes().split(b'\0')
        except OSError:
            continue
        if argv and (b'openpilot.selfdrive.modeld.modeld' in argv or Path(argv[0].decode(errors='replace')).name == 'camerad'):
            raise RuntimeError('Camera/model processes active; parked test refused')
    def interrupted(signum, _frame):
        raise RuntimeError(f'Test interrupted by signal {signum}')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    signal.alarm(180 + args.connect_timeout)
    args.output.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(args.manifest.read_text())
    assert manifest['validation'] == 'parked_only'
    original_enabled = live.get('JetlinkEnabled')
    client = None
    settings_changed = False
    try:
        if args.usb:
            settings_changed = True
            # The existing daemon's supported disabled path closes and unbinds
            # its endpoints. Wait for release; do not kill/suspend manager.
            live.put_bool('JetlinkEnabled', False, block=True)
            udc = Path('/sys/kernel/config/usb_gadget/jetlink/UDC')
            end = time.monotonic() + 15
            while udc.read_text().strip():
                parked()
                if time.monotonic() >= end:
                    raise RuntimeError('Existing daemon did not release USB; no test started')
                time.sleep(0.1)
            client = JetlinkClient.open_ffs('/dev/ffs-jetlink', gadget=str(udc.parent), deadline=2.0)
            print('USB test gadget ready. Keep the car off; allow USB on Pixel and tap Connect comma USB.', flush=True)
        else:
            host, port = args.tcp.rsplit(':', 1)
            if host != '127.0.0.1':
                raise RuntimeError('TCP test requires a local tunnel')
            client = JetlinkClient.open_tcp(host, int(port), deadline=2.0)

        # Scope the extra bench handshake field to this one client object.
        send_json = client.t.send_json
        def bench_json(kind, sequence, data, flags=0):
            if kind == protocol.Msg.ENGINE_REQ:
                data = dict(data, validation_mode='parked')
            return send_json(kind, sequence, data, flags)
        client.t.send_json = bench_json
        parked()
        # Only this isolated process gets a longer initial permission window.
        # Restore normal transport limits before any model handshake/inference.
        if args.usb:
            from jetlink.transport import ffs
            saved_limits = (ffs.EP_OPEN_TIMEOUT, ffs.EP_READY_TIMEOUT, ffs.WRITE_TIMEOUT)
            ffs.EP_OPEN_TIMEOUT = ffs.EP_READY_TIMEOUT = ffs.WRITE_TIMEOUT = args.connect_timeout
        try:
            hello = client.hello(timeout=args.connect_timeout)
        finally:
            if args.usb:
                ffs.EP_OPEN_TIMEOUT, ffs.EP_READY_TIMEOUT, ffs.WRITE_TIMEOUT = saved_limits
        parked()
        signal.alarm(180)
        if hello.get('validation') != 'parked_only':
            raise RuntimeError('Expected the Pixel parked-test app')
        expected = manifest['spec']
        spec = client.ensure_engine(expected['sha256'], expected['nbytes'], frame_skip=4, build_timeout=30)
        if spec.to_dict() != expected:
            raise RuntimeError('Model signature differs from prepared manifest')
        warped = np.zeros(spec.warped_shape, np.uint8)
        packed = np.zeros(spec.packed_nelem, np.float32)
        packed[8] = 1
        rows = []
        print('Exact model handshake passed; running synthetic recurrent frames.', flush=True)
        for frame in range(args.frames):
            parked()
            start = time.monotonic()
            values = client.infer(warped, packed, frame_id=frame, reset=frame == 0, want_state=True)
            elapsed = (time.monotonic() - start) * 1000
            if values.size != 18452 or not np.isfinite(values).all():
                raise RuntimeError('Invalid model output')
            packed[12:] = values[spec.output_slices['hidden_state']]
            rows.append({'frame': frame, 'roundtrip_ms': elapsed, 'inference_ms': client.last_timings[0] / 1000,
                         'queue_ms': client.last_timings[1] / 1000, 'server_ms': client.last_timings[2] / 1000,
                         'pixel_state': client.last_state})
            if frame % 20 == 19:
                print(f'{frame + 1} frames, latest complete exchange {elapsed:.1f} ms; power={client.last_state.get("power", {})}', flush=True)
            if args.usb and (frame % 20 == 19 or frame == args.frames - 1):
                # Tell the phone the actual client-measured request/reply time.
                # Display-only update; it is outside each timed inference.
                samples = [r['roundtrip_ms'] for r in rows[5:]]
                metrics = {'mean_ms': float(np.mean(samples)), 'p95_ms': float(np.percentile(samples, 95)),
                           'samples': len(samples), 'complete': frame == args.frames - 1}
                sequence = client._next_seq()
                client.t.send_json(protocol.Msg.STATE_REQ, sequence, {'bench_metrics': metrics})
                client._expect(protocol.Msg.STATE_RESP, sequence, 2.0)
        report = {'passed_protocol': True, 'driving_ready': False,
                  'transport': 'usb' if args.usb else 'ssh_adb_tunnel', 'hello': hello,
                  'limitations': 'Synthetic inputs; no cameras, modeld, controls, or steering commands. Accuracy and driving latency remain unqualified.',
                  'frames': rows}
        report['steady'] = {key: {'mean': float(np.mean([r[key] for r in rows[5:]])),
                                  'p95': float(np.percentile([r[key] for r in rows[5:]], 95)),
                                  'max': float(max(r[key] for r in rows[5:]))}
                            for key in ('roundtrip_ms', 'inference_ms', 'queue_ms', 'server_ms')}
        (args.output / 'result.json').write_text(json.dumps(report, indent=2))
        print(json.dumps({k: v for k, v in report.items() if k != 'frames'}, indent=2), flush=True)
    finally:
        signal.alarm(0)
        try:
            if client is not None:
                client.close()
        finally:
            if settings_changed:
                if original_enabled is None:
                    live.remove('JetlinkEnabled')
                else:
                    live.put('JetlinkEnabled', original_enabled, block=True)
                print('Restored original JetLink enable setting; normal daemon can reconnect.', flush=True)


if __name__ == '__main__':
    main()
