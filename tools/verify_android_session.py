"""Device regression checks for the isolated local build, never the hosted app.

Requires the fictional local-services.mjs member and LAN permission granted.
Uses actual UI controls; credentials stay in the private fixture file and are never printed.
"""
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = '/Users/carll/Library/Android/sdk/platform-tools/adb'
PACKAGE = 'ph.capstone.archeryclub.local'
fixture = json.loads(Path('/private/tmp/archery-device-fixture.json').read_text())
assert fixture['email'].endswith('@example.test')
assert all(re.fullmatch(r'[A-Za-z0-9@.\-]+', fixture[k]) for k in ('email', 'password'))

def shell(*args):
    return subprocess.run([ADB, '-s', 'emulator-5554', 'shell', *args],
                          check=True, capture_output=True, timeout=40).stdout.decode()

def screen():
    shell('uiautomator', 'dump', '/sdcard/archery-session-check.xml')
    return ET.fromstring(shell('cat', '/sdcard/archery-session-check.xml'))

def wait_for(label, timeout=60):
    labels = (label,) if isinstance(label, str) else label
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        root = screen()
        if any(n.get('text') in labels for n in root.iter('node')):
            return root
        time.sleep(1)
    raise AssertionError('Expected screen text did not appear: ' + str(label))

def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def tap_text(label):
    root = wait_for(label)
    tap(next(n for n in root.iter('node') if n.get('text') == label))

def restart():
    shell('am', 'force-stop', PACKAGE)
    shell('am', 'start', '-n', PACKAGE+'/ph.capstone.archeryclub.MainActivity')

def sign_in(password):
    root = wait_for('Welcome to Archery Club')
    fields = [n for n in root.iter('node') if n.get('class') == 'android.widget.EditText']
    assert len(fields) >= 2
    for field, value in zip(fields, (fixture['email'], password)):
        tap(field)
        shell('input', 'keyevent', 'KEYCODE_MOVE_END')
        # Android may restore the fictional email field after process death.
        # Limit clearing to this local test app's login controls.
        assert field.get('package') == PACKAGE
        existing = field.get('text', '')
        if existing:
            shell('input', 'keyevent', *(['KEYCODE_DEL'] * len(existing)))
        shell('input', 'text', value)
        shell('input', 'keyevent', 'KEYCODE_BACK')
    tap_text('Sign in')

restart()
initial = wait_for(('Your club', 'Welcome to Archery Club'))
if any(n.get('text') == 'Welcome to Archery Club' for n in initial.iter('node')):
    sign_in(fixture['password'])
wait_for('Your club')
restart()
wait_for('Your club')
wait_for('Member Profile and Status')
print('PASS: process restart restores the authenticated member workspace.', flush=True)
tap_text('Sign out')
wait_for('Welcome to Archery Club')
restart()
wait_for('Welcome to Archery Club')
print('PASS: sign-out persists after process restart.', flush=True)
sign_in('Incorrect-local-password-123')
root = wait_for('The email or password is incorrect. Please try again.')
assert not any(n.get('text') == 'Your club' for n in root.iter('node'))
print('PASS: incorrect password is rejected without opening member records.', flush=True)
restart()
sign_in(fixture['password'])
wait_for('Your club')
print('PASS: correct password signs in again after a failed attempt.', flush=True)
