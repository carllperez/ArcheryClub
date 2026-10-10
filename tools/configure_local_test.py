"""Generate only ignored emulator configuration from the isolated CLI test project."""
import json,sys
from pathlib import Path
config=json.loads(Path(sys.argv[1]).read_text())
assert config['API_URL']=='http://127.0.0.1:55421'
key=config['PUBLISHABLE_KEY']
assert key.startswith('sb_publishable_')
root=Path(__file__).resolve().parents[1]
(root/'backend.local.properties').write_text('SUPABASE_URL=http://10.0.2.2:55421\nSUPABASE_PUBLISHABLE_KEY='+key+'\n')
