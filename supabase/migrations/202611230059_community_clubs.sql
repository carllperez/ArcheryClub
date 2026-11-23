-- 059: community clubs for people without a school email (23 Nov, the developer's decision).
-- A school club is found by its email domain: every @up.edu.ph account lands in UP's club. A personal email
-- (gmail, yahoo...) can't stand for a school, so such a person couldn't start a club at all. Now they can request a
-- community club: no email domain, and nobody lands in it by email. The only way in is the club's join code (056),
-- which its officers share, and members still apply and are approved as in any club.
--  * request_club: a personal email asks for a community club (one waiting request per person);
--  * create_club / decide_club_request: a club with no domain, whose first administrator has a personal email;
--  * join_club: a community club can't be picked from a list, only entered with its code.
-- School emails stay with their school's club (one person, one club), so they can't use a community club's code.

alter table app_private.club_requests alter column email_domain drop not null;
alter table app_private.club_requests drop constraint if exists club_requests_email_domain_check;
alter table app_private.club_requests add constraint club_requests_email_domain_check check (email_domain is null or email_domain ~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$');
drop index if exists app_private.club_requests_pending_domain;
create unique index club_requests_pending_domain on app_private.club_requests(email_domain) where status='pending' and email_domain is not null;
create unique index club_requests_pending_person on app_private.club_requests(user_id) where status='pending';

create or replace function public.create_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_id uuid; v_admin uuid; v_name text; v_role text;
 v_email text:=lower(trim(coalesce(p_data->>'admin_email','')));
 v_domain text:=nullif(lower(trim(both '@ ' from coalesce(p_data->>'email_domain',''))),'');
 v_community boolean:=coalesce((p_data->>'community')::boolean,false);
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator can create clubs.' using errcode='42501'; end if;
 v_name:=trim(app_private.required(p_data,'name',160));
 if exists(select 1 from app_private.clubs where lower(name)=lower(v_name)) then raise exception 'A club with that name already exists.'; end if;
 if v_community then v_domain:=null; end if;
 if not v_community and (v_domain is null or v_domain !~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$' ) then raise exception 'Enter the school''s email domain, like up.edu.ph, or make it a community club.'; end if;
 if v_domain is not null and exists(select 1 from app_private.clubs where email_domain=v_domain) then raise exception 'Another club already uses @%.',v_domain; end if;
 if v_email='' then raise exception 'Enter the email of the club''s first administrator.'; end if;
 if v_community and app_private.club_for_email(v_email) is not null then
  raise exception '% is a school email with its own club. A community club''s first administrator uses a personal email.',v_email; end if;
 if not v_community and not (split_part(v_email,'@',2)=v_domain or split_part(v_email,'@',2) like '%.'||v_domain) then
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
  values(u,14,v_id,'create_club',jsonb_build_object('name',v_name,'admin_email',v_email,'email_domain',v_domain,'community',v_community),app_private.current_club());
 return jsonb_build_object('id',v_id);
end $$;

create or replace function public.request_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_email text; v_domain text; v_id uuid; a uuid; v_community boolean;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_domain:=split_part(v_email,'@',2);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 if app_private.club_for_email(v_email) is not null then raise exception 'Your school already has a club. Sign out and back in to join it.'; end if;
 v_community:=v_domain in ('gmail.com','yahoo.com','ymail.com','outlook.com','hotmail.com','live.com','icloud.com','me.com','proton.me','protonmail.com','aol.com','gmx.com');
 if exists(select 1 from app_private.club_requests where user_id=u and status='pending') then
  raise exception 'Your request is already waiting for the platform administrator.'; end if;
 if not v_community and exists(select 1 from app_private.club_requests where email_domain=v_domain and status='pending') then
  raise exception 'A request for @% is already waiting for the platform administrator.',v_domain; end if;
 if exists(select 1 from app_private.clubs where lower(name)=lower(trim(coalesce(p_data->>'name','')))) then
  raise exception 'A club with that name already exists. Pick another name.'; end if;
 insert into app_private.club_requests(user_id,requester,email_domain,name,short_name,school,theme,note)
  values(u,coalesce(nullif((select full_name from app_private.profiles where id=u),'')||' · ','')||v_email,
   case when v_community then null else v_domain end,
   trim(app_private.required(p_data,'name',160)),left(trim(coalesce(p_data->>'short_name','')),40),
   case when v_community then coalesce(nullif(trim(coalesce(p_data->>'school','')),''),'Community club') else trim(app_private.required(p_data,'school',160)) end,
   coalesce(nullif(p_data->>'theme',''),'green'),left(trim(coalesce(p_data->>'note','')),500))
  returning id into v_id;
 for a in select id from app_private.profiles where platform_admin loop
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(a,14,v_id,'club_request',coalesce(nullif((select full_name from app_private.profiles where id=u),''),v_email)||' ('||v_email||') asked for '||
   case when v_community then 'a community club: ' else 'a club: ' end||trim(p_data->>'name'),(select club_id from app_private.profiles where id=a));
 end loop;
 return jsonb_build_object('id',v_id,'community',v_community);
end $$;

create or replace function public.decide_club_request(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); r app_private.club_requests; v_club uuid; v_email text; v_remark text:=trim(coalesce(p_data->>'remark',''));
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator decides club requests.' using errcode='42501'; end if;
 select * into r from app_private.club_requests where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'That request no longer exists.'; end if;
 if r.status<>'pending' then raise exception 'This request was already decided.'; end if;
 if p_data->>'decision'='approve' then
  select email into v_email from auth.users where id=r.user_id;
  v_club:=(public.create_club(jsonb_build_object('name',r.name,'short_name',r.short_name,'school',r.school,'email_domain',r.email_domain,
   'admin_email',v_email,'theme',r.theme,'community',r.email_domain is null))->>'id')::uuid;
  update app_private.club_requests set status='approved',club_id=v_club,remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club was approved: '||r.name||'. You are its first administrator. Open Club setup to set its rules'||
   case when r.email_domain is null then ', then share its join code (Administration, Join codes) with the people you want in.' else '.' end,v_club);
 elsif p_data->>'decision'='decline' then
  if v_remark='' then raise exception 'Say why the request is declined, so they know what to do.'; end if;
  update app_private.club_requests set status='declined',remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club request wasn''t approved: '||v_remark,null);
 else raise exception 'Approve or decline the request.'; end if;
 return jsonb_build_object('id',r.id,'club_id',v_club);
end $$;

-- A community club is entered with its join code only.
create or replace function public.join_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); c uuid; cur uuid;
begin
 c:=nullif(p_data->>'club_id','')::uuid;
 if c is null or not exists(select 1 from app_private.clubs where id=c) then raise exception 'Pick a club from the list.'; end if;
 if (select email_domain from app_private.clubs where id=c) is null and exists(select 1 from app_private.clubs where email_domain is not null) then
  raise exception 'Join this club with its join code. Ask one of its officers for it.'; end if;
 select club_id into cur from app_private.profiles where id=u;
 if cur is not null and cur<>c and (exists(select 1 from app_private.memberships where user_id=u)
    or exists(select 1 from app_private.applications where user_id=u)
    or exists(select 1 from app_private.event_access where user_id=u)
    or exists(select 1 from app_private.role_assignments where user_id=u and retired_at is null)) then
  raise exception 'Your account already belongs to a club. Each account joins one club.'; end if;
 update app_private.profiles set club_id=c, updated_at=now() where id=u;
 return jsonb_build_object('club_id',c);
end $$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
