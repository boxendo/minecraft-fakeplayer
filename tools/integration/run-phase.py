#!/usr/bin/env python3
"""Run one asynchronous integration phase and require a fresh completion report."""
import importlib.util, pathlib, sys, time
root = pathlib.Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('rcon', root / 'tools/rcon-test.py')
rcon = importlib.util.module_from_spec(spec); spec.loader.exec_module(rcon)
phase = sys.argv[1]
server = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else root / '.test-server'
report = server / 'plugins/FakeplayerVerification' / (phase + '.txt')
report.unlink(missing_ok=True)
print(rcon.command('fpverify ' + phase), flush=True)
for _ in range(300):
    if report.exists():
        text = report.read_text()
        print(text, end='', flush=True)
        if 'FAIL ' in text or 'PASS phase ' + phase not in text: sys.exit(1)
        break
    time.sleep(.1)
else:
    raise TimeoutError('No fresh completion report for ' + phase)
