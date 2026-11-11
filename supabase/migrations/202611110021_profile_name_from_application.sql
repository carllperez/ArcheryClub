-- Applicant names show everywhere as soon as they apply.
-- Sign-up only asks for an email and password, so a new profile has no name until the
-- application is approved. Officers then saw unnamed accounts ("Club record") in pickers
-- such as Assign role. While someone has no membership yet, their profile name follows
-- the name on their application; once a membership exists the verified-name lock from
-- migration 018 applies and only an approved name correction changes it.
begin;

create or replace function app_private.profile_name_from_application() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if trim(coalesce(new.full_name,''))<>'' and not exists(select 1 from app_private.memberships where user_id=new.user_id) then
  update app_private.profiles set full_name=trim(new.full_name),updated_at=now()
   where id=new.user_id and full_name is distinct from trim(new.full_name);
 end if;
 return new;
end $$;
revoke all on function app_private.profile_name_from_application() from public,anon,authenticated;

create trigger profile_name_from_application after insert or update of full_name on app_private.applications
 for each row execute function app_private.profile_name_from_application();

-- Backfill: unnamed profiles of people who have already applied.
update app_private.profiles p set full_name=trim(a.full_name),updated_at=now()
 from app_private.applications a
 where a.user_id=p.id and trim(p.full_name)='' and trim(a.full_name)<>'';

commit;
