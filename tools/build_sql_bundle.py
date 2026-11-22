"""Generate a transactional SQL Editor alternative for a fresh development project."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
parts=["-- Generated from versioned migrations. Fresh development project ONLY.\n-- Review docs/backend-setup.md before applying.\nbegin;\n"]
for path in sorted((root/'supabase/migrations').glob('*.sql')):
    sql=path.read_text().strip()
    # Older migrations carry their own begin;/commit;, newer ones don't: the bundle is one transaction either way.
    # (some start with a comment first). Drop those statement lines; PL/pgSQL's "begin" has no semicolon.
    sql='\n'.join(l for l in sql.splitlines() if l.strip().lower() not in ('begin;','commit;')).strip()
    parts.append(f'-- SOURCE: {path.name}\n'+sql+'\n')
parts.append("notify pgrst, 'reload schema';\ncommit;\n")
(root/'supabase/deploy-development.sql').write_text('\n'.join(parts))
