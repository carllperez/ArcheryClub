-- Run once through trusted database administration AFTER registering and verifying the account.
-- Replace the placeholder with the email explicitly selected by the project owner.
begin;
select set_config('archery.bootstrap_email','REPLACE_WITH_VERIFIED_ADMIN_EMAIL',true);
do $$
declare account uuid;
begin
 perform pg_advisory_xact_lock(14001);
 if exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
  where 'administration'=any(r.permissions) and a.retired_at is null and now() between a.starts_at and a.ends_at)
 then raise exception 'An administrator already exists. Use M14 for subsequent assignments.';end if;
 select id into account from auth.users where lower(email)=lower(current_setting('archery.bootstrap_email')) and email_confirmed_at is not null;
 if account is null then raise exception 'The selected account must register and verify its email first.';end if;
 insert into app_private.profiles(id) values(account) on conflict do nothing;
 insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason)
 values(account,'administrator','Development administrator',now()+interval '1 year','Initial administrator explicitly selected by project owner');
 insert into app_private.audit_log(actor,module,record_id,action,detail)
 values(account,14,account,'trusted_admin_bootstrap',jsonb_build_object('method','Project owner trusted database setup'));
end $$;
commit;
