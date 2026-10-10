"""Enter only the fictional local fixture into the observed 1080x2400 test screen."""
import json,re,subprocess
from pathlib import Path
fixture=json.loads(Path('/private/tmp/archery-device-fixture.json').read_text())
assert fixture['email'].endswith('@example.test')
assert all(re.fullmatch(r'[A-Za-z0-9@.\-]+',fixture[k]) for k in ('email','password'))
adb='/Users/carll/Library/Android/sdk/platform-tools/adb'
def run(*args):subprocess.run([adb,'-s','emulator-5554','shell',*args],check=True,capture_output=True)
run('input','tap','400','970')
run('input','text',fixture['email'])
run('input','keyevent','4')
run('input','tap','400','1190')
run('input','text',fixture['password'])
run('input','keyevent','4')
run('input','tap','540','1390')
print('Submitted fictional local fixture credentials; no credentials printed.')
