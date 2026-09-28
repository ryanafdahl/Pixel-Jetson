# Direct USB validation

Keep the vehicle parked with ignition off. This test sends synthetic images and
recurrent features; it does not start cameras, modeld, controls or steering.
The phone-to-comma USB cable carries model data directly. SSH only launches and
reads the isolated test on the comma.

Copy `android/parked_comma.py` and the generated
`android/app/src/main/assets/model.json` to `/data/pixel-jetlink-test/` on your
comma using your existing SSH access. Use the comma's virtual environment Python,
not a system Python missing numpy. Then run:

```bash
cd /data/openpilot
/usr/local/venv/bin/python3 /data/pixel-jetlink-test/parked_comma.py \
  --usb --connect-timeout 300 --frames 120 \
  --output /data/pixel-jetlink-test/usb-new-run
```

Use a new output directory for each attempt. Start this before heading to the
phone; wait for `USB test gadget ready`. The initial connection window is five
minutes. Connect the Pixel. Android should offer JetLink Pixel Test on the first
attachment: select it and **Always use** if shown. The app loads the model and
connects automatically when Android grants permission. The Connect button is a
fallback; do not repeatedly tap it after a completed test.

The updated client sends measured USB request/reply averages and p95 values to
the dashboard every 20 frames and at completion. The first five frames are
excluded. These timing windows exclude the separate dashboard message. Phone
work is shown separately. A 50 ms average and p95 threshold is a display rule,
not a full driving qualification or a guarantee that every frame meets budget.

The test refuses onroad state and active camera/model processes. It temporarily
disables normal JetLink so its daemon releases USB, and restores the previous
setting in cleanup. The normal transport limits resume after the initial USB
handshake, and the frame phase is bounded by three minutes. If forcibly killed
or power is lost, verify the enable setting before returning to the Jetson.

After frame 120, the dashboard should retain **TEST COMPLETE**. The result is
saved immediately. The client sends keepalive pings for up to 60 seconds so you
can view it; unplugging ends this hold early. Use `--hold-seconds 0` to skip the
viewing period. The test then closes and restores the normal daemon. An idle/disconnect message after
completion does not invalidate the saved result. The ordinary driving client is
still refused by this experimental app; tapping Connect again does not start
another isolated test.

Verify the one-time default selection with a subsequent unplug/replug while a
fresh parked test is waiting. Automatic launch, lock-screen behavior, and
permission retention must be verified on the exact Android build. This setup
does not disable the OS security model or grant permission via root.

Record the negotiated USB speed during each run:

The client now saves `usb_link.current_speed` and the phone's `usb_io` label in
the result automatically after the handshake. You can also inspect speed with:

```bash
cat /sys/class/udc/*/current_speed
```

Use `result.json`, not a screen impression, for pass/fail. It must show
`passed_protocol: true`, `transport: usb`, and the requested frame count. After a short pass, use `--frames 1200` for a
longer run with a new output directory. Updated builds also save `phone_profile`
to separate phone processing stages. Inspect power fields
for actual wireless charging under load. A short synthetic pass does not prove
reconnect reliability, thermal endurance or numerical suitability for driving.
