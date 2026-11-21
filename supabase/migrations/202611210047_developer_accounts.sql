-- Developer accounts (21 Nov testing, the developer's decision): the developer's own account, which has no club
-- membership, may hold roles so one person can test every desk end to end. Everyone else still needs an Active
-- membership for any role (migration 042, "no exceptions").
--  * The flag is set only here, in the database. No app screen or command can set it.
--  * The app shows a flagged account as "Developer account (testing)" in the role picker and the accounts list.
--  * Separation of duties is unchanged: whoever records a result still can't verify it, and so on.
--  * TO REMOVE BEFORE GOING OFFICIAL: update app_private.profiles set developer=false; then retire the roles the
--    developer account holds. (Or apply a migration that drops the column and restores the 042 checks.)
-- admin_command is not retyped: its live definition is read, each membership check is confirmed to appear exactly
-- once, and only those two expressions are replaced.
begin;

alter table app_private.profiles add column if not exists developer boolean not null default false;

create or replace function app_private.role_eligible(p_user uuid) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.memberships m where m.user_id=p_user and m.status='Active')
     or exists(select 1 from app_private.profiles p where p.id=p_user and p.developer)
$$;
revoke all on function app_private.role_eligible(uuid) from public,anon,authenticated;

do $do$
declare d text;
 v_assign text:='not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>''user_id'')::uuid and m.status=''Active'')';
 v_extend text:='not exists(select 1 from app_private.memberships m where m.user_id=ra.user_id and m.status=''Active'')';
begin
 d:=pg_get_functiondef('app_private.admin_command(text,jsonb)'::regprocedure);
 if (length(d)-length(replace(d,v_assign,'')))/length(v_assign)<>1 or (length(d)-length(replace(d,v_extend,'')))/length(v_extend)<>1 then
  raise exception 'admin_command is not the expected version (membership checks not found exactly once); nothing changed.';
 end if;
 d:=replace(d,v_assign,'not app_private.role_eligible((p_data->>''user_id'')::uuid)');
 d:=replace(d,v_extend,'not app_private.role_eligible(ra.user_id)');
 execute d;
end $do$;

-- The role picker's standing list: a developer account without an Active membership shows as "Developer".
create or replace function app_private.member_standing() returns jsonb language sql stable security definer set search_path='' as $$
 select case when app_private.can('administration') or app_private.can('membership')
  then (select coalesce(jsonb_agg(x),'[]') from (
    select jsonb_build_object('user_id',m.user_id,'status',m.status) x from app_private.memberships m
    union all
    select jsonb_build_object('user_id',p.id,'status','Developer') from app_private.profiles p
     where p.developer and not exists(select 1 from app_private.memberships m where m.user_id=p.id and m.status='Active')) s)
  else '[]'::jsonb end
$$;
revoke all on function app_private.member_standing() from public,anon;
grant execute on function app_private.member_standing() to authenticated;

commit;
