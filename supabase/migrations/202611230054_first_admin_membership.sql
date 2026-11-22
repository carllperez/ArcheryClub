-- 054: a new club's first administrator also gets Membership officer (23 Nov, the developer's decision).
-- Administrator carries only 'administration'; accepting members needs 'membership', and in a brand-new club
-- there are no Active members to give that role to. Without this, only the developer exemption (047) let a
-- new club accept anyone. Same create_club as 051 otherwise; it stays owned by postgres (reads auth.users).
-- Backfill: every club's first administrator who has no active membership role gets one, same term end.
create or replace function public.create_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_id uuid; v_admin uuid; v_name text; v_role text;
 v_email text:=lower(trim(coalesce(p_data->>'admin_email','')));
 v_domain text:=nullif(lower(trim(both '@ ' from coalesce(p_data->>'email_domain',''))),'');
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator can create clubs.' using errcode='42501'; end if;
 v_name:=trim(app_private.required(p_data,'name',160));
 if exists(select 1 from app_private.clubs where lower(name)=lower(v_name)) then raise exception 'A club with that name already exists.'; end if;
 if v_domain is null or v_domain !~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$' then raise exception 'Enter the school''s email domain, like up.edu.ph.'; end if;
 if exists(select 1 from app_private.clubs where email_domain=v_domain) then raise exception 'Another club already uses @%.',v_domain; end if;
 if v_email='' then raise exception 'Enter the email of the club''s first administrator.'; end if;
 if not (split_part(v_email,'@',2)=v_domain or split_part(v_email,'@',2) like '%.'||v_domain) then
  raise exception 'The first administrator needs an @% email.',v_domain; end if;
 select id into v_admin from auth.users where lower(email)=v_email;
 if v_admin is null then raise exception 'No account uses %. Ask them to create an account in the app first.',v_email; end if;
 if v_admin=u or exists(select 1 from app_private.memberships where user_id=v_admin)
    or exists(select 1 from app_private.applications where user_id=v_admin and status<>'draft')
    or exists(select 1 from app_private.role_assignments where user_id=v_admin and retired_at is null) then
  raise exception 'That account already belongs to a club. The first administrator needs an account of their own.'; end if;
 if coalesce(p_data->>'theme','green') not in ('green','red','blue') then raise exception 'Pick green, red or blue for the club''s colour.'; end if;
 insert into app_private.clubs(name,short_name,school,created_by,theme,email_domain)
  values(v_name,left(trim(coalesce(p_data->>'short_name','')),40),left(trim(coalesce(p_data->>'school','')),160),u,coalesce(p_data->>'theme','green'),v_domain)
  returning id into v_id;
 insert into app_private.settings(club_id,club_name) values(v_id,v_name);
 insert into app_private.profiles(id,email,club_id) values(v_admin,v_email,v_id)
  on conflict(id) do update set club_id=v_id, updated_at=now();
 select id into v_role from app_private.roles where 'administration'=any(permissions) order by (id='administrator') desc, id limit 1;
 insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason,club_id)
  values(v_admin,v_role,'Administrator',now(),now()+interval '1 year',u,'First administrator of the club',v_id);
 -- Also Membership officer, so a new club can accept its first members without the developer exemption (047).
 select id into v_role from app_private.roles where 'membership'=any(permissions) order by (id='membership_officer') desc, id limit 1;
 if v_role is not null then
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason,club_id)
   values(v_admin,v_role,'Membership officer',now(),now()+interval '1 year',u,'First administrator of the club',v_id);
 end if;
 update app_private.profiles set developer=true where id=v_admin;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,14,v_id,'create_club',jsonb_build_object('name',v_name,'admin_email',v_email,'email_domain',v_domain),app_private.current_club());
 return jsonb_build_object('id',v_id);
end $$;

insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason,club_id)
select ra.user_id,
 (select id from app_private.roles where 'membership'=any(permissions) order by (id='membership_officer') desc, id limit 1),
 'Membership officer',now(),ra.ends_at,ra.assigned_by,'First administrator of the club',ra.club_id
from app_private.role_assignments ra
where ra.reason='First administrator of the club' and ra.retired_at is null and ra.ends_at>now()
 and not exists(select 1 from app_private.role_assignments x join app_private.roles r on r.id=x.role_id
   where x.user_id=ra.user_id and x.club_id is not distinct from ra.club_id and x.retired_at is null and x.ends_at>now() and 'membership'=any(r.permissions));

notify pgrst, 'reload schema';
