begin;
-- Expected-version mismatches are business conflicts, not retryable PostgreSQL
-- serialization failures. SQLSTATE 40001 can loop in PostgREST 14.
create or replace function app_private.check_version(p_actual bigint,p_data jsonb)
returns void language plpgsql immutable set search_path='' as $$
begin
 if p_actual is null or (p_data->>'version')::bigint is distinct from p_actual then
  raise sqlstate 'PT409' using message='This record changed. Refresh before trying again.';
 end if;
end $$;
commit;
