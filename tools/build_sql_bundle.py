"""Generate a transactional SQL Editor alternative for a fresh development project."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parts=["-- Generated from versioned migrations. Fresh development project ONLY.\n-- Review docs/backend-setup.md before applying.\nbegin;\n"]
for path in sorted((root/'supabase/migrations').glob('*.sql')):
    sql=path.read_text().strip()
    assert sql.startswith('begin;') and sql.endswith('commit;'),path
    parts.append(f'-- SOURCE: {path.name}\n'+sql[6:-7].strip()+'\n')
parts.append("notify pgrst, 'reload schema';\ncommit;\n")
(root/'supabase/deploy-development.sql').write_text('\n'.join(parts))
