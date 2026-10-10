-- Generated from versioned migrations. Fresh development project ONLY.
-- Review docs/backend-setup.md before applying.
begin;

-- SOURCE: 202610010001_identity_membership.sql
create schema if not exists app_private;
revoke all on schema app_private from public, anon;
grant usage on schema app_private to authenticated;

create table app_private.settings (
 id boolean primary key default true check(id), club_name text not null default 'Archery Club',
 rules_validated boolean not null default false,
 application_requirements text[] not null default '{}', renewal_requirements text[] not null default '{}',
 membership_categories text[] not null default array['Regular member'],
 retention_policy text not null default 'Awaiting club validation',
 updated_at timestamptz not null default now()
);
insert into app_private.settings(id) values(true);
create table app_private.profiles (
 id uuid primary key references auth.users(id), full_name text not null default '', phone text not null default '',
 enabled boolean not null default true, created_at timestamptz not null default now(), updated_at timestamptz not null default now()
);
create table app_private.roles (
 id text primary key, name text not null, permissions text[] not null default '{}',
 check(permissions <@ array['membership','activities','elections','equipment','training','finance','president','reports','administration','interclub'])
);
insert into app_private.roles values
 ('administrator','Administrator',array['administration']),
 ('membership_officer','Membership officer',array['membership']),
 ('activity_officer','Activity officer',array['activities']),
 ('election_officer','Election officer / Commission on Elections',array['elections']),
 ('equipment_officer','Equipment officer / Armorer',array['equipment']),
 ('training_officer','Coach / Training officer',array['training']),
 ('treasurer','Treasurer / Finance officer',array['finance']),
 ('president','President',array['president']),
 ('reports_officer','Reports officer',array['reports']),
 ('interclub_officer','Interclub officer',array['interclub']);
create table app_private.role_assignments (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references app_private.profiles,
 role_id text not null references app_private.roles, position text not null,
 starts_at timestamptz not null default now(), ends_at timestamptz not null, retired_at timestamptz,
 assigned_by uuid references app_private.profiles, reason text not null,
 check(ends_at>starts_at)
);
create index role_assignment_user on app_private.role_assignments(user_id);
create table app_private.memberships (
 user_id uuid primary key references app_private.profiles, category text not null,
 status text not null check(status in ('Active','Inactive','Suspended','Expired')),
 student_number text not null default '', approved_by uuid not null references app_private.profiles,
 approved_at timestamptz not null default now(), updated_at timestamptz not null default now(), version bigint not null default 1
);
create table app_private.applications (
 id uuid primary key default gen_random_uuid(), user_id uuid unique not null references app_private.profiles,
 full_name text not null default '', phone text not null default '', student_number text not null default '', experience text not null default '',
 confirmed boolean not null default false,
 status text not null default 'draft' check(status in ('draft','pending','under_review','incomplete','returned_for_correction','approved','rejected')),
 remark text not null default '', submitted_at timestamptz, reviewed_by uuid references app_private.profiles,
 created_at timestamptz not null default now(), updated_at timestamptz not null default now(), version bigint not null default 1
);
create table app_private.renewals (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references app_private.memberships(user_id),
 information text not null, status text not null default 'draft' check(status in ('draft','pending','approved','rejected','returned_for_correction')),
 remark text not null default '', reviewed_by uuid references app_private.profiles,
 created_at timestamptz not null default now(), version bigint not null default 1
);
create unique index pending_renewal on app_private.renewals(user_id) where status in ('draft','pending','returned_for_correction');
create table app_private.documents (
 id uuid primary key default gen_random_uuid(), owner_id uuid not null references app_private.profiles,
 module integer not null check(module between 1 and 15), record_id uuid not null, requirement text not null,
 name text not null check(length(name) between 1 and 200), path text unique not null,
 mime text not null, size_bytes bigint not null check(size_bytes between 1 and 20971520),
 created_at timestamptz not null default now(), verified_by uuid references app_private.profiles
);
create table app_private.notifications (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references app_private.profiles,
 module integer not null, record_id uuid, kind text not null, message text not null,
 due_at timestamptz, notified_at timestamptz not null default now(), acknowledged_at timestamptz, completed_at timestamptz,
 unique(user_id,record_id,kind,message)
);
create table app_private.audit_log (
 id bigint generated always as identity primary key, actor uuid references app_private.profiles,
 module integer not null, record_id uuid, action text not null, detail jsonb not null default '{}', created_at timestamptz not null default now()
);

create function app_private.active_user() returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.profiles p join auth.users u on u.id=p.id where p.id=auth.uid() and p.enabled and u.email_confirmed_at is not null)
$$;
create function app_private.can(p_permission text) returns boolean language sql stable security definer set search_path='' as $$
 select app_private.active_user() and exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at and p_permission=any(r.permissions))
$$;
create function app_private.member() returns boolean language sql stable security definer set search_path='' as $$
 select app_private.active_user() and exists(select 1 from app_private.memberships where user_id=auth.uid() and status='Active')
$$;
create function app_private.require_user() returns uuid language plpgsql stable security definer set search_path='' as $$
 begin if not app_private.active_user() then raise exception 'Sign in with a verified, enabled account.' using errcode='42501'; end if; return auth.uid(); end
$$;
create function app_private.require_permission(p_permission text) returns void language plpgsql stable security definer set search_path='' as $$
 begin if not app_private.can(p_permission) then raise exception 'Permission denied: %',p_permission using errcode='42501'; end if; end
$$;
create function app_private.require_member() returns void language plpgsql stable security definer set search_path='' as $$
 begin if not app_private.member() then raise exception 'An active membership is required.' using errcode='42501'; end if; end
$$;
create function app_private.required(p_data jsonb,p_key text,p_max integer default 4000) returns text language plpgsql immutable set search_path='' as $$
 declare v text:=btrim(p_data->>p_key); begin
 if v is null or length(v)=0 or length(v)>p_max then raise exception 'Enter a valid % (1–% characters).',p_key,p_max using errcode='22023'; end if; return v; end
$$;
create function app_private.note(p_module integer,p_record uuid,p_action text,p_detail jsonb default '{}') returns void language sql security definer set search_path='' as $$
 insert into app_private.audit_log(actor,module,record_id,action,detail) values(auth.uid(),p_module,p_record,p_action,p_detail)
$$;
create function app_private.notify(p_user uuid,p_module integer,p_record uuid,p_kind text,p_message text) returns void language sql security definer set search_path='' as $$
 insert into app_private.notifications(user_id,module,record_id,kind,message) values(p_user,p_module,p_record,p_kind,p_message) on conflict do nothing
$$;
create function app_private.notify_role(p_permission text,p_module integer,p_record uuid,p_message text) returns void language sql security definer set search_path='' as $$
 insert into app_private.notifications(user_id,module,record_id,kind,message)
 select distinct a.user_id,p_module,p_record,'review',p_message from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 join app_private.profiles p on p.id=a.user_id where p.enabled and p_permission=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at
 on conflict do nothing
$$;
create function app_private.check_version(p_actual bigint,p_data jsonb) returns void language plpgsql immutable set search_path='' as $$
 begin if p_actual is null or (p_data->>'version')::bigint is distinct from p_actual then raise exception 'This record changed. Refresh before trying again.' using errcode='40001'; end if; end
$$;

-- Bootstrap identity from verified Auth only. Signup metadata never assigns a role.
create function public.initialize_account() returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=auth.uid(); begin
 if u is null or not exists(select 1 from auth.users where id=u and email_confirmed_at is not null) then raise exception 'Verify your email before continuing.' using errcode='42501'; end if;
 insert into app_private.profiles(id) values(u) on conflict do nothing;
 perform app_private.require_user();
 perform app_private.note(14,u,'session_access');
 return jsonb_build_object('id',u); end
$$;

create function app_private.membership_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); a app_private.applications; r app_private.renewals; v_id uuid; v_status text; req text; result jsonb;
 begin
 case p_action
 when 'save_profile' then
  update app_private.profiles set full_name=app_private.required(p_data,'full_name',160),phone=coalesce(p_data->>'phone',''),updated_at=now() where id=u;
  perform app_private.note(1,u,p_action); return jsonb_build_object('id',u);
 when 'save_application','submit_application' then
  if exists(select 1 from app_private.memberships where user_id=u) then raise exception 'An official membership already exists.'; end if;
  insert into app_private.applications(user_id) values(u) on conflict(user_id) do nothing;
  select * into a from app_private.applications where user_id=u for update;
  if a.status not in ('draft','incomplete','returned_for_correction') then raise exception 'This application is locked for review.'; end if;
  if a.version>1 or p_data ? 'version' then perform app_private.check_version(a.version,p_data); end if;
  if p_action='submit_application' then
   perform app_private.required(p_data,'full_name',160);
   if coalesce((p_data->>'confirmed')::boolean,false)=false then raise exception 'Confirm your application information.'; end if;
   for req in select unnest(application_requirements) from app_private.settings loop
    if not exists(select 1 from app_private.documents where record_id=a.id and owner_id=u and module=2 and requirement=req) then raise exception 'Missing requirement: %',req; end if;
   end loop;
  end if;
  update app_private.applications set full_name=coalesce(p_data->>'full_name',''),phone=coalesce(p_data->>'phone',''),student_number=coalesce(p_data->>'student_number',''),experience=coalesce(p_data->>'experience',''),confirmed=coalesce((p_data->>'confirmed')::boolean,false),
   status=case when p_action='submit_application' then 'pending' else status end,
   submitted_at=case when p_action='submit_application' then now() else submitted_at end,version=version+1,updated_at=now() where id=a.id returning to_jsonb(applications.*) into result;
  perform app_private.note(2,a.id,p_action);
  if p_action='submit_application' then perform app_private.notify_role('membership',7,a.id,'Application received'); end if;
  return result;
 when 'review_application' then
  perform app_private.require_permission('membership');
  select * into a from app_private.applications where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Application not found.'; end if;
  if a.user_id=u then raise exception 'You cannot review your own application.' using errcode='42501'; end if;
  perform app_private.check_version(a.version,p_data);
  v_status:=p_data->>'status';
  if a.status not in ('pending','under_review') or v_status not in ('under_review','incomplete','returned_for_correction','approved','rejected') then raise exception 'Invalid screening transition.'; end if;
  if v_status in ('incomplete','returned_for_correction','rejected') then perform app_private.required(p_data,'remark'); end if;
  if v_status='approved' then
   if not coalesce((p_data->>'requirements_verified')::boolean,false) then raise exception 'Verify supporting requirements before approval.'; end if;
   if not exists(select 1 from app_private.settings where (p_data->>'category')=any(membership_categories)) then raise exception 'Select a configured membership category.'; end if;
   insert into app_private.memberships(user_id,category,status,student_number,approved_by) values(a.user_id,p_data->>'category','Active',a.student_number,u);
   update app_private.profiles set full_name=a.full_name,phone=a.phone,updated_at=now() where id=a.user_id;
  end if;
  update app_private.applications set status=v_status,remark=coalesce(p_data->>'remark',''),reviewed_by=u,updated_at=now(),version=version+1 where id=a.id;
  perform app_private.note(7,a.id,p_action,jsonb_build_object('from',a.status,'to',v_status,'remark',p_data->>'remark'));
  perform app_private.notify(a.user_id,2,a.id,'screening',v_status||': '||coalesce(p_data->>'remark',''));
  return jsonb_build_object('id',a.id);
 when 'save_renewal','request_renewal' then
  if not exists(select 1 from app_private.memberships where user_id=u) then raise exception 'Membership not found.'; end if;
  perform pg_advisory_xact_lock(hashtextextended(u::text,1));
  select * into r from app_private.renewals where user_id=u and status in ('draft','pending','returned_for_correction') for update;
  if found then
   if r.status='pending' then raise exception 'This renewal is locked for review.';end if;
   perform app_private.check_version(r.version,p_data);
  else
   insert into app_private.renewals(user_id,information) values(u,app_private.required(p_data,'information')) returning * into r;
  end if;
  if p_action='request_renewal' then
   for req in select unnest(renewal_requirements) from app_private.settings loop
    if not exists(select 1 from app_private.documents where record_id=r.id and owner_id=u and module=1 and requirement=req) then raise exception 'Missing requirement: %',req;end if;
   end loop;
  end if;
  update app_private.renewals set information=app_private.required(p_data,'information'),status=case when p_action='request_renewal' then 'pending' else status end,version=version+1 where id=r.id returning to_jsonb(renewals.*) into result;
  perform app_private.note(1,r.id,p_action);
  if p_action='request_renewal' then perform app_private.notify_role('membership',7,r.id,'Renewal requires review');end if;
  return result;
 when 'review_renewal' then
  perform app_private.require_permission('membership');
  select * into r from app_private.renewals where id=(p_data->>'id')::uuid for update;
  if not found or r.status<>'pending' then raise exception 'Pending renewal not found.'; end if;
  if r.user_id=u then raise exception 'You cannot approve your own renewal.' using errcode='42501'; end if;
  perform app_private.check_version(r.version,p_data); v_status:=p_data->>'status';
  if v_status not in ('approved','rejected','returned_for_correction') then raise exception 'Invalid renewal decision.'; end if;
  perform app_private.required(p_data,'remark');
  update app_private.renewals set status=v_status,remark=p_data->>'remark',reviewed_by=u,version=version+1 where id=r.id;
  if v_status='approved' then update app_private.memberships set status='Active',updated_at=now(),version=version+1 where user_id=r.user_id; end if;
  perform app_private.note(7,r.id,p_action,p_data); perform app_private.notify(r.user_id,1,r.id,'renewal',v_status||': '||(p_data->>'remark')); return jsonb_build_object('id',r.id);
 when 'update_membership' then
  perform app_private.require_permission('membership'); perform app_private.required(p_data,'remark');
  v_id:=(p_data->>'user_id')::uuid;
  if v_id=u then raise exception 'Another membership officer must change your standing.' using errcode='42501'; end if;
  perform app_private.check_version((select version from app_private.memberships where user_id=v_id for update),p_data);
  if not exists(select 1 from app_private.settings where (p_data->>'category')=any(membership_categories)) then raise exception 'Invalid category.'; end if;
  update app_private.memberships set status=p_data->>'status',category=p_data->>'category',updated_at=now(),version=version+1 where user_id=v_id;
  perform app_private.note(7,v_id,p_action,p_data); return jsonb_build_object('id',v_id);
 else raise exception 'Unknown membership action.';
 end case; end
$$;

create function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; begin
 perform app_private.require_permission('administration');
 -- Serialize access changes to protect against two concurrent last-admin retirements.
 perform pg_advisory_xact_lock(14001);
 case p_action
 when 'configure_club' then
  update app_private.settings set club_name=app_private.required(p_data,'club_name',160),
   application_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'application_requirements','[]'))),
   renewal_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'renewal_requirements','[]'))),
   membership_categories=array(select jsonb_array_elements_text(coalesce(p_data->'membership_categories','["Regular member"]'))),
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now();
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 when 'retire_role' then
  update app_private.role_assignments set retired_at=now(),reason=reason||E'\nRetired: '||app_private.required(p_data,'reason') where id=(p_data->>'id')::uuid returning id into v_id;
 when 'set_account_status' then
  v_id:=(p_data->>'user_id')::uuid; perform app_private.required(p_data,'reason');
  update app_private.profiles set enabled=(p_data->>'enabled')::boolean,updated_at=now() where id=v_id;
 else raise exception 'Unknown administrative action.';
 end case;
 if not exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id join app_private.profiles p on p.id=a.user_id
 where p.enabled and 'administration'=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at) then raise exception 'Keep at least one active administrator.'; end if;
 perform app_private.note(14,v_id,p_action,p_data); return jsonb_build_object('id',v_id); end
$$;

-- No application writes are exposed as direct DML. All transitions pass the checked functions.
do $$ declare t text; begin
 foreach t in array array['settings','profiles','roles','role_assignments','memberships','applications','renewals','documents','notifications','audit_log'] loop
  execute format('alter table app_private.%I enable row level security',t);
  execute format('revoke all on app_private.%I from public,anon,authenticated',t);
  execute format('grant select on app_private.%I to authenticated',t);
 end loop; end $$;
create policy settings_read on app_private.settings for select to authenticated using(app_private.active_user());
create policy profile_read on app_private.profiles for select to authenticated using(app_private.active_user() and (id=auth.uid() or app_private.can('membership') or app_private.can('administration')));
create policy role_read on app_private.roles for select to authenticated using(app_private.active_user());
create policy assignment_read on app_private.role_assignments for select to authenticated using(app_private.active_user() and (user_id=auth.uid() or app_private.can('administration')));
create policy membership_read on app_private.memberships for select to authenticated using(app_private.active_user() and (user_id=auth.uid() or app_private.can('membership')));
create policy application_read on app_private.applications for select to authenticated using(app_private.active_user() and (user_id=auth.uid() or app_private.can('membership')));
create policy renewal_read on app_private.renewals for select to authenticated using(app_private.active_user() and (user_id=auth.uid() or app_private.can('membership')));
create policy notification_read on app_private.notifications for select to authenticated using(app_private.active_user() and user_id=auth.uid());
create policy audit_read on app_private.audit_log for select to authenticated using(app_private.can('administration'));
revoke all on all functions in schema app_private from public,anon,authenticated;
grant execute on function app_private.active_user(),app_private.can(text),app_private.member() to authenticated;
revoke all on function public.initialize_account() from public,anon;
grant execute on function public.initialize_account() to authenticated;

-- SOURCE: 202610010002_api.sql
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action in ('save_profile','save_application','submit_application','review_application','request_renewal','review_renewal','update_membership') then
  return app_private.membership_command(p_action,p_data);
 elsif p_action in ('configure_club','define_role','assign_role','retire_role','set_account_status') then
  return app_private.admin_command(p_action,p_data);
 elsif p_action='acknowledge_notification' then
  update app_private.notifications set acknowledged_at=now() where id=(p_data->>'id')::uuid and user_id=auth.uid();
  return '{}';
 end if;
 raise exception 'Unknown action.';
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
-- Security-invoker: RLS applies to every source query, including officer views.
create function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
declare result jsonb; begin
 if not app_private.active_user() then raise exception 'Sign in with a verified, enabled account.' using errcode='42501'; end if;
 select jsonb_build_object(
 'user_id',auth.uid(),
 'permissions',(select coalesce(jsonb_agg(distinct p),'[]') from app_private.role_assignments a join app_private.roles r on r.id=a.role_id cross join unnest(r.permissions) p where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at),
 'settings',(select to_jsonb(s) from app_private.settings s),
 'profiles',(select coalesce(jsonb_agg(p),'[]') from app_private.profiles p),
 'memberships',(select coalesce(jsonb_agg(m),'[]') from app_private.memberships m),
 'applications',(select coalesce(jsonb_agg(a),'[]') from app_private.applications a),
 'renewals',(select coalesce(jsonb_agg(r),'[]') from app_private.renewals r),
 'roles',(select coalesce(jsonb_agg(r),'[]') from app_private.roles r),
 'role_assignments',(select coalesce(jsonb_agg(a),'[]') from app_private.role_assignments a),
 'documents',(select coalesce(jsonb_agg(d),'[]') from app_private.documents d),
 'notifications',(select coalesce(jsonb_agg(n order by notified_at desc),'[]') from app_private.notifications n),
 'audit_log',(select coalesce(jsonb_agg(l order by created_at desc),'[]') from (select * from app_private.audit_log order by created_at desc limit 200) l)
 ) into result;
 return result;
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202610010003_activities.sql
create table app_private.events (
 id uuid primary key default gen_random_uuid(), title text not null check(length(title) between 1 and 200), description text not null,
 venue text not null, starts_at timestamptz not null, ends_at timestamptz not null,
 registration_opens timestamptz not null, registration_closes timestamptz not null,
 capacity integer not null check(capacity>0), requirements text not null default '', assigned_personnel text not null default '',
 published boolean not null default false, archived boolean not null default false,
 created_by uuid not null references app_private.profiles, updated_at timestamptz not null default now(), version bigint not null default 1,
 check(ends_at>starts_at),check(registration_closes>=registration_opens),check(registration_closes<=starts_at)
);
create table app_private.announcements (
 id uuid primary key default gen_random_uuid(), event_id uuid references app_private.events,
 title text not null, message text not null, created_by uuid not null references app_private.profiles, created_at timestamptz not null default now()
);
create table app_private.registrations (
 id uuid primary key default gen_random_uuid(), event_id uuid not null references app_private.events,
 user_id uuid not null references app_private.memberships(user_id), created_at timestamptz not null default now(), unique(event_id,user_id)
);
create table app_private.attendance_windows (
 id uuid primary key default gen_random_uuid(), event_id uuid not null references app_private.events,
 token uuid unique not null default gen_random_uuid(), opens_at timestamptz not null, closes_at timestamptz not null, check(closes_at>opens_at)
);
create table app_private.attendance (
 id uuid primary key default gen_random_uuid(), event_id uuid not null references app_private.events,
 user_id uuid not null references app_private.memberships(user_id), status text not null check(status in ('present','absent','excused')),
 method text not null check(method in ('qr','manual')), encoded_by uuid not null references app_private.profiles,
 remark text not null default '', validated boolean not null default true, created_at timestamptz not null default now(), version bigint not null default 1,
 unique(event_id,user_id)
);
create table app_private.corrections (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references app_private.profiles,
 module integer not null check(module in (4,8,11)), record_id uuid not null, request text not null,
 status text not null default 'pending' check(status in ('pending','approved','rejected')),
 decision text not null default '', reviewed_by uuid references app_private.profiles, created_at timestamptz not null default now()
);
create function app_private.activity_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); e app_private.events; v_id uuid; v_user uuid; w app_private.attendance_windows; a app_private.attendance; c app_private.corrections; begin
 case p_action
 when 'save_event' then
  perform app_private.require_permission('activities');
  if nullif(p_data->>'id','') is null then
   insert into app_private.events(title,description,venue,starts_at,ends_at,registration_opens,registration_closes,capacity,requirements,assigned_personnel,created_by)
   values(app_private.required(p_data,'title',200),app_private.required(p_data,'description'),app_private.required(p_data,'venue',200),(p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,(p_data->>'registration_opens')::timestamptz,(p_data->>'registration_closes')::timestamptz,(p_data->>'capacity')::integer,coalesce(p_data->>'requirements',''),coalesce(p_data->>'assigned_personnel',''),u) returning id into v_id;
  else
   select * into e from app_private.events where id=(p_data->>'id')::uuid for update;
   if not found or e.archived then raise exception 'Editable event not found.'; end if; perform app_private.check_version(e.version,p_data);
   if (p_data->>'capacity')::integer<(select count(*) from app_private.registrations where event_id=e.id) then raise exception 'Capacity cannot be lower than existing registrations.'; end if;
   update app_private.events set title=app_private.required(p_data,'title',200),description=app_private.required(p_data,'description'),venue=app_private.required(p_data,'venue',200),starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,registration_opens=(p_data->>'registration_opens')::timestamptz,registration_closes=(p_data->>'registration_closes')::timestamptz,capacity=(p_data->>'capacity')::integer,requirements=coalesce(p_data->>'requirements',''),assigned_personnel=coalesce(p_data->>'assigned_personnel',''),version=version+1,updated_at=now() where id=e.id;
   v_id:=e.id;
   for v_user in select user_id from app_private.registrations where event_id=e.id loop perform app_private.notify(v_user,3,e.id,'event_update','Event details updated: '||(p_data->>'title')); end loop;
  end if;
 when 'publish_event','archive_event' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Event not found.'; end if; perform app_private.check_version(e.version,p_data);
  update app_private.events set published=true,archived=(p_action='archive_event'),version=version+1 where id=e.id;v_id:=e.id;
 when 'publish_announcement' then
  perform app_private.require_permission('activities');
  insert into app_private.announcements(event_id,title,message,created_by) values(nullif(p_data->>'event_id','')::uuid,app_private.required(p_data,'title',200),app_private.required(p_data,'message'),u) returning id into v_id;
  for v_user in select user_id from app_private.memberships where status='Active' loop perform app_private.notify(v_user,3,v_id,'announcement',p_data->>'title'); end loop;
 when 'register_event' then
  perform app_private.require_member(); select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
  if not found or not e.published or e.archived or now()<e.registration_opens or now()>e.registration_closes then raise exception 'Registration is not open.'; end if;
  select id into v_id from app_private.registrations where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id); end if;
  if (select count(*) from app_private.registrations where event_id=e.id)>=e.capacity then raise exception 'This activity is full.'; end if;
  insert into app_private.registrations(event_id,user_id) values(e.id,u) returning id into v_id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at) values(u,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
 when 'open_attendance' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=(p_data->>'event_id')::uuid;
  if not found or not e.published or e.archived then raise exception 'Active event not found.'; end if;
  insert into app_private.attendance_windows(event_id,opens_at,closes_at) values(e.id,(p_data->>'opens_at')::timestamptz,(p_data->>'closes_at')::timestamptz) returning id into v_id;
 when 'check_in' then
  perform app_private.require_member();
  select * into w from app_private.attendance_windows where token=(p_data->>'token')::uuid and now() between opens_at and closes_at;
  if not found or not exists(select 1 from app_private.events where id=w.event_id and published and not archived) then raise exception 'Attendance code is invalid or expired.'; end if;
  if not exists(select 1 from app_private.registrations where user_id=u and event_id=w.event_id) then raise exception 'Register before recording attendance.'; end if;
  insert into app_private.attendance(event_id,user_id,status,method,encoded_by) values(w.event_id,u,'present','qr',u) on conflict(event_id,user_id) do nothing returning id into v_id;
 when 'record_attendance' then
  perform app_private.require_permission('activities'); perform app_private.required(p_data,'remark');
  v_user:=(p_data->>'user_id')::uuid;
  if not exists(select 1 from app_private.registrations where user_id=v_user and event_id=(p_data->>'event_id')::uuid) then raise exception 'Registration not found.'; end if;
  select * into a from app_private.attendance where user_id=v_user and event_id=(p_data->>'event_id')::uuid for update;
  if found then perform app_private.check_version(a.version,p_data); end if;
  insert into app_private.attendance(event_id,user_id,status,method,encoded_by,remark) values((p_data->>'event_id')::uuid,v_user,p_data->>'status','manual',u,p_data->>'remark')
   on conflict(event_id,user_id) do update set status=excluded.status,method='manual',encoded_by=u,remark=excluded.remark,version=attendance.version+1 returning id into v_id;
  perform app_private.notify(v_user,4,v_id,'attendance','Attendance updated: '||(p_data->>'status'));
 when 'request_attendance_correction' then
  select * into a from app_private.attendance where id=(p_data->>'record_id')::uuid and user_id=u;
  if not found then raise exception 'Your attendance record was not found.'; end if;
  insert into app_private.corrections(user_id,module,record_id,request) values(u,8,a.id,app_private.required(p_data,'request')) returning id into v_id;
  perform app_private.notify_role('activities',8,v_id,'Attendance correction requested');
 when 'review_attendance_correction' then
  perform app_private.require_permission('activities');
  select * into c from app_private.corrections where id=(p_data->>'id')::uuid and module=8 for update;
  if not found or c.status<>'pending' then raise exception 'Pending correction not found.'; end if;
  if p_data->>'status' not in ('approved','rejected') then raise exception 'Invalid correction decision.'; end if;
  perform app_private.required(p_data,'decision');
  if p_data->>'status'='approved' then update app_private.attendance set status=p_data->>'attendance_status',remark=p_data->>'decision',encoded_by=u,version=version+1 where id=c.record_id; end if;
  update app_private.corrections set status=p_data->>'status',decision=p_data->>'decision',reviewed_by=u where id=c.id;v_id:=c.id;
  perform app_private.notify(c.user_id,4,c.id,'correction',p_data->>'decision');
 else raise exception 'Unknown activity action.';
 end case;
 perform app_private.note(case when p_action in ('register_event','check_in') then 3 else 8 end,v_id,p_action,p_data);
 return jsonb_build_object('id',v_id); end
$$;
do $$ declare t text; begin foreach t in array array['events','announcements','registrations','attendance_windows','attendance','corrections'] loop
 execute format('alter table app_private.%I enable row level security',t);
 execute format('revoke all on app_private.%I from public,anon,authenticated',t);
 execute format('grant select on app_private.%I to authenticated',t);
end loop;end $$;
create policy event_read on app_private.events for select to authenticated using(app_private.can('activities') or (app_private.member() and published));
create policy announcement_read on app_private.announcements for select to authenticated using(app_private.can('activities') or app_private.member());
create policy registration_read on app_private.registrations for select to authenticated using(app_private.can('activities') or (app_private.active_user() and user_id=auth.uid()));
create policy window_read on app_private.attendance_windows for select to authenticated using(app_private.can('activities'));
create policy attendance_read on app_private.attendance for select to authenticated using(app_private.can('activities') or (app_private.active_user() and user_id=auth.uid()));
create policy correction_read on app_private.corrections for select to authenticated using((app_private.active_user() and user_id=auth.uid()) or (module=8 and app_private.can('activities')) or (module=11 and app_private.can('training')));
revoke all on function app_private.activity_command(text,jsonb) from public,anon,authenticated;

-- SOURCE: 202610010004_elections.sql
create table app_private.academic_terms(id uuid primary key default gen_random_uuid(),name text unique not null,sequence integer unique not null,starts_on date not null,ends_on date not null,check(ends_on>starts_on));
create table app_private.member_terms(user_id uuid references app_private.memberships(user_id),term_id uuid references app_private.academic_terms,completed boolean not null default false,primary key(user_id,term_id));
create table app_private.elections (
 id uuid primary key default gen_random_uuid(),title text not null,kind text not null check(kind in ('annual','special')),
 term_end date not null,announces_at timestamptz not null,candidacy_closes timestamptz not null,voting_opens timestamptz not null,voting_closes timestamptz not null,result_due timestamptz not null,
 threshold numeric not null default 60 check(threshold between 0 and 100),residency_terms integer not null default 2 check(residency_terms>=0),
 rules text not null,rules_validated boolean not null default false,method text not null check(method in ('digital','paper','external')),
 positions text[] not null default array['President','Vice President'], status text not null default 'draft' check(status in ('draft','announced','voting','closed','requires_revote','confirmed','archived')),
 external_turnout integer check(external_turnout>=0),external_results jsonb,confirmation text,confirmed_by uuid references app_private.profiles,
 created_by uuid not null references app_private.profiles,version bigint not null default 1,
 check(candidacy_closes>announces_at),check(voting_opens>=candidacy_closes),check(voting_closes>voting_opens),check(result_due>=voting_closes)
);
create table app_private.candidates (
 id uuid primary key default gen_random_uuid(),election_id uuid not null references app_private.elections,
 user_id uuid not null references app_private.memberships(user_id),position text not null,statement text not null,
 nominated_by uuid not null references app_private.profiles,confirmed boolean not null default false,reviewed_by uuid references app_private.profiles,remark text not null default '',
 unique(election_id,user_id,position)
);
create table app_private.eligible_voters (
 election_id uuid references app_private.elections,user_id uuid references app_private.memberships(user_id),confirmed_by uuid not null references app_private.profiles,reason text not null,
 primary key(election_id,user_id)
);
-- Choices never contain user ID, cast timestamp, device ID, token or participation foreign key.
create table app_private.ballots(id uuid primary key default gen_random_uuid(),election_id uuid not null references app_private.elections,choices jsonb not null);
create table app_private.voter_participation(election_id uuid,user_id uuid,primary key(election_id,user_id),foreign key(election_id,user_id) references app_private.eligible_voters);
create table app_private.leadership_records (
 id uuid primary key default gen_random_uuid(),election_id uuid references app_private.elections,user_id uuid references app_private.profiles,
 position text not null,kind text not null check(kind in ('appointment','deliberation','vacancy','succession','transition')),
 starts_on date,ends_on date,documentation text not null,created_by uuid not null references app_private.profiles,created_at timestamptz not null default now()
);
create function app_private.residency(p_user uuid,p_count integer) returns boolean language sql stable security definer set search_path='' as $$
 select p_count=0 or (select count(*) from (
  select t.sequence,row_number() over(order by t.sequence desc) rn from app_private.member_terms mt join app_private.academic_terms t on t.id=mt.term_id
  where mt.user_id=p_user and mt.completed and t.ends_on<current_date order by t.sequence desc limit p_count
 ) q where q.sequence+q.rn=(select max(t.sequence)+1 from app_private.member_terms mt join app_private.academic_terms t on t.id=mt.term_id where mt.user_id=p_user and mt.completed and t.ends_on<current_date))=p_count
$$;
create function app_private.election_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); e app_private.elections; v_id uuid; v_user uuid; n integer; total integer; position_name text; choice text; tally jsonb; begin
 if p_action not in ('file_candidacy','nominate_candidate','cast_ballot') then perform app_private.require_permission('elections'); end if;
 case p_action
 when 'save_term' then
  insert into app_private.academic_terms(name,sequence,starts_on,ends_on) values(app_private.required(p_data,'name',160),(p_data->>'sequence')::integer,(p_data->>'starts_on')::date,(p_data->>'ends_on')::date) returning id into v_id;
 when 'record_residency' then
  insert into app_private.member_terms(user_id,term_id,completed) values((p_data->>'user_id')::uuid,(p_data->>'term_id')::uuid,(p_data->>'completed')::boolean)
  on conflict(user_id,term_id) do update set completed=excluded.completed;v_id:=(p_data->>'user_id')::uuid;
 when 'create_election' then
  insert into app_private.elections(title,kind,term_end,announces_at,candidacy_closes,voting_opens,voting_closes,result_due,threshold,residency_terms,rules,rules_validated,method,positions,created_by)
  values(app_private.required(p_data,'title',200),p_data->>'kind',(p_data->>'term_end')::date,(p_data->>'announces_at')::timestamptz,(p_data->>'candidacy_closes')::timestamptz,(p_data->>'voting_opens')::timestamptz,(p_data->>'voting_closes')::timestamptz,(p_data->>'result_due')::timestamptz,coalesce((p_data->>'threshold')::numeric,60),coalesce((p_data->>'residency_terms')::integer,2),app_private.required(p_data,'rules'),coalesce((p_data->>'rules_validated')::boolean,false),p_data->>'method',array(select jsonb_array_elements_text(coalesce(p_data->'positions','["President","Vice President"]'))),u) returning id into v_id;
 when 'file_candidacy','nominate_candidate' then
  perform app_private.require_member();
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status<>'announced' or now()<e.announces_at or now()>e.candidacy_closes then raise exception 'Candidacy filing is not open.'; end if;
  if not (p_data->>'position'=any(e.positions)) then raise exception 'Select a configured elected position.'; end if;
  v_user:=case when p_action='nominate_candidate' then (p_data->>'user_id')::uuid else u end;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') then raise exception 'An active member is required for candidacy.';end if;
  insert into app_private.candidates(election_id,user_id,position,statement,nominated_by) values(e.id,v_user,p_data->>'position',app_private.required(p_data,'statement'),u) returning id into v_id;
  perform app_private.notify(v_user,9,v_id,'nomination','Candidacy recorded for '||e.title||'; eligibility is subject to officer review.');
 when 'confirm_candidate' then
  select e1.* into e from app_private.elections e1 join app_private.candidates c on c.election_id=e1.id where c.id=(p_data->>'id')::uuid for update of e1;
  if not found or e.status not in ('draft','announced') then raise exception 'Candidate review is closed.'; end if;
  update app_private.candidates set confirmed=(p_data->>'confirmed')::boolean,reviewed_by=u,remark=app_private.required(p_data,'remark') where id=(p_data->>'id')::uuid returning id into v_id;
 when 'confirm_voter' then
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status not in ('draft','announced') then raise exception 'Voter roll is locked.'; end if;
  v_user:=(p_data->>'user_id')::uuid;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') or not app_private.residency(v_user,e.residency_terms) then raise exception 'Active membership and consecutive completed residency terms are required.'; end if;
  insert into app_private.eligible_voters values(e.id,v_user,u,app_private.required(p_data,'reason')) on conflict(election_id,user_id) do update set confirmed_by=u,reason=excluded.reason; v_id:=e.id;
 when 'cast_ballot' then
  perform app_private.require_member();
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status<>'voting' or e.method<>'digital' or now()<e.voting_opens or now()>e.voting_closes then raise exception 'Voting is not open.'; end if;
  if not exists(select 1 from app_private.eligible_voters where election_id=e.id and user_id=u) then raise exception 'You are not a confirmed eligible voter.' using errcode='42501'; end if;
  if jsonb_typeof(p_data->'choices') is distinct from 'object' or (select count(*) from jsonb_object_keys(p_data->'choices'))<>cardinality(e.positions) then raise exception 'Complete one choice per position.'; end if;
  foreach position_name in array e.positions loop
   choice:=p_data->'choices'->>position_name;
   if not exists(select 1 from app_private.candidates where id=choice::uuid and election_id=e.id and position=position_name and confirmed) then raise exception 'Invalid candidate choice.'; end if;
  end loop;
  insert into app_private.voter_participation values(e.id,u);
  insert into app_private.ballots(election_id,choices) values(e.id,p_data->'choices');
  -- Never log ballot payloads or return a ballot identifier.
  return jsonb_build_object('accepted',true);
 when 'set_election_status' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Election not found.'; end if;perform app_private.check_version(e.version,p_data);
  if not e.rules_validated then raise exception 'Validate the election rules before proceeding.'; end if;
  if not ((e.status='draft' and p_data->>'status'='announced' and now()>=e.announces_at)
   or (e.status='announced' and p_data->>'status'='voting' and now() between e.voting_opens and e.voting_closes)
   or (e.status='voting' and p_data->>'status'='closed' and now()>=e.voting_closes)
   or (e.status in ('confirmed','requires_revote') and p_data->>'status'='archived')) then raise exception 'Invalid election transition or deadline.'; end if;
  if p_data->>'status'='voting' then
   if not exists(select 1 from app_private.eligible_voters where election_id=e.id) then raise exception 'Confirm the voter roll first.'; end if;
   foreach position_name in array e.positions loop
    if not exists(select 1 from app_private.candidates where election_id=e.id and position=position_name and confirmed) then raise exception 'Confirm candidates for each position first.'; end if;
   end loop;
  end if;
  update app_private.elections set status=p_data->>'status',version=version+1 where id=e.id;v_id:=e.id;
 when 'record_external_results' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found or e.method='digital' or e.status<>'closed' then raise exception 'Closed external/paper election required.'; end if;
  perform app_private.check_version(e.version,p_data);
  total:=(select count(*) from app_private.eligible_voters where election_id=e.id);n:=(p_data->>'turnout')::integer;
  if n<0 or n>total then raise exception 'Turnout exceeds eligible voters.'; end if;
  if jsonb_typeof(p_data->'results') is distinct from 'array' then raise exception 'Enter candidate tallies.';end if;
  if jsonb_array_length(p_data->'results')<>(select count(*) from app_private.candidates where election_id=e.id and confirmed)
   or (select count(distinct v->>'id') from jsonb_array_elements(p_data->'results') v)<>jsonb_array_length(p_data->'results') then raise exception 'Include each confirmed candidate once.';end if;
  for tally in select value from jsonb_array_elements(p_data->'results') loop
   if not exists(select 1 from app_private.candidates where id=(tally->>'id')::uuid and election_id=e.id and confirmed)
    or coalesce(tally->>'votes','')!~'^[0-9]+$' or (tally->>'votes')::numeric>n then raise exception 'Invalid candidate tally.';end if;
  end loop;
  if exists(select 1 from jsonb_array_elements(p_data->'results') v join app_private.candidates c on c.id=(v->>'id')::uuid group by c.position having sum((v->>'votes')::numeric)>n) then raise exception 'Position vote totals exceed turnout.';end if;
  update app_private.elections set external_turnout=n,external_results=p_data->'results',confirmation=app_private.required(p_data,'verification'),version=version+1 where id=e.id;v_id:=e.id;
 when 'confirm_results' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found or e.status<>'closed' then raise exception 'Close voting before confirming results.'; end if;
  perform app_private.check_version(e.version,p_data);
  total:=(select count(*) from app_private.eligible_voters where election_id=e.id);
  n:=case when e.method='digital' then (select count(*) from app_private.voter_participation where election_id=e.id) else e.external_turnout end;
  if n is null or total=0 then raise exception 'Verified turnout and voter roll required.'; end if;
  update app_private.elections set status=case when 100.0*n/total<e.threshold then 'requires_revote' else 'confirmed' end,confirmed_by=u,confirmation=app_private.required(p_data,'confirmation'),version=version+1 where id=e.id;v_id:=e.id;
 when 'record_leadership' then
  insert into app_private.leadership_records(election_id,user_id,position,kind,starts_on,ends_on,documentation,created_by)
  values(nullif(p_data->>'election_id','')::uuid,nullif(p_data->>'user_id','')::uuid,app_private.required(p_data,'position',160),p_data->>'kind',nullif(p_data->>'starts_on','')::date,nullif(p_data->>'ends_on','')::date,app_private.required(p_data,'documentation'),u) returning id into v_id;
 else raise exception 'Unknown election action.';
 end case;
 perform app_private.note(9,v_id,p_action,p_data); return jsonb_build_object('id',v_id);end
$$;
create function public.election_summary(p_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
 declare e app_private.elections;n integer;total integer;tallies jsonb;begin
 perform app_private.require_user();select * into e from app_private.elections where id=p_id;
 if not found or not (app_private.can('elections') or (app_private.member() and e.status in ('confirmed','requires_revote','archived'))) then raise exception 'Results are restricted until official confirmation.' using errcode='42501';end if;
 total:=(select count(*) from app_private.eligible_voters where election_id=p_id);
 n:=case when e.method='digital' then (select count(*) from app_private.voter_participation where election_id=p_id) else e.external_turnout end;
 if e.status in ('closed','confirmed','requires_revote','archived') then
  if e.method='digital' then select coalesce(jsonb_agg(t),'[]') into tallies from (select c.id,c.position,c.user_id,count(b.id) as votes from app_private.candidates c left join app_private.ballots b on b.election_id=c.election_id and b.choices->>c.position=c.id::text where c.election_id=p_id and c.confirmed group by c.id) t;
  else tallies:=e.external_results; end if;
 end if;
 return jsonb_build_object('eligible',total,'participation',n,'rate',round(100.0*n/nullif(total,0),2),'threshold',e.threshold,'status',e.status,'tallies',tallies);end
$$;
do $$declare t text;begin foreach t in array array['academic_terms','member_terms','elections','candidates','eligible_voters','ballots','voter_participation','leadership_records'] loop
 execute format('alter table app_private.%I enable row level security',t);
 execute format('revoke all on app_private.%I from public,anon,authenticated',t);
 if t<>'ballots' then execute format('grant select on app_private.%I to authenticated',t);end if;
end loop;end $$;
create policy terms_read on app_private.academic_terms for select to authenticated using(app_private.member() or app_private.can('elections'));
create policy member_terms_read on app_private.member_terms for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy election_read on app_private.elections for select to authenticated using(app_private.can('elections') or (app_private.member() and status<>'draft'));
create policy candidate_read on app_private.candidates for select to authenticated using(app_private.can('elections') or (app_private.member() and (confirmed or user_id=auth.uid() or nominated_by=auth.uid())));
create policy voters_read on app_private.eligible_voters for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy participation_read on app_private.voter_participation for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy leadership_read on app_private.leadership_records for select to authenticated using(app_private.can('elections') or app_private.can('administration'));
revoke all on function app_private.election_command(text,jsonb),app_private.residency(uuid,integer) from public,anon,authenticated;
revoke all on function public.election_summary(uuid) from public,anon;grant execute on function public.election_summary(uuid) to authenticated;

-- SOURCE: 202610010005_equipment_training_finance.sql
create table app_private.equipment (
 id uuid primary key default gen_random_uuid(),label text unique not null,category text not null,specifications text not null,
 handedness text not null check(handedness in ('left','right','ambidextrous','not_applicable')),draw_weight numeric check(draw_weight>=0),bow_setup text not null default '',
 condition text not null check(condition in ('serviceable','maintenance','damaged','retired')),acquired_on date,notes text not null default '',version bigint not null default 1
);
create table app_private.borrowings (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.memberships(user_id),equipment_id uuid not null references app_private.equipment,
 event_id uuid references app_private.events,starts_at timestamptz not null,ends_at timestamptz not null,handedness text not null,maximum_draw_weight numeric check(maximum_draw_weight>=0),bow_setup text not null default '',purpose text not null,
 status text not null default 'pending' check(status in ('pending','approved','released','returned','rejected','returned_for_correction')),
 reviewed_by uuid references app_private.profiles,remark text not null default '',released_at timestamptz,returned_at timestamptz,version bigint not null default 1,check(ends_at>starts_at)
);
create index borrowing_period on app_private.borrowings(equipment_id,starts_at,ends_at);
create table app_private.equipment_history(id uuid primary key default gen_random_uuid(),equipment_id uuid not null references app_private.equipment,borrowing_id uuid references app_private.borrowings,condition text not null,notes text not null,actor uuid not null references app_private.profiles,created_at timestamptz not null default now());
create table app_private.training_records (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.memberships(user_id),event_id uuid references app_private.events,
 training_date date not null,distance numeric not null check(distance>0),target_face text not null,category text not null,scoring_format text not null,
 score numeric not null check(score>=0),maximum_score numeric not null check(maximum_score>0),grouping text not null default '',level text not null default '',observations text not null default '',feedback text not null default '',
 source text not null check(source in ('personal','coach','csv')),validation text not null check(validation in ('personal','pending','validated','returned_for_correction')),
 encoded_by uuid not null references app_private.profiles,encoded_at timestamptz not null default now(),validated_by uuid references app_private.profiles,validated_at timestamptz,
 import_key text unique,version bigint not null default 1,check(score<=maximum_score)
);
create table app_private.financial_records (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.profiles,
 kind text not null check(kind in ('payment','expense','reimbursement')),purpose text not null,amount numeric(12,2) not null check(amount>0),
 reference text not null default '',status text not null default 'draft' check(status in ('draft','pending_verification','verified','incomplete','rejected','returned_for_correction')),
 remark text not null default '',reviewed_by uuid references app_private.profiles,created_at timestamptz not null default now(),verified_at timestamptz,version bigint not null default 1
);
create table app_private.financial_approvals (
 record_id uuid references app_private.financial_records,office text check(office in ('president','finance')),approved_by uuid not null references app_private.profiles,approved_at timestamptz not null default now(),remark text not null,
 primary key(record_id,office)
);
create table app_private.fee_assessments(id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.profiles,purpose text not null,amount numeric(12,2) not null check(amount>0),due_on date not null,payment_id uuid unique references app_private.financial_records,created_by uuid not null references app_private.profiles);

create function app_private.equipment_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;e app_private.equipment;b app_private.borrowings;v_status text;begin
 case p_action
 when 'save_equipment' then
  perform app_private.require_permission('equipment');
  if nullif(p_data->>'id','') is null then
   insert into app_private.equipment(label,category,specifications,handedness,draw_weight,bow_setup,condition,acquired_on,notes)
   values(app_private.required(p_data,'label',160),app_private.required(p_data,'category',100),app_private.required(p_data,'specifications'),p_data->>'handedness',nullif(p_data->>'draw_weight','')::numeric,coalesce(p_data->>'bow_setup',''),p_data->>'condition',nullif(p_data->>'acquired_on','')::date,coalesce(p_data->>'notes','')) returning id into v_id;
  else
   select * into e from app_private.equipment where id=(p_data->>'id')::uuid for update;if not found then raise exception 'Equipment not found.';end if;perform app_private.check_version(e.version,p_data);
   update app_private.equipment set label=app_private.required(p_data,'label',160),category=app_private.required(p_data,'category',100),specifications=app_private.required(p_data,'specifications'),handedness=p_data->>'handedness',draw_weight=nullif(p_data->>'draw_weight','')::numeric,bow_setup=coalesce(p_data->>'bow_setup',''),condition=p_data->>'condition',notes=coalesce(p_data->>'notes',''),version=version+1 where id=e.id;v_id:=e.id;
  end if;
  insert into app_private.equipment_history(equipment_id,condition,notes,actor) values(v_id,p_data->>'condition',coalesce(p_data->>'notes',''),u);
 when 'request_borrowing' then
  perform app_private.require_member();
  select * into e from app_private.equipment where id=(p_data->>'equipment_id')::uuid;
  if not found or e.condition<>'serviceable' then raise exception 'Select serviceable equipment.';end if;
  if (p_data->>'starts_at')::timestamptz<now() then raise exception 'Choose a future borrowing period.';end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.borrowings(user_id,equipment_id,event_id,starts_at,ends_at,handedness,maximum_draw_weight,bow_setup,purpose)
   values(u,e.id,nullif(p_data->>'event_id','')::uuid,(p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,p_data->>'handedness',nullif(p_data->>'maximum_draw_weight','')::numeric,coalesce(p_data->>'bow_setup',''),app_private.required(p_data,'purpose')) returning id into v_id;
  else
   select * into b from app_private.borrowings where id=(p_data->>'id')::uuid and user_id=u for update;
   if not found or b.status<>'returned_for_correction' then raise exception 'Editable borrowing request not found.';end if;perform app_private.check_version(b.version,p_data);
   update app_private.borrowings set equipment_id=e.id,starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,handedness=p_data->>'handedness',maximum_draw_weight=nullif(p_data->>'maximum_draw_weight','')::numeric,bow_setup=coalesce(p_data->>'bow_setup',''),purpose=app_private.required(p_data,'purpose'),status='pending',version=version+1 where id=b.id;v_id:=b.id;
  end if;
  perform app_private.notify_role('equipment',10,v_id,'Borrowing request requires review');
 when 'review_borrowing' then
  perform app_private.require_permission('equipment');
  select * into b from app_private.borrowings where id=(p_data->>'id')::uuid for update;if not found then raise exception 'Borrowing request not found.';end if;
  perform app_private.check_version(b.version,p_data);v_status:=p_data->>'status';
  if not ((b.status='pending' and v_status in ('approved','rejected','returned_for_correction')) or (b.status='approved' and v_status in ('released','rejected')) or (b.status='released' and v_status='returned')) then raise exception 'Invalid borrowing transition.';end if;
  perform app_private.required(p_data,'remark');
  select * into e from app_private.equipment where id=b.equipment_id for update;
  if v_status in ('approved','released') then
   if e.condition<>'serviceable' then raise exception 'Equipment is not serviceable.';end if;
   if exists(select 1 from app_private.borrowings x where x.id<>b.id and x.equipment_id=e.id and x.status in ('approved','released') and (x.status='released' and x.ends_at<now() or tstzrange(x.starts_at,x.ends_at,'[)') && tstzrange(b.starts_at,b.ends_at,'[)'))) then raise exception 'Equipment has a conflicting reservation or overdue return.';end if;
   if not coalesce((p_data->>'compatibility_confirmed')::boolean,false) then raise exception 'Officer must confirm safety and compatibility.';end if;
   if (e.handedness not in ('ambidextrous','not_applicable') and e.handedness<>b.handedness) or (b.maximum_draw_weight is not null and e.draw_weight>b.maximum_draw_weight) or (b.bow_setup<>'' and b.bow_setup<>e.bow_setup) then raise exception 'Equipment is incompatible with this request. Select an alternative.';end if;
  end if;
  update app_private.borrowings set status=v_status,reviewed_by=u,remark=p_data->>'remark',released_at=case when v_status='released' then now() else released_at end,returned_at=case when v_status='returned' then now() else returned_at end,version=version+1 where id=b.id;
  if v_status='returned' then
   update app_private.equipment set condition=p_data->>'condition',version=version+1 where id=e.id;
   insert into app_private.equipment_history(equipment_id,borrowing_id,condition,notes,actor) values(e.id,b.id,p_data->>'condition',p_data->>'remark',u);
  end if;
  v_id:=b.id;perform app_private.notify(b.user_id,5,b.id,'borrowing',v_status||': '||(p_data->>'remark'));
 else raise exception 'Unknown equipment action.';end case;
 perform app_private.note(10,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;
create function public.equipment_availability(p_start timestamptz,p_end timestamptz,p_handedness text default '',p_max_weight numeric default null,p_setup text default '') returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb;begin
 if not (app_private.member() or app_private.can('equipment')) then raise exception 'Permission denied.' using errcode='42501';end if;
 if p_end<=p_start then raise exception 'Invalid borrowing period.';end if;
 select coalesce(jsonb_agg(t),'[]') into result from (
 select e.*,exists(select 1 from app_private.borrowings b where b.equipment_id=e.id and b.status in ('approved','released') and ((b.status='released' and b.ends_at<now()) or tstzrange(b.starts_at,b.ends_at,'[)')&&tstzrange(p_start,p_end,'[)'))) as reserved,
 (e.condition='serviceable' and (p_handedness='' or e.handedness in (p_handedness,'ambidextrous','not_applicable')) and (p_max_weight is null or e.draw_weight is null or e.draw_weight<=p_max_weight) and (p_setup='' or e.bow_setup=p_setup)) as compatible
 from app_private.equipment e) t;return result;end
$$;

create function app_private.training_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;v_user uuid;t app_private.training_records;c app_private.corrections;entry jsonb;results jsonb:='[]';begin
 case p_action
 when 'save_training','save_personal_training' then
  if p_action='save_training' then perform app_private.require_permission('training');v_user:=(p_data->>'user_id')::uuid;else perform app_private.require_member();v_user:=u;end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.training_records(user_id,event_id,training_date,distance,target_face,category,scoring_format,score,maximum_score,grouping,level,observations,feedback,source,validation,encoded_by,validated_by,validated_at,import_key)
   values(v_user,nullif(p_data->>'event_id','')::uuid,(p_data->>'training_date')::date,(p_data->>'distance')::numeric,app_private.required(p_data,'target_face',100),app_private.required(p_data,'category',100),app_private.required(p_data,'scoring_format',100),(p_data->>'score')::numeric,(p_data->>'maximum_score')::numeric,coalesce(p_data->>'grouping',''),coalesce(p_data->>'level',''),coalesce(p_data->>'observations',''),case when p_action='save_training' then coalesce(p_data->>'feedback','') else '' end,case when p_action='save_training' then 'coach' else 'personal' end,case when p_action='save_training' then 'validated' else 'personal' end,u,case when p_action='save_training' then u end,case when p_action='save_training' then now() end,case when p_action='save_training' then nullif(p_data->>'import_key','') end) returning id into v_id;
  else
   select * into t from app_private.training_records where id=(p_data->>'id')::uuid for update;
   if not found or (p_action='save_personal_training' and (t.user_id<>u or t.validation not in ('personal','returned_for_correction'))) then raise exception 'Editable training record not found.';end if;
   perform app_private.check_version(t.version,p_data);
   update app_private.training_records set training_date=(p_data->>'training_date')::date,distance=(p_data->>'distance')::numeric,target_face=app_private.required(p_data,'target_face',100),category=app_private.required(p_data,'category',100),scoring_format=app_private.required(p_data,'scoring_format',100),score=(p_data->>'score')::numeric,maximum_score=(p_data->>'maximum_score')::numeric,grouping=coalesce(p_data->>'grouping',''),level=coalesce(p_data->>'level',''),observations=coalesce(p_data->>'observations',''),feedback=case when p_action='save_training' then coalesce(p_data->>'feedback','') else feedback end,version=version+1 where id=t.id;v_id:=t.id;
  end if;
 when 'submit_training' then
  update app_private.training_records set validation='pending',version=version+1 where id=(p_data->>'id')::uuid and user_id=u and validation in ('personal','returned_for_correction') returning id into v_id;
  if not found then raise exception 'Personal training record not found.';end if;
  perform app_private.notify_role('training',11,v_id,'Personal training submitted for review');
 when 'validate_training' then
  perform app_private.require_permission('training');select * into t from app_private.training_records where id=(p_data->>'id')::uuid for update;
  if not found or t.validation<>'pending' then raise exception 'Pending training record not found.';end if;perform app_private.check_version(t.version,p_data);
  if p_data->>'validation' not in ('validated','returned_for_correction') then raise exception 'Invalid validation decision.';end if;
  update app_private.training_records set validation=p_data->>'validation',feedback=app_private.required(p_data,'feedback'),validated_by=u,validated_at=now(),version=version+1 where id=t.id;v_id:=t.id;
  perform app_private.notify(t.user_id,4,t.id,'training',p_data->>'validation');
 when 'import_training' then
  perform app_private.require_permission('training');
  if jsonb_typeof(p_data->'rows') is distinct from 'array' or jsonb_array_length(p_data->'rows') not between 1 and 500 then raise exception 'Import 1–500 score rows.';end if;
  for entry in select value from jsonb_array_elements(p_data->'rows') loop
   perform app_private.required(entry,'import_key',200);
   results:=results||jsonb_build_array(app_private.training_command('save_training',entry));
  end loop;return results;
 when 'request_training_correction' then
  select * into t from app_private.training_records where id=(p_data->>'record_id')::uuid and user_id=u and validation='validated';
  if not found then raise exception 'Validated training record not found.';end if;
  insert into app_private.corrections(user_id,module,record_id,request) values(u,11,t.id,app_private.required(p_data,'request')) returning id into v_id;
  perform app_private.notify_role('training',11,v_id,'Training correction requested');
 when 'review_training_correction' then
  perform app_private.require_permission('training');select * into c from app_private.corrections where id=(p_data->>'id')::uuid and module=11 for update;
  if not found or c.status<>'pending' then raise exception 'Pending correction not found.';end if;
  if p_data->>'status' not in ('approved','rejected') then raise exception 'Invalid correction decision.';end if;
  if p_data->>'status'='approved' then
   if p_data->'corrected_record' is null then raise exception 'Provide corrected scorecard.';end if;
   perform app_private.training_command('save_training',(p_data->'corrected_record')||jsonb_build_object('id',c.record_id));
  end if;
  update app_private.corrections set status=p_data->>'status',decision=app_private.required(p_data,'decision'),reviewed_by=u where id=c.id;v_id:=c.id;
  perform app_private.notify(c.user_id,4,c.id,'correction',p_data->>'decision');
 else raise exception 'Unknown training action.';end case;
 perform app_private.note(11,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;

create function app_private.finance_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;f app_private.financial_records;v_office text;begin
 case p_action
 when 'save_financial_request' then
  if p_data->>'kind'<>'payment' then perform app_private.require_member();end if;
  if p_data->>'kind'='payment' and not exists(select 1 from app_private.applications where user_id=u) and not exists(select 1 from app_private.memberships where user_id=u) then raise exception 'Submit an application before submitting payment records.';end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.financial_records(user_id,kind,purpose,amount,reference) values(u,p_data->>'kind',app_private.required(p_data,'purpose'),(p_data->>'amount')::numeric,coalesce(p_data->>'reference','')) returning id into v_id;
  else
   select * into f from app_private.financial_records where id=(p_data->>'id')::uuid and user_id=u for update;
   if not found or f.status not in ('draft','incomplete','returned_for_correction') then raise exception 'Editable financial record not found.';end if;perform app_private.check_version(f.version,p_data);
   delete from app_private.financial_approvals where record_id=f.id;
   update app_private.financial_records set purpose=app_private.required(p_data,'purpose'),amount=(p_data->>'amount')::numeric,reference=coalesce(p_data->>'reference',''),status='draft',version=version+1 where id=f.id;v_id:=f.id;
  end if;
 when 'submit_financial_request' then
  select * into f from app_private.financial_records where id=(p_data->>'id')::uuid and user_id=u for update;
  if not found or f.status not in ('draft','incomplete','returned_for_correction') then raise exception 'Editable financial request not found.';end if;perform app_private.check_version(f.version,p_data);
  if f.kind='payment' and length(btrim(f.reference))=0 then raise exception 'Payment reference is required.';end if;
  if not exists(select 1 from app_private.documents where record_id=f.id and module=12 and owner_id=u) then raise exception 'Supporting proof or receipt is required.';end if;
  update app_private.financial_records set status='pending_verification',version=version+1 where id=f.id;v_id:=f.id;
  perform app_private.notify_role('finance',12,f.id,'Financial submission requires review');
 when 'approve_funds' then
  v_office:=p_data->>'office';if v_office not in ('president','finance') then raise exception 'Invalid approval office.';end if;perform app_private.require_permission(v_office);
  select * into f from app_private.financial_records where id=(p_data->>'id')::uuid for update;
  if not found or f.kind='payment' or f.status<>'pending_verification' then raise exception 'Pending expense or reimbursement required.';end if;
  if f.user_id=u then raise exception 'You cannot approve your own expense.' using errcode='42501';end if;
  if exists(select 1 from app_private.financial_approvals where record_id=f.id and approved_by=u and financial_approvals.office<>v_office) then raise exception 'President and Treasurer approvals must be separate people.';end if;
  insert into app_private.financial_approvals(record_id,office,approved_by,remark) values(f.id,v_office,u,app_private.required(p_data,'remark')) on conflict(record_id,office) do nothing;v_id:=f.id;
 when 'verify_financial_request' then
  perform app_private.require_permission('finance');select * into f from app_private.financial_records where id=(p_data->>'id')::uuid for update;
  if not found or f.status<>'pending_verification' then raise exception 'Pending financial record not found.';end if;perform app_private.check_version(f.version,p_data);
  if f.user_id=u then raise exception 'Another finance officer must verify your submission.' using errcode='42501';end if;
  if p_data->>'status' not in ('verified','incomplete','rejected','returned_for_correction') then raise exception 'Invalid financial decision.';end if;
  if p_data->>'status'='verified' and f.kind<>'payment' and (select count(*) from app_private.financial_approvals where record_id=f.id)<>2 then raise exception 'President and Treasurer prior approvals are required.';end if;
  update app_private.financial_records set status=p_data->>'status',remark=app_private.required(p_data,'remark'),reviewed_by=u,verified_at=case when p_data->>'status'='verified' then now() end,version=version+1 where id=f.id;v_id:=f.id;
  perform app_private.notify(f.user_id,12,f.id,'finance',p_data->>'status'||': '||(p_data->>'remark'));
 when 'assess_fee' then
  perform app_private.require_permission('finance');insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by) values((p_data->>'user_id')::uuid,app_private.required(p_data,'purpose'),(p_data->>'amount')::numeric,(p_data->>'due_on')::date,u) returning id into v_id;
 when 'link_fee_payment' then
  perform app_private.require_permission('finance');
  select * into f from app_private.financial_records where id=(p_data->>'payment_id')::uuid and kind='payment' and status='verified';
  if not found then raise exception 'Verified payment not found.';end if;
  update app_private.fee_assessments set payment_id=f.id where id=(p_data->>'id')::uuid and user_id=f.user_id and amount=f.amount returning id into v_id;
  if not found then raise exception 'Payment must match the assessed person and amount.';end if;
 else raise exception 'Unknown finance action.';end case;
 perform app_private.note(12,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;
do $$declare t text;begin foreach t in array array['equipment','borrowings','equipment_history','training_records','financial_records','financial_approvals','fee_assessments'] loop
 execute format('alter table app_private.%I enable row level security',t);execute format('revoke all on app_private.%I from public,anon,authenticated',t);execute format('grant select on app_private.%I to authenticated',t);
end loop;end $$;
create policy equipment_read on app_private.equipment for select to authenticated using(app_private.member() or app_private.can('equipment'));
create policy borrowing_read on app_private.borrowings for select to authenticated using(app_private.can('equipment') or (app_private.active_user() and user_id=auth.uid()));
create policy equipment_history_read on app_private.equipment_history for select to authenticated using(app_private.can('equipment'));
create policy training_read on app_private.training_records for select to authenticated using(app_private.can('training') or (app_private.active_user() and user_id=auth.uid()));
create policy financial_read on app_private.financial_records for select to authenticated using(app_private.can('finance') or (app_private.can('president') and kind in ('expense','reimbursement')) or (app_private.active_user() and user_id=auth.uid()));
create policy approval_read on app_private.financial_approvals for select to authenticated using(exists(select 1 from app_private.financial_records f where f.id=record_id));
create policy fee_read on app_private.fee_assessments for select to authenticated using(app_private.can('finance') or (app_private.active_user() and user_id=auth.uid()));
revoke all on function app_private.equipment_command(text,jsonb),app_private.training_command(text,jsonb),app_private.finance_command(text,jsonb) from public,anon,authenticated;
revoke all on function public.equipment_availability(timestamptz,timestamptz,text,numeric,text) from public,anon;
grant execute on function public.equipment_availability(timestamptz,timestamptz,text,numeric,text) to authenticated;

-- SOURCE: 202610010006_interclub.sql
create table app_private.partner_clubs(id uuid primary key default gen_random_uuid(),name text unique not null,official_channel text not null);
create table app_private.interclub_events (
 event_id uuid primary key references app_private.events,shared_title text not null,shared_details text not null,shared_venue text not null,
 shared_start timestamptz not null,shared_end timestamptz not null,requirements text not null,registration_closes timestamptz not null,
 rules text not null,status text not null default 'preparation' check(status in ('preparation','active','completed','archived')),version bigint not null default 1
);
create table app_private.event_access (
 id uuid primary key default gen_random_uuid(),event_id uuid not null references app_private.interclub_events,
 user_id uuid not null references app_private.profiles,partner_id uuid references app_private.partner_clubs,
 role text not null check(role in ('partner','coordinator')),revoked_at timestamptz,invited_by uuid not null references app_private.profiles,
 check((role='partner' and partner_id is not null) or role='coordinator'),unique(event_id,user_id)
);
create table app_private.delegates (
 id uuid primary key default gen_random_uuid(),event_id uuid not null references app_private.interclub_events,partner_id uuid not null references app_private.partner_clubs,
 full_name text not null,affiliation text not null,category text not null,requirements text not null,logistical_needs text not null default '',equipment_needs text not null default '',
 status text not null default 'pending' check(status in ('pending','incomplete','eligible','rejected','checked_in','substituted')),
 remark text not null default '',reviewed_by uuid references app_private.profiles,version bigint not null default 1
);
create table app_private.interclub_assignments (
 id uuid primary key default gen_random_uuid(),event_id uuid not null references app_private.interclub_events,delegate_id uuid not null references app_private.delegates,
 starts_at timestamptz not null,ends_at timestamptz not null,target_group text not null,responsibility text not null,check(ends_at>starts_at)
);
create table app_private.interclub_concerns (
 id uuid primary key default gen_random_uuid(),event_id uuid not null references app_private.interclub_events,partner_id uuid references app_private.partner_clubs,
 submitted_by uuid not null references app_private.profiles,concern text not null,status text not null default 'open' check(status in ('open','resolved')),
 resolution text not null default '',resolved_by uuid references app_private.profiles,created_at timestamptz not null default now()
);
create table app_private.interclub_results (
 id uuid primary key default gen_random_uuid(),event_id uuid not null references app_private.interclub_events,delegate_id uuid not null references app_private.delegates,
 result text not null,verified_by uuid references app_private.profiles,verified_at timestamptz,unique(event_id,delegate_id)
);
create table app_private.interclub_archives (
 event_id uuid primary key references app_private.interclub_events,summary text not null,procedures text not null,agreements text not null,
 approval_references text not null,minutes text not null,issues text not null,evaluation text not null,recommendations text not null,
 snapshot jsonb not null,archived_by uuid not null references app_private.profiles,archived_at timestamptz not null default now()
);
create function app_private.event_staff(p_event uuid) returns boolean language sql stable security definer set search_path='' as $$
 select app_private.can('interclub') or (app_private.active_user() and exists(select 1 from app_private.event_access where event_id=p_event and user_id=auth.uid() and role='coordinator' and revoked_at is null))
$$;
create function app_private.event_guest(p_event uuid,p_partner uuid default null) returns boolean language sql stable security definer set search_path='' as $$
 select app_private.active_user() and exists(select 1 from app_private.event_access where event_id=p_event and user_id=auth.uid() and revoked_at is null and (p_partner is null or partner_id=p_partner))
$$;
create function app_private.interclub_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;v_event uuid;v_user uuid;p uuid;d app_private.delegates;i app_private.interclub_events;e app_private.events;begin
 v_event:=nullif(p_data->>'event_id','')::uuid;
 if p_action not in ('save_partner','share_interclub_event') then
  select * into i from app_private.interclub_events where event_id=v_event for update;
  if not found or i.status='archived' then raise exception 'Editable shared event not found.';end if;
 end if;
 case p_action
 when 'save_partner' then
  perform app_private.require_permission('interclub');insert into app_private.partner_clubs(name,official_channel) values(app_private.required(p_data,'name',200),app_private.required(p_data,'official_channel',500)) returning id into v_id;
 when 'share_interclub_event' then
  perform app_private.require_permission('interclub');select * into e from app_private.events where id=v_event and published and not archived;
  if not found then raise exception 'Published official activity required.';end if;
  insert into app_private.interclub_events(event_id,shared_title,shared_details,shared_venue,shared_start,shared_end,requirements,registration_closes,rules)
  values(e.id,e.title,app_private.required(p_data,'shared_details'),e.venue,e.starts_at,e.ends_at,app_private.required(p_data,'requirements'),(p_data->>'registration_closes')::timestamptz,app_private.required(p_data,'rules'));
  v_id:=e.id;
 when 'invite_partner' then
  perform app_private.require_permission('interclub');
  select p1.id into v_user from auth.users a join app_private.profiles p1 on p1.id=a.id where lower(a.email)=lower(app_private.required(p_data,'email',254)) and a.email_confirmed_at is not null and p1.enabled;
  if v_user is null then raise exception 'Representative must register and verify their account first.';end if;
  insert into app_private.event_access(event_id,user_id,partner_id,role,invited_by) values(v_event,v_user,nullif(p_data->>'partner_id','')::uuid,p_data->>'role',u)
  on conflict(event_id,user_id) do update set partner_id=excluded.partner_id,role=excluded.role,revoked_at=null,invited_by=u returning id into v_id;
  perform app_private.notify(v_user,15,v_event,'invitation','You have been invited to '||i.shared_title);
 when 'revoke_event_access' then
  perform app_private.require_permission('interclub');update app_private.event_access set revoked_at=now() where event_id=v_event and id=(p_data->>'id')::uuid returning id into v_id;
 when 'save_delegate' then
  p:=(p_data->>'partner_id')::uuid;
  if not(app_private.event_staff(v_event) or app_private.event_guest(v_event,p)) then raise exception 'Permission denied for this delegation.' using errcode='42501';end if;
  if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Delegation registration is closed. Request an approved substitution.';end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.delegates(event_id,partner_id,full_name,affiliation,category,requirements,logistical_needs,equipment_needs)
   values(v_event,p,app_private.required(p_data,'full_name',160),app_private.required(p_data,'affiliation',160),app_private.required(p_data,'category',100),coalesce(p_data->>'requirements',''),coalesce(p_data->>'logistical_needs',''),coalesce(p_data->>'equipment_needs','')) returning id into v_id;
  else
   select * into d from app_private.delegates where id=(p_data->>'id')::uuid and event_id=v_event and partner_id=p for update;
   if not found or d.status not in ('pending','incomplete') then raise exception 'Editable delegate not found.';end if;perform app_private.check_version(d.version,p_data);
   update app_private.delegates set full_name=app_private.required(p_data,'full_name',160),affiliation=app_private.required(p_data,'affiliation',160),category=app_private.required(p_data,'category',100),requirements=coalesce(p_data->>'requirements',''),logistical_needs=coalesce(p_data->>'logistical_needs',''),equipment_needs=coalesce(p_data->>'equipment_needs',''),status='pending',version=version+1 where id=d.id;v_id:=d.id;
  end if;
 when 'review_delegate','substitute_delegate' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  select * into d from app_private.delegates where id=(p_data->>'id')::uuid and event_id=v_event for update;if not found then raise exception 'Delegate not found.';end if;perform app_private.check_version(d.version,p_data);
  if p_action='substitute_delegate' then
   if d.status not in ('eligible','checked_in') then raise exception 'Only approved delegates can be substituted.';end if;
   update app_private.delegates set status='substituted',remark=app_private.required(p_data,'remark'),reviewed_by=u,version=version+1 where id=d.id;
   insert into app_private.delegates(event_id,partner_id,full_name,affiliation,category,requirements,status,reviewed_by,remark)
   values(v_event,d.partner_id,app_private.required(p_data,'full_name',160),d.affiliation,d.category,app_private.required(p_data,'requirements'),'eligible',u,p_data->>'remark') returning id into v_id;
   update app_private.interclub_assignments set delegate_id=v_id where delegate_id=d.id;
  else
   if not ((d.status in ('pending','incomplete') and p_data->>'status' in ('eligible','incomplete','rejected')) or (d.status='eligible' and p_data->>'status'='checked_in')) then raise exception 'Invalid delegate transition.';end if;
   update app_private.delegates set status=p_data->>'status',remark=app_private.required(p_data,'remark'),reviewed_by=u,version=version+1 where id=d.id;v_id:=d.id;
  end if;
 when 'assign_delegate' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  select * into d from app_private.delegates where id=(p_data->>'delegate_id')::uuid and event_id=v_event and status in ('eligible','checked_in');if not found then raise exception 'Eligible delegate required.';end if;
  if exists(select 1 from app_private.interclub_assignments a where a.event_id=v_event and (a.delegate_id=d.id or a.target_group=p_data->>'target_group') and tstzrange(a.starts_at,a.ends_at,'[)')&&tstzrange((p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,'[)')) then raise exception 'Schedule or target assignment conflict.';end if;
  insert into app_private.interclub_assignments(event_id,delegate_id,starts_at,ends_at,target_group,responsibility) values(v_event,d.id,(p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,app_private.required(p_data,'target_group',100),app_private.required(p_data,'responsibility')) returning id into v_id;
 when 'raise_concern' then
  if not(app_private.event_staff(v_event) or app_private.event_guest(v_event)) then raise exception 'Permission denied.' using errcode='42501';end if;
  select partner_id into p from app_private.event_access where event_id=v_event and user_id=u and revoked_at is null;
  insert into app_private.interclub_concerns(event_id,partner_id,submitted_by,concern) values(v_event,p,u,app_private.required(p_data,'concern')) returning id into v_id;
 when 'resolve_concern' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  update app_private.interclub_concerns set status='resolved',resolution=app_private.required(p_data,'resolution'),resolved_by=u where id=(p_data->>'id')::uuid and event_id=v_event returning id into v_id;
 when 'record_result','verify_result' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  if p_action='record_result' then
   if not exists(select 1 from app_private.delegates where id=(p_data->>'delegate_id')::uuid and event_id=v_event and status='checked_in') then raise exception 'Checked-in delegate required.';end if;
   insert into app_private.interclub_results(event_id,delegate_id,result) values(v_event,(p_data->>'delegate_id')::uuid,app_private.required(p_data,'result'))
   on conflict(event_id,delegate_id) do update set result=excluded.result,verified_by=null,verified_at=null returning id into v_id;
  else update app_private.interclub_results set verified_by=u,verified_at=now() where id=(p_data->>'id')::uuid and event_id=v_event returning id into v_id;end if;
 when 'update_shared_event' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  perform app_private.check_version(i.version,p_data);
  if p_data->>'status' not in ('preparation','active','completed') then raise exception 'Use the archive workflow to archive an event.';end if;
  update app_private.interclub_events set shared_details=app_private.required(p_data,'shared_details'),shared_venue=app_private.required(p_data,'shared_venue',200),shared_start=(p_data->>'shared_start')::timestamptz,shared_end=(p_data->>'shared_end')::timestamptz,status=p_data->>'status',version=version+1 where event_id=v_event;v_id:=v_event;
 when 'archive_interclub' then
  if not app_private.event_staff(v_event) then raise exception 'Permission denied.' using errcode='42501';end if;
  if i.status<>'completed' then raise exception 'Complete the event before archiving.';end if;
  if exists(select 1 from app_private.interclub_results where event_id=v_event and verified_by is null) then raise exception 'Verify all results before archiving.';end if;
  insert into app_private.interclub_archives(event_id,summary,procedures,agreements,approval_references,minutes,issues,evaluation,recommendations,snapshot,archived_by)
  values(v_event,app_private.required(p_data,'summary'),app_private.required(p_data,'procedures'),app_private.required(p_data,'agreements'),app_private.required(p_data,'approval_references'),app_private.required(p_data,'minutes'),app_private.required(p_data,'issues'),app_private.required(p_data,'evaluation'),app_private.required(p_data,'recommendations'),jsonb_build_object('event',to_jsonb(i),'delegates',(select jsonb_agg(d1) from app_private.delegates d1 where event_id=v_event),'assignments',(select jsonb_agg(a1) from app_private.interclub_assignments a1 where event_id=v_event),'results',(select jsonb_agg(r1) from app_private.interclub_results r1 where event_id=v_event)),u);
  update app_private.interclub_events set status='archived',version=version+1 where event_id=v_event;v_id:=v_event;
 else raise exception 'Unknown interclub action.';end case;
 perform app_private.note(15,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;
do $$declare t text;begin foreach t in array array['partner_clubs','interclub_events','event_access','delegates','interclub_assignments','interclub_concerns','interclub_results','interclub_archives'] loop
 execute format('alter table app_private.%I enable row level security',t);execute format('revoke all on app_private.%I from public,anon,authenticated',t);execute format('grant select on app_private.%I to authenticated',t);
end loop;end $$;
create policy partner_read on app_private.partner_clubs for select to authenticated using(app_private.can('interclub') or exists(select 1 from app_private.event_access a where a.partner_id=partner_clubs.id and a.user_id=auth.uid() and a.revoked_at is null and app_private.active_user()));
create policy shared_event_read on app_private.interclub_events for select to authenticated using(app_private.event_staff(event_id) or app_private.event_guest(event_id));
create policy event_access_read on app_private.event_access for select to authenticated using(app_private.event_staff(event_id) or (app_private.active_user() and user_id=auth.uid()));
-- The participant reference list is intentionally shared only within this event. Private requirement documents are separately protected.
create policy delegate_read on app_private.delegates for select to authenticated using(app_private.event_staff(event_id) or app_private.event_guest(event_id,partner_id));
create policy assignment_read on app_private.interclub_assignments for select to authenticated using(app_private.event_staff(event_id) or app_private.event_guest(event_id));
create policy concern_read on app_private.interclub_concerns for select to authenticated using(app_private.event_staff(event_id) or (app_private.active_user() and submitted_by=auth.uid()));
create policy result_read on app_private.interclub_results for select to authenticated using(app_private.event_staff(event_id) or (verified_by is not null and app_private.event_guest(event_id)));
create policy archive_read on app_private.interclub_archives for select to authenticated using(app_private.event_staff(event_id));
revoke all on function app_private.event_staff(uuid),app_private.event_guest(uuid,uuid),app_private.interclub_command(text,jsonb) from public,anon,authenticated;
grant execute on function app_private.event_staff(uuid),app_private.event_guest(uuid,uuid) to authenticated;

-- SOURCE: 202610010007_storage_reports.sql
create table app_private.goals(id uuid primary key default gen_random_uuid(),metric text not null,title text not null,target numeric not null check(target>0),starts_on date not null,ends_on date not null,created_by uuid not null references app_private.profiles,check(ends_on>=starts_on));
create table app_private.video_verifications(path text primary key,duration_ms integer not null check(duration_ms between 1 and 10000),verified_at timestamptz not null default now());
alter table app_private.goals enable row level security;alter table app_private.video_verifications enable row level security;
revoke all on app_private.goals,app_private.video_verifications from public,anon,authenticated;
grant select on app_private.goals to authenticated;
create policy goal_read on app_private.goals for select to authenticated using(app_private.can('reports'));

create function app_private.document_access(p_module integer,p_record uuid,p_write boolean default false) returns boolean language plpgsql stable security definer set search_path='' as $$
 declare u uuid:=auth.uid();begin
 if not app_private.active_user() then return false;end if;
 case p_module
 when 2 then return exists(select 1 from app_private.applications where id=p_record and ((user_id=u and (not p_write or status in ('draft','incomplete','returned_for_correction'))) or (not p_write and app_private.can('membership'))));
 when 1 then return exists(select 1 from app_private.renewals where id=p_record and ((user_id=u and (not p_write or status in ('draft','returned_for_correction'))) or (not p_write and app_private.can('membership'))));
 when 9 then return exists(select 1 from app_private.candidates c join app_private.elections e on e.id=c.election_id where c.id=p_record and ((c.user_id=u and (not p_write or (e.status='announced' and now()<=e.candidacy_closes))) or app_private.can('elections')));
 when 11 then return exists(select 1 from app_private.training_records where id=p_record and ((user_id=u and (not p_write or validation in ('personal','returned_for_correction'))) or app_private.can('training')));
 when 12 then return exists(select 1 from app_private.financial_records where id=p_record and ((user_id=u and (not p_write or status in ('draft','incomplete','returned_for_correction'))) or (not p_write and (app_private.can('finance') or (kind<>'payment' and app_private.can('president'))))));
 when 15 then return exists(select 1 from app_private.delegates d join app_private.interclub_events e on e.event_id=d.event_id where d.id=p_record and ((app_private.event_guest(d.event_id,d.partner_id) and (not p_write or (e.status='preparation' and now()<=e.registration_closes and d.status in ('pending','incomplete')))) or (app_private.event_staff(d.event_id) and (not p_write or e.status<>'archived'))))
 or exists(select 1 from app_private.interclub_events e where e.event_id=p_record and ((app_private.event_staff(e.event_id) and (not p_write or e.status<>'archived')) or (not p_write and app_private.event_guest(e.event_id))));
 else return false;end case;end
$$;
create function app_private.storage_write(p_name text) returns boolean language plpgsql stable security definer set search_path='' as $$
 begin
 if split_part(p_name,'/',1)<>auth.uid()::text then return false;end if;
 return app_private.document_access(split_part(p_name,'/',2)::integer,split_part(p_name,'/',3)::uuid,true);
 exception when invalid_text_representation then return false;end
$$;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('club-documents','club-documents',false,20971520,array['application/pdf','image/jpeg','image/png','text/csv','video/mp4']);
-- A revoked representative must not regain an attached file through the orphan fallback.
-- Check attachment existence independently of the caller's filtered document rows.
create function app_private.registered_path(p_name text) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.documents where path=p_name)
$$;
revoke all on function app_private.registered_path(text) from public,anon,authenticated;
grant execute on function app_private.registered_path(text) to authenticated;
create policy archery_upload on storage.objects for insert to authenticated with check(bucket_id='club-documents' and app_private.storage_write(name));
create policy archery_download on storage.objects for select to authenticated using(bucket_id='club-documents' and app_private.active_user() and (
 exists(select 1 from app_private.documents d where d.path=storage.objects.name and app_private.document_access(d.module,d.record_id,false))
 or (owner_id=auth.uid()::text and not app_private.registered_path(storage.objects.name))));
create policy archery_orphan_cleanup on storage.objects for delete to authenticated using(bucket_id='club-documents' and app_private.active_user() and owner_id=auth.uid()::text and not app_private.registered_path(storage.objects.name));
create policy document_read on app_private.documents for select to authenticated using(app_private.document_access(module,record_id,false));
create function public.register_document(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();s storage.objects;v_id uuid;m integer:=(p_data->>'module')::integer;r uuid:=(p_data->>'record_id')::uuid;begin
 if not app_private.document_access(m,r,true) then raise exception 'This record does not accept your uploads.' using errcode='42501';end if;
 select * into s from storage.objects where bucket_id='club-documents' and name=p_data->>'path' and owner_id=u::text;
 if not found or split_part(s.name,'/',1)<>u::text or split_part(s.name,'/',2)<>m::text or split_part(s.name,'/',3)<>r::text then raise exception 'Uploaded file does not belong to this record.';end if;
 if s.metadata->>'mimetype'='video/mp4' and (m<>11 or not exists(select 1 from app_private.video_verifications where path=s.name)) then raise exception 'Training video must pass the server duration check (10 seconds maximum).';end if;
 insert into app_private.documents(owner_id,module,record_id,requirement,name,path,mime,size_bytes)
 values(u,m,r,app_private.required(p_data,'requirement',160),app_private.required(p_data,'name',200),s.name,s.metadata->>'mimetype',(s.metadata->>'size')::bigint)
 returning id into v_id;perform app_private.note(m,r,'attach_document',jsonb_build_object('document_id',v_id));return jsonb_build_object('id',v_id);end
$$;

create function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb:='{}';begin
 perform app_private.require_permission('reports');
 if p_to<p_from then raise exception 'Invalid reporting period.';end if;
 if app_private.can('membership') then
 result:=result||jsonb_build_object('membership',jsonb_build_object('active',(select count(*) from app_private.memberships where status='Active'),'total',(select count(*) from app_private.memberships),'active_ratio',(select round(100.0*count(*) filter(where status='Active')/nullif(count(*),0),2) from app_private.memberships),'applications',(select count(*) from app_private.applications where submitted_at::date between p_from and p_to),'conversion_rate',(select round(100.0*count(*) filter(where status='approved')/nullif(count(*),0),2) from app_private.applications where submitted_at::date between p_from and p_to),'incomplete',(select count(*) from app_private.applications where status in ('incomplete','returned_for_correction'))));end if;
 if app_private.can('activities') then
 result:=result||jsonb_build_object('activities',(select coalesce(jsonb_agg(x),'[]') from(select e.id,e.title,(select count(*) from app_private.registrations where event_id=e.id) registrations,(select count(*) from app_private.attendance where event_id=e.id and status='present') present,(select round(100.0*(select count(*) from app_private.attendance where event_id=e.id and status='present')/nullif(count(*),0),2) from app_private.registrations where event_id=e.id) attendance_rate from app_private.events e where e.starts_at::date between p_from and p_to)x));end if;
 if app_private.can('equipment') then
 result:=result||jsonb_build_object('equipment',jsonb_build_object('items',(select count(*) from app_private.equipment),'serviceable',(select count(*) from app_private.equipment where condition='serviceable'),'utilized_items',(select count(distinct equipment_id) from app_private.borrowings where released_at::date between p_from and p_to),'overdue',(select count(*) from app_private.borrowings where status='released' and ends_at<now()),'pending',(select count(*) from app_private.borrowings where status='pending')));end if;
 if app_private.can('training') then
 result:=result||jsonb_build_object('training',(select coalesce(jsonb_agg(x),'[]') from(select user_id,distance,target_face,category,scoring_format,maximum_score,training_date,count(*) sessions,round(avg(score),2) average_score from app_private.training_records where validation='validated' and training_date between p_from and p_to group by user_id,distance,target_face,category,scoring_format,maximum_score,training_date order by training_date)x));end if;
 if app_private.can('finance') then
 result:=result||jsonb_build_object('finance',jsonb_build_object('verified_payments',(select coalesce(sum(amount),0) from app_private.financial_records where kind='payment' and status='verified' and verified_at::date between p_from and p_to),'verified_expenses',(select coalesce(sum(amount),0) from app_private.financial_records where kind in ('expense','reimbursement') and status='verified' and verified_at::date between p_from and p_to),'outstanding_reimbursements',(select coalesce(sum(amount),0) from app_private.financial_records where kind='reimbursement' and status='pending_verification'),'collection_completion',(select round(100.0*count(*) filter(where payment_id is not null)/nullif(count(*),0),2) from app_private.fee_assessments where due_on between p_from and p_to)));end if;
 if app_private.can('interclub') then result:=result||jsonb_build_object('interclub',(select coalesce(jsonb_agg(x),'[]') from(select i.event_id,i.shared_title,i.status,(select count(*) from app_private.delegates where event_id=i.event_id) delegates,(select count(*) from app_private.interclub_results where event_id=i.event_id and verified_by is not null) verified_results from app_private.interclub_events i where shared_start::date between p_from and p_to)x));end if;
 return result||jsonb_build_object('from',p_from,'to',p_to,'generated_at',now(),'limitation','Recorded data only. Missing attendance, unvalidated training, and unverified finance are not assumed complete. Null rates have no denominator.');end
$$;
revoke all on function app_private.document_access(integer,uuid,boolean),app_private.storage_write(text) from public,anon,authenticated;
grant execute on function app_private.document_access(integer,uuid,boolean),app_private.storage_write(text) to authenticated;
revoke all on function public.register_document(jsonb),public.club_report(date,date) from public,anon;
grant execute on function public.register_document(jsonb),public.club_report(date,date) to authenticated;

-- SOURCE: 202610010008_integrated_api.sql
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action in ('save_profile','save_application','submit_application','review_application','save_renewal','request_renewal','review_renewal','update_membership') then
  return app_private.membership_command(p_action,p_data);
 elsif p_action in ('configure_club','define_role','assign_role','retire_role','set_account_status') then
  return app_private.admin_command(p_action,p_data);
 elsif p_action in ('save_event','publish_event','archive_event','publish_announcement','register_event','open_attendance','check_in','record_attendance','request_attendance_correction','review_attendance_correction') then
  return app_private.activity_command(p_action,p_data);
 elsif p_action in ('save_term','record_residency','create_election','file_candidacy','nominate_candidate','confirm_candidate','confirm_voter','cast_ballot','set_election_status','record_external_results','confirm_results','record_leadership') then
  return app_private.election_command(p_action,p_data);
 elsif p_action in ('save_equipment','request_borrowing','review_borrowing') then
  return app_private.equipment_command(p_action,p_data);
 elsif p_action in ('save_training','save_personal_training','submit_training','validate_training','import_training','request_training_correction','review_training_correction') then
  return app_private.training_command(p_action,p_data);
 elsif p_action in ('save_financial_request','submit_financial_request','approve_funds','verify_financial_request','assess_fee','link_fee_payment') then
  return app_private.finance_command(p_action,p_data);
 elsif p_action in ('save_partner','share_interclub_event','invite_partner','revoke_event_access','save_delegate','review_delegate','substitute_delegate','assign_delegate','raise_concern','resolve_concern','record_result','verify_result','update_shared_event','archive_interclub') then
  return app_private.interclub_command(p_action,p_data);
 elsif p_action='save_goal' then
  perform app_private.require_permission('reports');
  if p_data->>'metric' not in ('active_members','applicant_conversion','attendance_rate','equipment_readiness','training_average','collection_completion') then raise exception 'Unknown goal metric.';end if;
  insert into app_private.goals(metric,title,target,starts_on,ends_on,created_by) values(p_data->>'metric',app_private.required(p_data,'title',200),(p_data->>'target')::numeric,(p_data->>'starts_on')::date,(p_data->>'ends_on')::date,auth.uid());return '{}';
 elsif p_action='acknowledge_notification' then
  update app_private.notifications set acknowledged_at=now() where id=(p_data->>'id')::uuid and user_id=auth.uid();
  return '{}';
 end if;
 raise exception 'Unknown action.';
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
-- Full election rows include unconfirmed external tallies. Never expose them through SELECT.
revoke select on app_private.elections from authenticated;
create function app_private.election_cards() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(to_jsonb(e)-'external_results'-'external_turnout'-'confirmation'-'confirmed_by'),'[]')
 from app_private.elections e where app_private.can('elections') or (app_private.member() and e.status<>'draft')
$$;
-- Only names and membership standing needed to select a person; no contact or student details.
create function app_private.member_directory() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object('user_id',p.id,'full_name',p.full_name,'status',m.status)),'[]')
 from app_private.profiles p left join app_private.memberships m on m.user_id=p.id
 where app_private.active_user() and (p.id=auth.uid() or (app_private.member() and m.status='Active')
 or (m.user_id is not null and (app_private.can('membership') or app_private.can('activities') or app_private.can('elections') or app_private.can('training') or app_private.can('finance') or app_private.can('equipment')))
 or (app_private.member() and exists(select 1 from app_private.candidates c join app_private.elections e on e.id=c.election_id where c.user_id=p.id and c.confirmed and e.status<>'draft')))
$$;
create function app_private.shared_participants() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'event_id',d.event_id,'full_name',d.full_name,'affiliation',d.affiliation,'category',d.category,'status',d.status)),'[]')
 from app_private.delegates d where d.status in ('eligible','checked_in') and (app_private.event_staff(d.event_id) or app_private.event_guest(d.event_id))
$$;
revoke all on function app_private.election_cards(),app_private.member_directory(),app_private.shared_participants() from public,anon,authenticated;
grant execute on function app_private.election_cards(),app_private.member_directory(),app_private.shared_participants() to authenticated;
create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
declare result jsonb; t text; rows jsonb; begin
 if not app_private.active_user() then raise exception 'Sign in with a verified, enabled account.' using errcode='42501';end if;
 result:=jsonb_build_object('user_id',auth.uid(),'permissions',(select coalesce(jsonb_agg(distinct p),'[]') from app_private.role_assignments a join app_private.roles r on r.id=a.role_id cross join unnest(r.permissions) p where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at));
 foreach t in array array['settings','profiles','memberships','applications','renewals','roles','role_assignments','documents','notifications','audit_log','events','announcements','registrations','attendance_windows','attendance','corrections','academic_terms','member_terms','elections','candidates','eligible_voters','voter_participation','leadership_records','equipment','borrowings','equipment_history','training_records','financial_records','financial_approvals','fee_assessments','partner_clubs','interclub_events','event_access','delegates','interclub_assignments','interclub_concerns','interclub_results','interclub_archives','goals'] loop
  if t='elections' then rows:=app_private.election_cards();
  else execute format('select coalesce(jsonb_agg(x),''[]''::jsonb) from (select * from app_private.%I) x',t) into rows;end if;
  result:=result||jsonb_build_object(t,rows);
 end loop;
 return result||jsonb_build_object('member_directory',app_private.member_directory(),'shared_participants',app_private.shared_participants());
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202610020009_integrity.sql
alter table app_private.profiles add constraint profile_lengths check(length(full_name)<=160 and length(phone)<=40);
alter table app_private.applications add constraint application_lengths check(length(full_name)<=160 and length(phone)<=40 and length(student_number)<=80 and length(experience)<=4000);
alter table app_private.settings add constraint categories_not_empty check(cardinality(membership_categories)>0);
alter table app_private.elections add constraint elected_positions check(cardinality(positions)>0 and positions <@ array['President','Vice President']);
alter table app_private.interclub_events add constraint shared_period check(shared_end>shared_start);

-- Only a trusted video verifier can write the duration. Authenticated clients cannot forge it.
create function public.record_video_verification(p_path text,p_owner uuid,p_duration_ms integer) returns void language plpgsql security definer set search_path='' as $$
begin
 if not exists(select 1 from storage.objects where bucket_id='club-documents' and name=p_path and owner_id=p_owner::text and metadata->>'mimetype'='video/mp4')
 or split_part(p_path,'/',1)<>p_owner::text or split_part(p_path,'/',2)<>'11' then raise exception 'Training video not found.';end if;
 insert into app_private.video_verifications(path,duration_ms) values(p_path,p_duration_ms)
 on conflict(path) do update set duration_ms=excluded.duration_ms,verified_at=now();
end $$;
revoke all on function public.record_video_verification(text,uuid,integer) from public,anon,authenticated;
grant execute on function public.record_video_verification(text,uuid,integer) to service_role;

-- SOURCE: 202610020010_goals.sql
alter function public.club_report(date,date) rename to club_report_data;
revoke all on function public.club_report_data(date,date) from public,anon,authenticated;
create function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;g app_private.goals;data jsonb;actual numeric;goals jsonb:='[]';begin
 perform app_private.require_permission('reports');
 result:=public.club_report_data(p_from,p_to);
 for g in select * from app_private.goals where starts_on<=p_to and ends_on>=p_from loop
  data:=public.club_report_data(g.starts_on,g.ends_on);actual:=null;
  case g.metric
   when 'active_members' then actual:=(data->'membership'->>'active')::numeric;
   when 'applicant_conversion' then actual:=(data->'membership'->>'conversion_rate')::numeric;
   when 'attendance_rate' then select round(100.0*sum((v->>'present')::numeric)/nullif(sum((v->>'registrations')::numeric),0),2) into actual from jsonb_array_elements(coalesce(data->'activities','[]')) v;
   when 'equipment_readiness' then actual:=round(100.0*(data->'equipment'->>'serviceable')::numeric/nullif((data->'equipment'->>'items')::numeric,0),2);
   when 'training_average' then
    if app_private.can('training') then select round(avg(100.0*score/maximum_score),2) into actual from app_private.training_records where validation='validated' and training_date between g.starts_on and g.ends_on;end if;
   when 'collection_completion' then actual:=(data->'finance'->>'collection_completion')::numeric;
  end case;
  goals:=goals||jsonb_build_array(jsonb_build_object('id',g.id,'title',g.title,'metric',g.metric,'target',g.target,'actual',actual,'starts_on',g.starts_on,'ends_on',g.ends_on,'achievement_percent',round(100.0*actual/g.target,2),'basis',case when g.metric in ('active_members','equipment_readiness') then 'Current snapshot' when g.metric='training_average' then 'Validated score percentage; compare individual trends only within matching conditions' else 'Records in the goal period' end));
 end loop;
 if app_private.can('elections') then
  result:=result||jsonb_build_object('elections',(select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'title',e.title,'summary',public.election_summary(e.id))),'[]') from app_private.elections e where e.voting_closes::date between p_from and p_to));
 end if;
 return result||jsonb_build_object('goals',goals);
end $$;
revoke all on function public.club_report(date,date) from public,anon;
grant execute on function public.club_report(date,date) to authenticated;

-- SOURCE: 202610020011_account_creation.sql
create table app_private.account_requests (
 id uuid primary key default gen_random_uuid(),actor uuid not null references app_private.profiles,
 email text not null,full_name text not null,reason text not null,
 created_at timestamptz not null default now(),completed_at timestamptz,user_id uuid references auth.users
);
alter table app_private.account_requests enable row level security;
revoke all on app_private.account_requests from public,anon,authenticated;
grant select on app_private.account_requests to authenticated;
create policy account_request_read on app_private.account_requests for select to authenticated using(app_private.can('administration'));
create function public.request_account_creation(p_email text,p_full_name text,p_reason text) returns uuid language plpgsql security definer set search_path='' as $$
declare v_id uuid;begin
 perform app_private.require_permission('administration');
 if length(p_email)>254 or p_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then raise exception 'Enter a valid email address.';end if;
 if length(btrim(p_full_name)) not between 1 and 160 or length(btrim(p_reason)) not between 1 and 4000 then raise exception 'Full name and reason are required.';end if;
 insert into app_private.account_requests(actor,email,full_name,reason) values(auth.uid(),lower(btrim(p_email)),btrim(p_full_name),btrim(p_reason)) returning id into v_id;
 perform app_private.note(14,v_id,'request_account_creation',jsonb_build_object('email',lower(btrim(p_email))));return v_id;
end $$;
create function public.complete_account_creation(p_request uuid,p_user uuid) returns void language plpgsql security definer set search_path='' as $$
declare r app_private.account_requests;begin
 select * into r from app_private.account_requests where id=p_request and completed_at is null for update;
 if not found or not exists(select 1 from auth.users where id=p_user and lower(email)=r.email) then raise exception 'Account creation request does not match.';end if;
 insert into app_private.profiles(id,full_name) values(p_user,r.full_name) on conflict do nothing;
 update app_private.account_requests set user_id=p_user,completed_at=now() where id=r.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail) values(r.actor,14,p_user,'account_invited',jsonb_build_object('request_id',r.id));
end $$;
revoke all on function public.request_account_creation(text,text,text),public.complete_account_creation(uuid,uuid) from public,anon,authenticated;
grant execute on function public.request_account_creation(text,text,text) to authenticated;
grant execute on function public.complete_account_creation(uuid,uuid) to service_role;

-- SOURCE: 202610020012_cross_module_reads.sql
drop policy event_read on app_private.events;
create policy event_read on app_private.events for select to authenticated using(
 app_private.can('activities') or (published and (app_private.member() or app_private.can('equipment') or app_private.can('training') or app_private.can('interclub')))
);
drop policy partner_read on app_private.partner_clubs;
create policy partner_read on app_private.partner_clubs for select to authenticated using(
 app_private.can('interclub') or exists(select 1 from app_private.event_access a where a.partner_id=partner_clubs.id and a.revoked_at is null
 and (app_private.event_staff(a.event_id) or (a.user_id=auth.uid() and app_private.active_user())))
);

notify pgrst, 'reload schema';
commit;
