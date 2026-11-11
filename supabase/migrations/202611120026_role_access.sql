-- Roles & access. Each club decides which desks a role opens (Administration -> Roles & access).
-- A role is a name plus a set of permissions; every action already checks that set, so changing
-- it changes what holders can see and do on their next refresh. Built-in roles remember their
-- default so a club can undo its changes. The last-administrator rule still applies.
begin;

alter table app_private.roles add column default_permissions text[];
update app_private.roles r set default_permissions=d.p from (values
 ('administrator',array['administration']),('membership_officer',array['membership']),('activity_officer',array['activities']),
 ('election_officer',array['elections']),('equipment_officer',array['equipment']),('training_officer',array['training']),
 ('treasurer',array['finance']),('president',array['president']),('reports_officer',array['reports']),('interclub_officer',array['interclub'])
) as d(id,p) where r.id=d.id;

create function app_private.keep_an_administrator() returns void language plpgsql security definer set search_path='' as $$
begin
 if not exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id join app_private.profiles p on p.id=a.user_id
  where p.enabled and 'administration'=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at)
 then raise exception 'At least one person must keep Administration, or nobody could manage roles.'; end if;
end $$;
revoke all on function app_private.keep_an_administrator() from public,anon,authenticated;

create function public.save_role(p_role_id text,p_name text,p_permissions text[],p_new boolean) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_name text:=trim(coalesce(p_name,'')); v_perms text[]; v_old text[];
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 perform pg_advisory_xact_lock(14001);
 if length(v_name) not between 2 and 60 then raise exception 'Give the role a name of 2 to 60 characters.'; end if;
 if coalesce(p_role_id,'') !~ '^[a-z][a-z0-9_]{1,79}$' then raise exception 'That role id is not valid.'; end if;
 select coalesce(array_agg(distinct x order by x),'{}') into v_perms from unnest(coalesce(p_permissions,'{}')) x;
 if not v_perms <@ array['membership','activities','elections','equipment','training','finance','president','reports','administration','interclub'] then
  raise exception 'Unknown desk in the list.'; end if;
 select permissions into v_old from app_private.roles where id=p_role_id;
 if p_new then
  if found then raise exception 'A role with this name already exists. Open it from the list instead.'; end if;
  if exists(select 1 from app_private.roles where lower(name)=lower(v_name)) then raise exception 'A role called % already exists.',v_name; end if;
  insert into app_private.roles(id,name,permissions) values(p_role_id,v_name,v_perms);
 else
  if not found then raise exception 'That role no longer exists. Refresh and try again.'; end if;
  if exists(select 1 from app_private.roles where lower(name)=lower(v_name) and id<>p_role_id) then raise exception 'Another role is already called %.',v_name; end if;
  update app_private.roles set name=v_name,permissions=v_perms where id=p_role_id;
 end if;
 perform app_private.keep_an_administrator();
 perform app_private.note(14,null,'save_role',jsonb_build_object('role_id',p_role_id,'name',v_name,'permissions',to_jsonb(v_perms),'before',to_jsonb(v_old)));
 return jsonb_build_object('id',p_role_id,'name',v_name,'permissions',to_jsonb(v_perms));
end $$;
revoke all on function public.save_role(text,text,text[],boolean) from public,anon;
grant execute on function public.save_role(text,text,text[],boolean) to authenticated;

create function public.reset_role(p_role_id text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_default text[];
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 perform pg_advisory_xact_lock(14001);
 select default_permissions into v_default from app_private.roles where id=p_role_id;
 if not found then raise exception 'That role no longer exists. Refresh and try again.'; end if;
 if v_default is null then raise exception 'This role was made by the club, so it has no default to go back to.'; end if;
 update app_private.roles set permissions=v_default where id=p_role_id;
 perform app_private.keep_an_administrator();
 perform app_private.note(14,null,'reset_role',jsonb_build_object('role_id',p_role_id,'permissions',to_jsonb(v_default)));
 return jsonb_build_object('id',p_role_id,'permissions',to_jsonb(v_default));
end $$;
revoke all on function public.reset_role(text) from public,anon;
grant execute on function public.reset_role(text) to authenticated;

commit;
