-- Read-only deployment evidence. No passwords, tokens or personal record contents.
select jsonb_build_object(
 'migration_count',(select count(*) from supabase_migrations.schema_migrations),
 'private_tables',(select count(*) from pg_tables where schemaname='app_private'),
 'tables_without_rls',(select coalesce(jsonb_agg(c.relname),'[]') from pg_class c
   join pg_namespace n on n.oid=c.relnamespace
   where n.nspname='app_private' and c.relkind='r' and not c.relrowsecurity),
 'authenticated_direct_writes',(select coalesce(jsonb_agg(c.relname),'[]') from pg_class c
   join pg_namespace n on n.oid=c.relnamespace where n.nspname='app_private' and c.relkind='r'
   and (has_table_privilege('authenticated',c.oid,'INSERT') or has_table_privilege('authenticated',c.oid,'UPDATE') or has_table_privilege('authenticated',c.oid,'DELETE'))),
 'anonymous_workspace',has_function_privilege('anon','public.club_workspace()','EXECUTE'),
 'authenticated_workspace',has_function_privilege('authenticated','public.club_workspace()','EXECUTE'),
 'document_bucket',(select jsonb_build_object('private',not public,'max_bytes',file_size_limit) from storage.buckets where id='club-documents'),
 'rules_validated',(select rules_validated from app_private.settings),
 'auth_users',(select count(*) from auth.users),
 'owner_provisioned_demo_accounts',(select count(*) from auth.users where raw_app_meta_data->>'owner_provisioned_demo'='true')
) as deployment_check;
