-- Interclub results, 21 Nov testing: the officer who recorded a result was offered "Verify", tapped it, and got a vague
-- "no permission" (the server rightly refuses: someone else must verify). The app couldn't tell who recorded a result,
-- because that was only in the audit log. Each result now keeps who recorded it, so the app can say
-- "Another officer verifies this" instead of offering the button. The server's check is unchanged.
begin;

alter table app_private.interclub_results add column if not exists recorded_by uuid references app_private.profiles;

-- Whoever enters or changes the result is its recorder (a changed result also loses its verification, as before).
create or replace function app_private.result_recorder() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if tg_op='INSERT' or new.result is distinct from old.result then new.recorded_by:=coalesce(auth.uid(),new.recorded_by); end if;
 return new;
end $$;
revoke all on function app_private.result_recorder() from public,anon,authenticated;
drop trigger if exists result_recorder on app_private.interclub_results;
create trigger result_recorder before insert or update on app_private.interclub_results
 for each row execute function app_private.result_recorder();

-- Results already recorded: take the recorder from the audit log (the same source the server's check uses).
update app_private.interclub_results r set recorded_by=(select a.actor from app_private.audit_log a
  where a.module=15 and a.record_id=r.id and a.action='record_result' order by a.created_at desc, a.id desc limit 1)
 where r.recorded_by is null;

commit;
