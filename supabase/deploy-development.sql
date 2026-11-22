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

-- SOURCE: 202610100013_workflow_completion.sql
-- Draft election configuration can be corrected and validated before publication.
-- Published rules remain locked; a later election is a separate record.
create function app_private.update_election(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare e app_private.elections; begin
 perform app_private.require_permission('elections');
 select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
 if not found or e.status<>'draft' then raise exception 'Only a draft election can be edited.';end if;
 perform app_private.check_version(e.version,p_data);
 update app_private.elections set
 title=app_private.required(p_data,'title',200),kind=p_data->>'kind',term_end=(p_data->>'term_end')::date,
 announces_at=(p_data->>'announces_at')::timestamptz,candidacy_closes=(p_data->>'candidacy_closes')::timestamptz,
 voting_opens=(p_data->>'voting_opens')::timestamptz,voting_closes=(p_data->>'voting_closes')::timestamptz,result_due=(p_data->>'result_due')::timestamptz,
 threshold=(p_data->>'threshold')::numeric,residency_terms=(p_data->>'residency_terms')::integer,
 rules=app_private.required(p_data,'rules'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),
 method=p_data->>'method',positions=array(select jsonb_array_elements_text(p_data->'positions')),version=version+1
 where id=e.id;
 perform app_private.note(9,e.id,'update_election',p_data);
 return jsonb_build_object('id',e.id);
end $$;
revoke all on function app_private.update_election(jsonb) from public,anon,authenticated;

-- Keep the existing command contract and privilege checks for every other action.
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_completion;
revoke all on function app_private.club_command_before_completion(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='update_election' then return app_private.update_election(p_data);end if;
 return app_private.club_command_before_completion(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

-- Constraints complement the existing command's event lock and overlap checks.
create function app_private.guard_interclub_period() returns trigger
language plpgsql security definer set search_path='' as $$
declare e app_private.interclub_events;begin
 select * into e from app_private.interclub_events where event_id=new.event_id for update;
 if e.status not in ('preparation','active') then raise exception 'Assignments are locked after event completion.';end if;
 if new.starts_at<e.shared_start or new.ends_at>e.shared_end then raise exception 'Assignment must fit the shared event period.';end if;
 return new;
end $$;
create trigger interclub_assignment_period before insert or update on app_private.interclub_assignments
for each row execute function app_private.guard_interclub_period();

create function app_private.guard_interclub_stage() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if old.status='archived' or (new.status<>old.status and not (
  (old.status='preparation' and new.status='active') or
  (old.status='active' and new.status='completed') or
  (old.status='completed' and new.status='archived')
 )) then raise exception 'Invalid shared event transition; archived events stay locked.';end if;
 if exists(select 1 from app_private.interclub_assignments a where a.event_id=new.event_id
  and (a.starts_at<new.shared_start or a.ends_at>new.shared_end)) then
  raise exception 'The shared period must contain existing assignments.';
 end if;
 return new;
end $$;
create trigger interclub_event_stage before update on app_private.interclub_events
for each row execute function app_private.guard_interclub_stage();
revoke all on function app_private.guard_interclub_period(),app_private.guard_interclub_stage() from public,anon,authenticated;

-- A resubmitted task must reappear even if its earlier notice was acknowledged.
create or replace function app_private.notify_role(p_permission text,p_module integer,p_record uuid,p_message text) returns void
language sql security definer set search_path='' as $$
 insert into app_private.notifications(user_id,module,record_id,kind,message)
 select distinct a.user_id,p_module,p_record,'review',p_message from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 join app_private.profiles p on p.id=a.user_id where p.enabled and p_permission=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at
 on conflict(user_id,record_id,kind,message) do update set acknowledged_at=null,completed_at=null,notified_at=now()
$$;

create function app_private.sync_workflow_notices() returns trigger
language plpgsql security definer set search_path='' as $$
declare done boolean:=false;begin
 if tg_table_name in ('applications','renewals') then done:=new.status not in ('pending','under_review');
 elsif tg_table_name='borrowings' then
  done:=new.status<>'pending';
  if new.status='released' then
   insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
   values(new.user_id,5,new.id,'return','Return borrowed equipment',new.ends_at)
   on conflict(user_id,record_id,kind,message) do update set due_at=excluded.due_at,completed_at=null;
  elsif new.status='returned' then
   update app_private.notifications set completed_at=now() where record_id=new.id and kind='return' and completed_at is null;
  end if;
 elsif tg_table_name='training_records' then done:=new.validation<>'pending';
 elsif tg_table_name='financial_records' then done:=new.status<>'pending_verification';
 elsif tg_table_name='corrections' then done:=new.status<>'pending';
 elsif tg_table_name='fee_assessments' then
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at,completed_at)
  values(new.user_id,12,new.id,'fee','Fee assessment: '||new.purpose,new.due_on::timestamptz,case when new.payment_id is not null then now() end)
  on conflict(user_id,record_id,kind,message) do update set due_at=excluded.due_at,completed_at=excluded.completed_at;
 elsif tg_table_name='attendance' and new.status in ('present','excused') then
  update app_private.notifications set completed_at=now() where user_id=new.user_id and record_id=new.event_id and kind='event' and completed_at is null;
 end if;
 if done then update app_private.notifications set completed_at=now() where record_id=new.id and kind='review' and completed_at is null;end if;
 return new;
end $$;
do $$declare t text;begin
 foreach t in array array['applications','renewals','borrowings','training_records','financial_records','corrections','fee_assessments','attendance'] loop
  execute format('create trigger workflow_notices after insert or update on app_private.%I for each row execute function app_private.sync_workflow_notices()',t);
 end loop;
end $$;
revoke all on function app_private.sync_workflow_notices() from public,anon,authenticated;

-- SOURCE: 202610230014_announcement_updates.sql
alter table app_private.announcements
 add column version bigint not null default 1,
 add column updated_at timestamptz,
 add column updated_by uuid references app_private.profiles;

-- An announcement must not disclose an unpublished activity to every member.
create function app_private.guard_announcement_event() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.event_id is not null and not exists (
  select 1 from app_private.events where id=new.event_id and published and not archived
 ) then raise exception 'Choose a published, active activity or leave the activity empty.';end if;
 return new;
end $$;
create trigger announcement_event before insert or update on app_private.announcements
for each row execute function app_private.guard_announcement_event();
revoke all on function app_private.guard_announcement_event() from public,anon,authenticated;

create function app_private.update_announcement(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare a app_private.announcements;begin
 perform app_private.require_permission('activities');
 select * into a from app_private.announcements where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'Announcement not found.';end if;
 perform app_private.check_version(a.version,p_data);
 update app_private.announcements set title=app_private.required(p_data,'title',200),
 message=app_private.required(p_data,'message'),event_id=nullif(p_data->>'event_id','')::uuid,
 version=version+1,updated_at=now(),updated_by=auth.uid() where id=a.id;
 -- Reopen the existing notice, including for a correction that retains its title.
 update app_private.notifications set message='Updated announcement: '||(p_data->>'title'),
  acknowledged_at=null,completed_at=null,notified_at=now()
 where record_id=a.id and kind='announcement';
 insert into app_private.notifications(user_id,module,record_id,kind,message)
 select m.user_id,3,a.id,'announcement','Updated announcement: '||(p_data->>'title')
 from app_private.memberships m where m.status='Active' and not exists (
  select 1 from app_private.notifications n where n.user_id=m.user_id and n.record_id=a.id and n.kind='announcement'
 );
 perform app_private.note(8,a.id,'update_announcement',jsonb_build_object('before',to_jsonb(a),'after',p_data));
 return jsonb_build_object('id',a.id);
end $$;
revoke all on function app_private.update_announcement(jsonb) from public,anon,authenticated;

create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='update_election' then return app_private.update_election(p_data);end if;
 if p_action='update_announcement' then return app_private.update_announcement(p_data);end if;
 return app_private.club_command_before_completion(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

-- SOURCE: 202610230015_version_conflicts.sql
-- Expected-version mismatches are business conflicts, not retryable PostgreSQL
-- serialization failures. SQLSTATE 40001 can loop in PostgREST 14.
create or replace function app_private.check_version(p_actual bigint,p_data jsonb)
returns void language plpgsql immutable set search_path='' as $$
begin
 if p_actual is null or (p_data->>'version')::bigint is distinct from p_actual then
  raise sqlstate 'PT409' using message='This record changed. Refresh before trying again.';
 end if;
end $$;

-- SOURCE: 202611050016_settings_safe_update.sql
-- Supabase safe-update enforcement requires an explicit singleton predicate.
-- Preserve role guards, serialization and the last-administrator invariant.
create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
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
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
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

-- SOURCE: 202611100017_member_workflows.sql
-- Member workflow completion (consultation follow-up, November 2026).
--  * Full member profile editing (contact, emergency contact, academic and archery details).
--  * Registration lifecycle: register -> (full) waitlist -> cancel -> automatic waitlist promotion.
--    The registrations table keeps its meaning (confirmed seats), so capacity checks, QR/manual
--    attendance and reports continue to count only confirmed participants.
--  * Member-side borrowing cancellation before release.
--  * Read models for the app: per-event seat counts and live equipment status, so members see
--    "3 spots left" / "Borrowed until Nov 12" without reading other members' records.

-- ---------------------------------------------------------------- profile
alter table app_private.profiles
 add column emergency_contact_name text not null default '',
 add column emergency_contact_phone text not null default '',
 add column program text not null default '',
 add column year_level text not null default '',
 add column dominant_hand text not null default '' check(dominant_hand in ('','left','right')),
 add column bow_type text not null default '' check(bow_type in ('','recurve','compound','barebow','traditional','longbow')),
 add column draw_weight numeric check(draw_weight is null or draw_weight between 5 and 80),
 add column arrow_length numeric check(arrow_length is null or arrow_length between 20 and 34),
 add column experience_level text not null default '' check(experience_level in ('','beginner','intermediate','advanced','competitive')),
 add column bio text not null default '' check(length(bio)<=500);

create function app_private.valid_phone(p text) returns boolean language sql immutable set search_path='' as $$
 select p='' or p ~ '^\+?[0-9][0-9 -]{6,18}$'
$$;

create function app_private.save_member_profile(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_phone text:=trim(coalesce(p_data->>'phone',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone',''));
begin
 if not app_private.valid_phone(v_phone) then raise exception 'Enter a valid mobile number (for example 0917 123 4567).'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if (v_ec_phone='') <> (trim(coalesce(p_data->>'emergency_contact_name',''))='') then
  raise exception 'Provide both the emergency contact name and number.'; end if;
 update app_private.profiles set
  full_name=app_private.required(p_data,'full_name',160), phone=v_phone,
  emergency_contact_name=trim(coalesce(p_data->>'emergency_contact_name','')), emergency_contact_phone=v_ec_phone,
  program=left(trim(coalesce(p_data->>'program','')),120), year_level=left(trim(coalesce(p_data->>'year_level','')),40),
  dominant_hand=coalesce(p_data->>'dominant_hand',''), bow_type=coalesce(p_data->>'bow_type',''),
  draw_weight=nullif(p_data->>'draw_weight','')::numeric, arrow_length=nullif(p_data->>'arrow_length','')::numeric,
  experience_level=coalesce(p_data->>'experience_level',''), bio=trim(coalesce(p_data->>'bio','')), updated_at=now()
 where id=u;
 perform app_private.note(1,u,'save_profile',p_data);
 return jsonb_build_object('id',u);
end $$;

-- ---------------------------------------------------------------- waitlist
create table app_private.waitlist (
 id uuid primary key default gen_random_uuid(), event_id uuid not null references app_private.events,
 user_id uuid not null references app_private.memberships(user_id), created_at timestamptz not null default now(),
 unique(event_id,user_id)
);
alter table app_private.waitlist enable row level security;
revoke all on app_private.waitlist from public,anon,authenticated;
grant select on app_private.waitlist to authenticated;
create policy waitlist_read on app_private.waitlist for select to authenticated
 using(app_private.can('activities') or (app_private.active_user() and user_id=auth.uid()));

create function app_private.registration_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.events; v_id uuid; v_next app_private.waitlist; v_seats bigint;
begin
 perform app_private.require_member();
 select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
 if not found or not e.published or e.archived then raise exception 'Registration is not open for this activity.'; end if;
 if p_action='register_event' then
  if now()<e.registration_opens then raise exception 'Registration has not opened yet.'; end if;
  if now()>e.registration_closes then raise exception 'Registration is closed.'; end if;
  select id into v_id from app_private.registrations where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','registered'); end if;
  select id into v_id from app_private.waitlist where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','waitlisted'); end if;
  select count(*) into v_seats from app_private.registrations where event_id=e.id;
  if v_seats>=e.capacity then
   insert into app_private.waitlist(event_id,user_id) values(e.id,u) returning id into v_id;
   perform app_private.notify(u,3,e.id,'waitlist','Waitlisted: '||e.title);
   perform app_private.note(3,v_id,'join_waitlist',p_data);
   return jsonb_build_object('id',v_id,'status','waitlisted');
  end if;
  insert into app_private.registrations(event_id,user_id) values(e.id,u) returning id into v_id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
   values(u,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
  perform app_private.note(3,v_id,'register_event',p_data);
  return jsonb_build_object('id',v_id,'status','registered');
 elsif p_action='cancel_registration' then
  if now()>=e.starts_at then raise exception 'This activity has started. Ask an officer to update your record.'; end if;
  delete from app_private.waitlist where event_id=e.id and user_id=u returning id into v_id;
  if v_id is not null then perform app_private.note(3,v_id,'leave_waitlist',p_data); return jsonb_build_object('id',v_id,'status','cancelled'); end if;
  if exists(select 1 from app_private.attendance where event_id=e.id and user_id=u) then
   raise exception 'Attendance is already recorded for this activity.'; end if;
  delete from app_private.registrations where event_id=e.id and user_id=u returning id into v_id;
  if v_id is null then raise exception 'You are not registered for this activity.'; end if;
  update app_private.notifications set completed_at=now() where user_id=u and record_id=e.id and kind='event' and completed_at is null;
  perform app_private.note(3,v_id,'cancel_registration',p_data);
  -- The freed seat goes to the earliest waitlisted member while registration is still open.
  if now()<=e.registration_closes then
   select * into v_next from app_private.waitlist where event_id=e.id order by created_at limit 1 for update;
   if found then
    delete from app_private.waitlist where id=v_next.id;
    insert into app_private.registrations(event_id,user_id) values(e.id,v_next.user_id);
    perform app_private.notify(v_next.user_id,3,e.id,'promoted','A slot opened. You are now registered for '||e.title);
    insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
     values(v_next.user_id,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
    perform app_private.note(3,e.id,'waitlist_promotion',jsonb_build_object('user_id',v_next.user_id));
   end if;
  end if;
  return jsonb_build_object('id',v_id,'status','cancelled');
 end if;
 raise exception 'Unknown registration action.';
end $$;

-- ---------------------------------------------------------------- borrowing cancellation
alter table app_private.borrowings drop constraint borrowings_status_check;
alter table app_private.borrowings add constraint borrowings_status_check
 check(status in ('pending','approved','released','returned','rejected','returned_for_correction','cancelled'));

create function app_private.cancel_borrowing(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); b app_private.borrowings;
begin
 select * into b from app_private.borrowings where id=(p_data->>'id')::uuid and user_id=u for update;
 if not found then raise exception 'Borrowing request not found.'; end if;
 if b.status not in ('pending','approved','returned_for_correction') then
  raise exception 'Only requests that have not been released can be cancelled.'; end if;
 perform app_private.check_version(b.version,p_data);
 update app_private.borrowings set status='cancelled',remark=coalesce(nullif(trim(p_data->>'remark'),''),'Cancelled by member'),version=version+1 where id=b.id;
 update app_private.notifications set completed_at=now() where record_id=b.id and kind='review' and completed_at is null;
 perform app_private.note(5,b.id,'cancel_borrowing',p_data);
 return jsonb_build_object('id',b.id);
end $$;

-- ---------------------------------------------------------------- read models
-- Seat counts only (no identities) for every activity the caller may see.
create function app_private.event_stats() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'event_id',e.id,
  'registered',(select count(*) from app_private.registrations r where r.event_id=e.id),
  'waitlisted',(select count(*) from app_private.waitlist w where w.event_id=e.id),
  'present',(select count(*) from app_private.attendance a where a.event_id=e.id and a.status='present'),
  'waitlist_position',(select p.pos from (select w.user_id,row_number() over(order by w.created_at) pos from app_private.waitlist w where w.event_id=e.id) p where p.user_id=auth.uid())
 )),'[]')
 from app_private.events e
 where app_private.can('activities') or (e.published and (app_private.member() or app_private.can('equipment') or app_private.can('training') or app_private.can('interclub')))
$$;

-- Current state of each item: in_use / overdue / reserved (approved, not released) / available,
-- or the condition when the item is out of service. Borrower identity is not exposed.
create function app_private.equipment_status() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'equipment_id',e.id,
  'state',case when e.condition<>'serviceable' then e.condition
   when exists(select 1 from app_private.borrowings b where b.equipment_id=e.id and b.status='released' and b.ends_at<now()) then 'overdue'
   when exists(select 1 from app_private.borrowings b where b.equipment_id=e.id and b.status='released') then 'in_use'
   when exists(select 1 from app_private.borrowings b where b.equipment_id=e.id and b.status='approved' and b.ends_at>now() and b.starts_at<now()+interval '24 hours') then 'reserved'
   else 'available' end,
  'busy_until',(select max(b.ends_at) from app_private.borrowings b where b.equipment_id=e.id and b.status in ('approved','released') and b.ends_at>now() and b.starts_at<now()+interval '24 hours'),
  'next_booking',(select min(b.starts_at) from app_private.borrowings b where b.equipment_id=e.id and b.status='approved' and b.starts_at>now()),
  'times_borrowed',(select count(*) from app_private.borrowings b where b.equipment_id=e.id and b.status in ('released','returned'))
 )),'[]')
 from app_private.equipment e where app_private.member() or app_private.can('equipment')
$$;

revoke all on function app_private.valid_phone(text),app_private.save_member_profile(jsonb),app_private.registration_command(text,jsonb),
 app_private.cancel_borrowing(jsonb),app_private.event_stats(),app_private.equipment_status() from public,anon,authenticated;
grant execute on function app_private.event_stats(),app_private.equipment_status() to authenticated;

-- ---------------------------------------------------------------- check-in must hold a confirmed seat
-- (unchanged rule; waitlisted members are not in registrations, so the existing check already refuses them)

-- ---------------------------------------------------------------- API entry points
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_member_workflows;
revoke all on function app_private.club_command_before_member_workflows(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='save_profile' then return app_private.save_member_profile(p_data); end if;
 if p_action in ('register_event','cancel_registration') then return app_private.registration_command(p_action,p_data); end if;
 if p_action='cancel_borrowing' then return app_private.cancel_borrowing(p_data); end if;
 if p_action='request_borrowing' and (p_data->>'ends_at')::timestamptz-(p_data->>'starts_at')::timestamptz>interval '14 days' then
  raise exception 'Borrowing periods are limited to 14 days.'; end if;
 return app_private.club_command_before_member_workflows(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

alter function public.club_workspace() set schema app_private;
alter function app_private.club_workspace() rename to club_workspace_base;
revoke all on function app_private.club_workspace_base() from public,anon;
grant execute on function app_private.club_workspace_base() to authenticated;
create function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status());
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611100018_name_corrections.sql
-- Verified names (consultation follow-up).
-- A member's full name is the identity an officer verified when approving the application,
-- and it appears on rosters, borrowings, ballots and reports. Once a membership exists the
-- member can no longer rename themselves; they file a correction that a membership officer
-- (not themselves) approves or rejects, with the decision kept in the audit log.

create table app_private.name_corrections (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references app_private.profiles,
 current_name text not null,
 requested_name text not null check(length(trim(requested_name)) between 1 and 160),
 reason text not null check(length(trim(reason)) between 1 and 500),
 status text not null default 'pending' check(status in ('pending','approved','rejected')),
 decision text not null default '',
 reviewed_by uuid references app_private.profiles,
 created_at timestamptz not null default now(),
 reviewed_at timestamptz
);
create unique index one_pending_name_correction on app_private.name_corrections(user_id) where status='pending';
alter table app_private.name_corrections enable row level security;
revoke all on app_private.name_corrections from public,anon,authenticated;
grant select on app_private.name_corrections to authenticated;
create policy name_correction_read on app_private.name_corrections for select to authenticated
 using(app_private.can('membership') or (app_private.active_user() and user_id=auth.uid()));

-- Same as migration 017, plus: the name is locked once a membership exists.
create or replace function app_private.save_member_profile(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_phone text:=trim(coalesce(p_data->>'phone',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_name text; v_current text;
begin
 v_name:=app_private.required(p_data,'full_name',160);
 select full_name into v_current from app_private.profiles where id=u;
 if trim(v_name)<>trim(v_current) and exists(select 1 from app_private.memberships where user_id=u) then
  raise exception 'Your name was verified when your membership was approved. Request a name correction instead.';
 end if;
 if not app_private.valid_phone(v_phone) then raise exception 'Enter a valid mobile number (for example 0917 123 4567).'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if (v_ec_phone='') <> (trim(coalesce(p_data->>'emergency_contact_name',''))='') then
  raise exception 'Provide both the emergency contact name and number.'; end if;
 update app_private.profiles set
  full_name=v_name, phone=v_phone,
  emergency_contact_name=trim(coalesce(p_data->>'emergency_contact_name','')), emergency_contact_phone=v_ec_phone,
  program=left(trim(coalesce(p_data->>'program','')),120), year_level=left(trim(coalesce(p_data->>'year_level','')),40),
  dominant_hand=coalesce(p_data->>'dominant_hand',''), bow_type=coalesce(p_data->>'bow_type',''),
  draw_weight=nullif(p_data->>'draw_weight','')::numeric, arrow_length=nullif(p_data->>'arrow_length','')::numeric,
  experience_level=coalesce(p_data->>'experience_level',''), bio=trim(coalesce(p_data->>'bio','')), updated_at=now()
 where id=u;
 perform app_private.note(1,u,'save_profile',p_data);
 return jsonb_build_object('id',u);
end $$;

create function app_private.name_correction_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); c app_private.name_corrections; v_id uuid; v_current text; v_requested text;
begin
 if p_action='request_name_correction' then
  if not exists(select 1 from app_private.memberships where user_id=u) then
   raise exception 'Applicants can edit their name directly in their application.'; end if;
  select full_name into v_current from app_private.profiles where id=u;
  v_requested:=trim(app_private.required(p_data,'requested_name',160));
  if v_requested=trim(v_current) then raise exception 'The requested name is the same as your current name.'; end if;
  if exists(select 1 from app_private.name_corrections where user_id=u and status='pending') then
   raise exception 'You already have a pending name correction.'; end if;
  insert into app_private.name_corrections(user_id,current_name,requested_name,reason)
   values(u,v_current,v_requested,trim(app_private.required(p_data,'reason',500))) returning id into v_id;
  perform app_private.notify_role('membership',1,v_id,'Name correction requested');
  perform app_private.note(1,v_id,p_action,p_data);
  return jsonb_build_object('id',v_id);
 elsif p_action='review_name_correction' then
  perform app_private.require_permission('membership');
  select * into c from app_private.name_corrections where id=(p_data->>'id')::uuid for update;
  if not found or c.status<>'pending' then raise exception 'Pending name correction not found.'; end if;
  if c.user_id=u then raise exception 'You cannot review your own name correction.'; end if;
  if p_data->>'status' not in ('approved','rejected') then raise exception 'Invalid correction decision.'; end if;
  perform app_private.required(p_data,'decision');
  if p_data->>'status'='approved' then
   update app_private.profiles set full_name=c.requested_name,updated_at=now() where id=c.user_id;
  end if;
  update app_private.name_corrections set status=p_data->>'status',decision=trim(p_data->>'decision'),reviewed_by=u,reviewed_at=now() where id=c.id;
  update app_private.notifications set completed_at=now() where record_id=c.id and kind='review' and completed_at is null;
  perform app_private.notify(c.user_id,1,c.id,'name_correction',
   case when p_data->>'status'='approved' then 'Name updated to '||c.requested_name else 'Name correction not approved: '||trim(p_data->>'decision') end);
  perform app_private.note(1,c.id,p_action,jsonb_build_object('before',c.current_name,'after',c.requested_name,'status',p_data->>'status','decision',p_data->>'decision'));
  return jsonb_build_object('id',c.id);
 end if;
 raise exception 'Unknown name correction action.';
end $$;
revoke all on function app_private.name_correction_command(text,jsonb) from public,anon,authenticated;

create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='save_profile' then return app_private.save_member_profile(p_data); end if;
 if p_action in ('request_name_correction','review_name_correction') then return app_private.name_correction_command(p_action,p_data); end if;
 if p_action in ('register_event','cancel_registration') then return app_private.registration_command(p_action,p_data); end if;
 if p_action='cancel_borrowing' then return app_private.cancel_borrowing(p_data); end if;
 if p_action='request_borrowing' and (p_data->>'ends_at')::timestamptz-(p_data->>'starts_at')::timestamptz>interval '14 days' then
  raise exception 'Borrowing periods are limited to 14 days.'; end if;
 return app_private.club_command_before_member_workflows(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611100019_workflow_hardening.sql
-- Workflow hardening after the process review (November 2026).
-- Connects modules that were independent and closes the "what if" gaps:
--  * Membership fee before Active: approval creates a "Pending payment" membership and a fee;
--    linking the verified payment activates it. Off while the configured fee is 0.
--  * Bulk fee assessment for every active member.
--  * Documents verified one by one; approval refused while any uploaded document is unverified.
--  * Rejected applicants may start a new application.
--  * Waitlist promotion continues until the activity starts (not only until the deadline).
--  * Walk-in attendance for active members who were not registered.
--  * Archiving an upcoming activity tells registrants and waitlisted members and cancels linked
--    equipment requests.
--  * Equipment: overdue items block new requests; overlapping bookings refused at request time;
--    members confirm receipt; a damaged return alerts the finance officers.
--  * Separation of duties: results of an election are confirmed by someone other than the person
--    who closed voting or entered paper tallies; interclub results are verified by someone other
--    than the person who recorded them.
--  * One attendance-rate definition everywhere: present / (registered - excused).

-- ---------------------------------------------------------------- membership fee before Active
alter table app_private.settings add column membership_fee numeric(12,2) not null default 0 check(membership_fee>=0);
alter table app_private.memberships drop constraint memberships_status_check;
alter table app_private.memberships add constraint memberships_status_check
 check(status in ('Active','Inactive','Suspended','Expired','Pending payment'));

create function app_private.membership_fee_gate() returns trigger language plpgsql security definer set search_path='' as $$
declare v_fee numeric;
begin
 select membership_fee into v_fee from app_private.settings where id;
 if tg_op='INSERT' and coalesce(v_fee,0)>0 and new.status='Active' then new.status:='Pending payment'; end if;
 return new;
end $$;
create trigger membership_fee_gate before insert on app_private.memberships for each row execute function app_private.membership_fee_gate();

create function app_private.membership_fee_assess() returns trigger language plpgsql security definer set search_path='' as $$
declare v_fee numeric;
begin
 if new.status='Pending payment' then
  select membership_fee into v_fee from app_private.settings where id;
  insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by)
   values(new.user_id,'Membership fee',v_fee,current_date+14,new.approved_by);
  perform app_private.notify(new.user_id,1,new.user_id,'membership','Approved. Pay the membership fee to activate your membership.');
 end if;
 return new;
end $$;
create trigger membership_fee_assess after insert on app_private.memberships for each row execute function app_private.membership_fee_assess();

create function app_private.membership_fee_paid() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.payment_id is not null and old.payment_id is null and new.purpose='Membership fee' then
  update app_private.memberships set status='Active',updated_at=now(),version=version+1
   where user_id=new.user_id and status='Pending payment';
  if found then perform app_private.notify(new.user_id,1,new.user_id,'membership','Membership fee verified. Your membership is now Active.'); end if;
 end if;
 return new;
end $$;
create trigger membership_fee_paid after update on app_private.fee_assessments for each row execute function app_private.membership_fee_paid();

-- ---------------------------------------------------------------- equipment receipt
alter table app_private.borrowings add column received_at timestamptz;

-- ---------------------------------------------------------------- seat counts include excused
create or replace function app_private.event_stats() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'event_id',e.id,
  'registered',(select count(*) from app_private.registrations r where r.event_id=e.id),
  'waitlisted',(select count(*) from app_private.waitlist w where w.event_id=e.id),
  'present',(select count(*) from app_private.attendance a where a.event_id=e.id and a.status='present'),
  'excused',(select count(*) from app_private.attendance a where a.event_id=e.id and a.status='excused'),
  'waitlist_position',(select p.pos from (select w.user_id,row_number() over(order by w.created_at) pos from app_private.waitlist w where w.event_id=e.id) p where p.user_id=auth.uid())
 )),'[]')
 from app_private.events e
 where app_private.can('activities') or (e.published and (app_private.member() or app_private.can('equipment') or app_private.can('training') or app_private.can('interclub')))
$$;

-- ---------------------------------------------------------------- new commands
create function app_private.hardening_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.events; b app_private.borrowings; a app_private.applications;
 d app_private.documents; v_id uuid; n integer:=0; v_user uuid;
begin
 case p_action
 when 'set_membership_fee' then
  if not (app_private.can('administration') or app_private.can('finance')) then raise exception 'Permission denied: finance' using errcode='42501'; end if;
  if (p_data->>'amount')::numeric<0 then raise exception 'The fee cannot be negative.'; end if;
  update app_private.settings set membership_fee=(p_data->>'amount')::numeric,updated_at=now() where id=true;
  perform app_private.note(12,null,p_action,p_data); return jsonb_build_object('amount',(p_data->>'amount')::numeric);
 when 'assess_fee_bulk' then
  perform app_private.require_permission('finance');
  if coalesce((p_data->>'amount')::numeric,0)<=0 then raise exception 'Enter an amount above zero.'; end if;
  if (p_data->>'due_on')::date<current_date then raise exception 'Choose a due date that has not passed.'; end if;
  insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by)
   select m.user_id,app_private.required(p_data,'purpose',200),(p_data->>'amount')::numeric,(p_data->>'due_on')::date,u
   from app_private.memberships m where m.status='Active'
   and not exists(select 1 from app_private.fee_assessments f where f.user_id=m.user_id and f.purpose=p_data->>'purpose' and f.due_on=(p_data->>'due_on')::date);
  get diagnostics n=row_count;
  perform app_private.note(12,null,p_action,p_data||jsonb_build_object('assessed',n));
  return jsonb_build_object('assessed',n);
 when 'verify_document' then
  perform app_private.require_permission('membership');
  select * into d from app_private.documents where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Document not found.'; end if;
  if d.owner_id=u then raise exception 'You cannot verify your own document.' using errcode='42501'; end if;
  update app_private.documents set verified_by=u where id=d.id;
  perform app_private.note(d.module,d.record_id,p_action,jsonb_build_object('document_id',d.id,'requirement',d.requirement));
  return jsonb_build_object('id',d.id);
 when 'restart_application' then
  select * into a from app_private.applications where user_id=u for update;
  if not found or a.status<>'rejected' then raise exception 'Only a rejected application can be started again.'; end if;
  if exists(select 1 from app_private.memberships where user_id=u) then raise exception 'An official membership already exists.'; end if;
  update app_private.applications set status='draft',confirmed=false,updated_at=now(),version=version+1 where id=a.id;
  perform app_private.note(2,a.id,p_action,'{}'); return jsonb_build_object('id',a.id);
 when 'record_walk_in' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
  if not found or not e.published or e.archived then raise exception 'Active event not found.'; end if;
  if now()<e.starts_at-interval '2 hours' or now()>e.ends_at+interval '1 day' then raise exception 'Walk-ins can be recorded only around the activity time.'; end if;
  v_user:=(p_data->>'user_id')::uuid;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') then raise exception 'Only active members can be recorded as walk-ins.'; end if;
  delete from app_private.waitlist where event_id=e.id and user_id=v_user;
  insert into app_private.registrations(event_id,user_id) values(e.id,v_user) on conflict(event_id,user_id) do nothing;
  insert into app_private.attendance(event_id,user_id,status,method,encoded_by,remark) values(e.id,v_user,'present','manual',u,'Walk-in')
   on conflict(event_id,user_id) do update set status='present',method='manual',encoded_by=u,remark='Walk-in',version=attendance.version+1 returning id into v_id;
  perform app_private.notify(v_user,4,v_id,'attendance','Recorded as a walk-in at '||e.title);
  perform app_private.note(8,v_id,p_action,p_data); return jsonb_build_object('id',v_id);
 when 'confirm_receipt' then
  select * into b from app_private.borrowings where id=(p_data->>'id')::uuid and user_id=u for update;
  if not found or b.status<>'released' then raise exception 'Only equipment handed over to you can be confirmed.'; end if;
  if b.received_at is not null then return jsonb_build_object('id',b.id); end if;
  update app_private.borrowings set received_at=now() where id=b.id;
  perform app_private.note(5,b.id,p_action,'{}'); return jsonb_build_object('id',b.id);
 end case;
end $$;

-- ---------------------------------------------------------------- API entry point
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_hardening;
revoke all on function app_private.club_command_before_hardening(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb; e app_private.events; v_user uuid; v_actor uuid;
begin
 if p_action in ('set_membership_fee','assess_fee_bulk','verify_document','restart_application','record_walk_in','confirm_receipt') then
  return app_private.hardening_command(p_action,p_data);
 end if;
 -- checks that must run before the original command
 if p_action='request_borrowing' then
  if exists(select 1 from app_private.borrowings where user_id=u and status='released' and ends_at<now()) then
   raise exception 'Return your overdue equipment before requesting more.'; end if;
  if exists(select 1 from app_private.borrowings x where x.equipment_id=(p_data->>'equipment_id')::uuid and x.status in ('approved','released')
     and x.id is distinct from nullif(p_data->>'id','')::uuid
     and tstzrange(x.starts_at,x.ends_at,'[)') && tstzrange((p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,'[)')) then
   raise exception 'This item is already booked for that time. Choose another time or item.'; end if;
 elsif p_action='review_application' and p_data->>'status'='approved' then
  if exists(select 1 from app_private.documents where module=2 and record_id=(p_data->>'id')::uuid and verified_by is null) then
   raise exception 'Verify every uploaded document before approving.'; end if;
 elsif p_action='confirm_results' then
  if exists(select 1 from app_private.audit_log where module=9 and record_id=(p_data->>'id')::uuid and actor=u
     and (action='record_external_results' or (action='set_election_status' and detail->>'status'='closed'))) then
   raise exception 'Another COMELEC member must confirm results you closed or entered.' using errcode='42501'; end if;
 elsif p_action='verify_result' then
  select actor into v_actor from app_private.audit_log where module=15 and record_id=(p_data->>'id')::uuid and action='record_result' order by created_at desc, id desc limit 1;
  if v_actor=u then raise exception 'Another officer must verify a result you recorded.' using errcode='42501'; end if;
 elsif p_action='archive_event' then
  select * into e from app_private.events where id=(p_data->>'id')::uuid;
 end if;

 result:=app_private.club_command_before_hardening(p_action,p_data);

 -- consequences that follow the original command
 if p_action='archive_event' and e.id is not null and not e.archived and e.ends_at>now() then
  for v_user in select user_id from app_private.registrations where event_id=e.id union select user_id from app_private.waitlist where event_id=e.id loop
   perform app_private.notify(v_user,3,e.id,'event_cancelled','Activity cancelled: '||e.title);
  end loop;
  update app_private.notifications set completed_at=now() where record_id=e.id and kind='event' and completed_at is null;
  delete from app_private.waitlist where event_id=e.id;
  update app_private.borrowings set status='cancelled',remark='Activity cancelled',version=version+1
   where event_id=e.id and status in ('pending','approved','returned_for_correction');
 elsif p_action='review_borrowing' and p_data->>'status'='returned' and p_data->>'condition'='damaged' then
  perform app_private.notify_role('finance',12,(p_data->>'id')::uuid,'Equipment returned damaged: decide on a repair fee');
 end if;
 return result;
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
revoke all on function app_private.hardening_command(text,jsonb),app_private.membership_fee_gate(),app_private.membership_fee_assess(),app_private.membership_fee_paid() from public,anon,authenticated;

-- ---------------------------------------------------------------- waitlist promotion until the activity starts
create or replace function app_private.registration_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.events; v_id uuid; v_next app_private.waitlist; v_seats bigint;
begin
 perform app_private.require_member();
 select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
 if not found or not e.published or e.archived then raise exception 'Registration is not open for this activity.'; end if;
 if p_action='register_event' then
  if now()<e.registration_opens then raise exception 'Registration has not opened yet.'; end if;
  if now()>e.registration_closes then raise exception 'Registration is closed.'; end if;
  select id into v_id from app_private.registrations where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','registered'); end if;
  select id into v_id from app_private.waitlist where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','waitlisted'); end if;
  select count(*) into v_seats from app_private.registrations where event_id=e.id;
  if v_seats>=e.capacity then
   insert into app_private.waitlist(event_id,user_id) values(e.id,u) returning id into v_id;
   perform app_private.notify(u,3,e.id,'waitlist','Waitlisted: '||e.title);
   perform app_private.note(3,v_id,'join_waitlist',p_data);
   return jsonb_build_object('id',v_id,'status','waitlisted');
  end if;
  insert into app_private.registrations(event_id,user_id) values(e.id,u) returning id into v_id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
   values(u,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
  perform app_private.note(3,v_id,'register_event',p_data);
  return jsonb_build_object('id',v_id,'status','registered');
 elsif p_action='cancel_registration' then
  if now()>=e.starts_at then raise exception 'This activity has started. Ask an officer to update your record.'; end if;
  delete from app_private.waitlist where event_id=e.id and user_id=u returning id into v_id;
  if v_id is not null then perform app_private.note(3,v_id,'leave_waitlist',p_data); return jsonb_build_object('id',v_id,'status','cancelled'); end if;
  if exists(select 1 from app_private.attendance where event_id=e.id and user_id=u) then
   raise exception 'Attendance is already recorded for this activity.'; end if;
  delete from app_private.registrations where event_id=e.id and user_id=u returning id into v_id;
  if v_id is null then raise exception 'You are not registered for this activity.'; end if;
  update app_private.notifications set completed_at=now() where user_id=u and record_id=e.id and kind='event' and completed_at is null;
  perform app_private.note(3,v_id,'cancel_registration',p_data);
  -- The freed seat goes to the earliest waitlisted member until the activity starts.
  if now()<e.starts_at then
   select * into v_next from app_private.waitlist where event_id=e.id order by created_at limit 1 for update;
   if found then
    delete from app_private.waitlist where id=v_next.id;
    insert into app_private.registrations(event_id,user_id) values(e.id,v_next.user_id);
    perform app_private.notify(v_next.user_id,3,e.id,'promoted','A slot opened. You are now registered for '||e.title);
    insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
     values(v_next.user_id,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
    perform app_private.note(3,e.id,'waitlist_promotion',jsonb_build_object('user_id',v_next.user_id));
   end if;
  end if;
  return jsonb_build_object('id',v_id,'status','cancelled');
 end if;
 raise exception 'Unknown registration action.';
end $$;

create or replace function public.club_report_data(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb:='{}';begin
 perform app_private.require_permission('reports');
 if p_to<p_from then raise exception 'Invalid reporting period.';end if;
 if app_private.can('membership') then
 result:=result||jsonb_build_object('membership',jsonb_build_object('active',(select count(*) from app_private.memberships where status='Active'),'total',(select count(*) from app_private.memberships),'active_ratio',(select round(100.0*count(*) filter(where status='Active')/nullif(count(*),0),2) from app_private.memberships),'applications',(select count(*) from app_private.applications where submitted_at::date between p_from and p_to),'conversion_rate',(select round(100.0*count(*) filter(where status='approved')/nullif(count(*),0),2) from app_private.applications where submitted_at::date between p_from and p_to),'incomplete',(select count(*) from app_private.applications where status in ('incomplete','returned_for_correction'))));end if;
 if app_private.can('activities') then
 result:=result||jsonb_build_object('activities',(select coalesce(jsonb_agg(x),'[]') from(select e.id,e.title,(select count(*) from app_private.registrations where event_id=e.id) registrations,(select count(*) from app_private.attendance where event_id=e.id and status='present') present,(select count(*) from app_private.attendance where event_id=e.id and status='excused') excused,(select round(100.0*(select count(*) from app_private.attendance where event_id=e.id and status='present')/nullif(count(*)-(select count(*) from app_private.attendance where event_id=e.id and status='excused'),0),2) from app_private.registrations where event_id=e.id) attendance_rate from app_private.events e where e.starts_at::date between p_from and p_to)x));end if;
 if app_private.can('equipment') then
 result:=result||jsonb_build_object('equipment',jsonb_build_object('items',(select count(*) from app_private.equipment),'serviceable',(select count(*) from app_private.equipment where condition='serviceable'),'utilized_items',(select count(distinct equipment_id) from app_private.borrowings where released_at::date between p_from and p_to),'overdue',(select count(*) from app_private.borrowings where status='released' and ends_at<now()),'pending',(select count(*) from app_private.borrowings where status='pending')));end if;
 if app_private.can('training') then
 result:=result||jsonb_build_object('training',(select coalesce(jsonb_agg(x),'[]') from(select user_id,distance,target_face,category,scoring_format,maximum_score,training_date,count(*) sessions,round(avg(score),2) average_score from app_private.training_records where validation='validated' and training_date between p_from and p_to group by user_id,distance,target_face,category,scoring_format,maximum_score,training_date order by training_date)x));end if;
 if app_private.can('finance') then
 result:=result||jsonb_build_object('finance',jsonb_build_object('verified_payments',(select coalesce(sum(amount),0) from app_private.financial_records where kind='payment' and status='verified' and verified_at::date between p_from and p_to),'verified_expenses',(select coalesce(sum(amount),0) from app_private.financial_records where kind in ('expense','reimbursement') and status='verified' and verified_at::date between p_from and p_to),'outstanding_reimbursements',(select coalesce(sum(amount),0) from app_private.financial_records where kind='reimbursement' and status='pending_verification'),'collection_completion',(select round(100.0*count(*) filter(where payment_id is not null)/nullif(count(*),0),2) from app_private.fee_assessments where due_on between p_from and p_to)));end if;
 if app_private.can('interclub') then result:=result||jsonb_build_object('interclub',(select coalesce(jsonb_agg(x),'[]') from(select i.event_id,i.shared_title,i.status,(select count(*) from app_private.delegates where event_id=i.event_id) delegates,(select count(*) from app_private.interclub_results where event_id=i.event_id and verified_by is not null) verified_results from app_private.interclub_events i where shared_start::date between p_from and p_to)x));end if;
 return result||jsonb_build_object('from',p_from,'to',p_to,'generated_at',now(),'limitation','Attendance rate = present / (registered - excused). Recorded data only. Missing attendance, unvalidated training, and unverified finance are not assumed complete. Null rates have no denominator.');end
$$;

create or replace function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;g app_private.goals;data jsonb;actual numeric;goals jsonb:='[]';begin
 perform app_private.require_permission('reports');
 result:=public.club_report_data(p_from,p_to);
 for g in select * from app_private.goals where starts_on<=p_to and ends_on>=p_from loop
  data:=public.club_report_data(g.starts_on,g.ends_on);actual:=null;
  case g.metric
   when 'active_members' then actual:=(data->'membership'->>'active')::numeric;
   when 'applicant_conversion' then actual:=(data->'membership'->>'conversion_rate')::numeric;
   when 'attendance_rate' then select round(100.0*sum((v->>'present')::numeric)/nullif(sum((v->>'registrations')::numeric-coalesce((v->>'excused')::numeric,0)),0),2) into actual from jsonb_array_elements(coalesce(data->'activities','[]')) v;
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

-- SOURCE: 202611100020_registration_fixes.sql
-- Registration fixes found while walking the sign-up → application → approval story end to end.
--  * A replaced document no longer blocks approval. Uploading a new file for the same requirement
--    (allowed only while the application is editable) marks the earlier file superseded; officers
--    verify the current file, and the old one stays on record for the audit trail.
--  * Starting again after a rejection clears earlier verifications, so the next decision is made
--    on documents someone has checked for this application.
alter table app_private.documents add column superseded_by uuid references app_private.documents(id);

create function app_private.supersede_document() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.module in (1,2) then
  update app_private.documents set superseded_by=new.id
   where module=new.module and record_id=new.record_id and requirement=new.requirement and owner_id=new.owner_id
     and id<>new.id and superseded_by is null;
 end if;
 return new;
end $$;
create trigger supersede_document after insert on app_private.documents for each row execute function app_private.supersede_document();

create function app_private.reset_verifications_on_restart() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if old.status='rejected' and new.status='draft' then
  update app_private.documents set verified_by=null where module=2 and record_id=new.id;
 end if;
 return new;
end $$;
create trigger reset_verifications_on_restart after update on app_private.applications for each row execute function app_private.reset_verifications_on_restart();

-- Same dispatcher as 202611100019, with superseded files excluded from the approval check.
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb; e app_private.events; v_user uuid; v_actor uuid;
begin
 if p_action in ('set_membership_fee','assess_fee_bulk','verify_document','restart_application','record_walk_in','confirm_receipt') then
  return app_private.hardening_command(p_action,p_data);
 end if;
 -- checks that must run before the original command
 if p_action='request_borrowing' then
  if exists(select 1 from app_private.borrowings where user_id=u and status='released' and ends_at<now()) then
   raise exception 'Return your overdue equipment before requesting more.'; end if;
  if exists(select 1 from app_private.borrowings x where x.equipment_id=(p_data->>'equipment_id')::uuid and x.status in ('approved','released')
     and x.id is distinct from nullif(p_data->>'id','')::uuid
     and tstzrange(x.starts_at,x.ends_at,'[)') && tstzrange((p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,'[)')) then
   raise exception 'This item is already booked for that time. Choose another time or item.'; end if;
 elsif p_action='review_application' and p_data->>'status'='approved' then
  if exists(select 1 from app_private.documents where module=2 and record_id=(p_data->>'id')::uuid and verified_by is null and superseded_by is null) then
   raise exception 'Verify every uploaded document before approving.'; end if;
 elsif p_action='confirm_results' then
  if exists(select 1 from app_private.audit_log where module=9 and record_id=(p_data->>'id')::uuid and actor=u
     and (action='record_external_results' or (action='set_election_status' and detail->>'status'='closed'))) then
   raise exception 'Another COMELEC member must confirm results you closed or entered.' using errcode='42501'; end if;
 elsif p_action='verify_result' then
  select actor into v_actor from app_private.audit_log where module=15 and record_id=(p_data->>'id')::uuid and action='record_result' order by created_at desc, id desc limit 1;
  if v_actor=u then raise exception 'Another officer must verify a result you recorded.' using errcode='42501'; end if;
 elsif p_action='archive_event' then
  select * into e from app_private.events where id=(p_data->>'id')::uuid;
 end if;

 result:=app_private.club_command_before_hardening(p_action,p_data);

 -- consequences that follow the original command
 if p_action='archive_event' and e.id is not null and not e.archived and e.ends_at>now() then
  for v_user in select user_id from app_private.registrations where event_id=e.id union select user_id from app_private.waitlist where event_id=e.id loop
   perform app_private.notify(v_user,3,e.id,'event_cancelled','Activity cancelled: '||e.title);
  end loop;
  update app_private.notifications set completed_at=now() where record_id=e.id and kind='event' and completed_at is null;
  delete from app_private.waitlist where event_id=e.id;
  update app_private.borrowings set status='cancelled',remark='Activity cancelled',version=version+1
   where event_id=e.id and status in ('pending','approved','returned_for_correction');
 elsif p_action='review_borrowing' and p_data->>'status'='returned' and p_data->>'condition'='damaged' then
  perform app_private.notify_role('finance',12,(p_data->>'id')::uuid,'Equipment returned damaged: decide on a repair fee');
 end if;
 return result;
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
revoke all on function app_private.supersede_document(),app_private.reset_verifications_on_restart() from public,anon,authenticated;

-- SOURCE: 202611110021_profile_name_from_application.sql
-- Applicant names show everywhere as soon as they apply.
-- Sign-up only asks for an email and password, so a new profile has no name until the
-- application is approved. Officers then saw unnamed accounts ("Club record") in pickers
-- such as Assign role. While someone has no membership yet, their profile name follows
-- the name on their application; once a membership exists the verified-name lock from
-- migration 018 applies and only an approved name correction changes it.

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

-- SOURCE: 202611110022_payment_reference_required.sql
-- A payment must carry the reference number printed on its receipt (GCash "Ref No.", bank
-- transaction number, OR number). The treasurer checks it against the club's account, and
-- one receipt can only pay once. Expenses and reimbursements keep the reference optional.

create or replace function app_private.payment_reference_check() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_ref text;
begin
 if new.kind<>'payment' then return new; end if;
 v_ref:=upper(regexp_replace(coalesce(new.reference,''),'[\s]','','g'));
 if v_ref !~ '^[A-Z0-9-]{6,30}$' then
  raise exception 'Enter the reference number from your receipt (6 to 30 letters or numbers, e.g. the GCash Ref No.).';
 end if;
 if exists(select 1 from app_private.financial_records r where r.kind='payment' and r.id<>new.id and r.status<>'rejected'
   and upper(regexp_replace(r.reference,'[\s]','','g'))=v_ref) then
  raise exception 'This reference number is already on another payment. Check the number on your receipt.';
 end if;
 new.reference:=v_ref;
 return new;
end $$;
revoke all on function app_private.payment_reference_check() from public,anon,authenticated;

create trigger payment_reference_check before insert or update of reference, kind on app_private.financial_records
 for each row execute function app_private.payment_reference_check();

-- SOURCE: 202611120023_payment_details.sql
-- Where to pay. The Treasurer records the club's GCash and bank details once; every
-- applicant's pay step shows them. Any GCash or bank transfer is accepted as long as the
-- receipt carries a reference number (migration 022).

alter table app_private.settings add column payment_details jsonb not null default '{}'::jsonb;

create function public.set_payment_details(p_details jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v jsonb:='{}'::jsonb; k text;
begin
 perform app_private.require_user();
 if not (app_private.can('finance') or app_private.can('administration')) then raise exception 'Permission denied: finance' using errcode='42501'; end if;
 foreach k in array array['gcash_name','gcash_number','bank_name','bank_account_name','bank_account_number'] loop
  if length(trim(coalesce(p_details->>k,'')))>120 then raise exception 'Keep each payment detail under 120 characters.'; end if;
  v:=v||jsonb_build_object(k,trim(coalesce(p_details->>k,'')));
 end loop;
 if (v->>'gcash_number')='' and (v->>'bank_account_number')='' then raise exception 'Enter at least a GCash number or a bank account number.'; end if;
 update app_private.settings set payment_details=v,updated_at=now() where id;
 perform app_private.note(12,null,'set_payment_details',v);
 return v;
end $$;
revoke all on function public.set_payment_details(jsonb) from public,anon;
grant execute on function public.set_payment_details(jsonb) to authenticated;

-- SOURCE: 202611120024_one_notice_per_event.sql
-- One notice per event, in plain words.
-- Approving an application with a fee used to send three notices ("approved: ",
-- "Approved. Pay the membership fee...", "Fee assessment: Membership fee"). The fee notice is
-- the one worth keeping: it carries the due date and completes itself once the fee is paid.
-- Officer decisions also arrived as "<status>: <remark>", which read as "approved:" when the
-- remark was empty.

create or replace function app_private.peso_text(p numeric) returns text language sql immutable set search_path='' as $$
 select '₱'||case when p=trunc(p) then to_char(p,'FM999,999,990') else to_char(p,'FM999,999,990.00') end
$$;

create or replace function app_private.tidy_notice() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_status text; v_remark text; f app_private.fee_assessments;
begin
 if new.kind in ('screening','finance') and position(': ' in new.message)>0 then
  v_status:=split_part(new.message,': ',1); v_remark:=trim(substr(new.message,length(v_status)+3));
 end if;
 if new.kind='screening' then
  -- Approved with a fee: the fee notice says it all.
  if v_status='approved' and exists(select 1 from app_private.memberships where user_id=new.user_id and status='Pending payment') and v_remark='' then return null; end if;
  new.message:=case v_status
   when 'approved' then 'Your membership application was approved.'
   when 'returned_for_correction' then 'Changes requested on your application'
   when 'rejected' then 'Your membership application was not approved'
   when 'under_review' then 'Your application is being reviewed.'
   else new.message end
   ||case when v_status is not null and v_remark<>'' then ': '||v_remark else (case when v_status in ('returned_for_correction','rejected') then '.' else '' end) end;
 elsif new.kind='finance' and v_status is not null then
  new.message:=case v_status
   when 'verified' then 'Your payment was verified'
   when 'returned_for_correction' then 'Your payment was sent back'
   when 'incomplete' then 'Your payment is incomplete'
   when 'rejected' then 'Your payment was not accepted'
   else v_status end
   ||case when v_remark<>'' and v_status<>'verified' then ': '||v_remark else '.' end;
 elsif new.kind='membership' and new.message like 'Approved. Pay the membership fee%' then
  return null;
 elsif new.kind='fee' then
  select * into f from app_private.fee_assessments where id=new.record_id;
  -- the text must not depend on payment_id: paying re-sends the same notice to mark it complete
  if found and f.purpose='Membership fee' then
   new.message:='Approved! Pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.';
  elsif found then
   new.message:='Fee due: '||f.purpose||' · '||app_private.peso_text(f.amount)||' by '||to_char(f.due_on,'Dy, Mon FMDD');
  end if;
 end if;
 return new;
end $$;
revoke all on function app_private.tidy_notice(),app_private.peso_text(numeric) from public,anon,authenticated;

create trigger tidy_notice before insert on app_private.notifications for each row execute function app_private.tidy_notice();

-- Clean up notices already sent.
delete from app_private.notifications n where n.kind='membership' and n.message like 'Approved. Pay the membership fee%';
delete from app_private.notifications n where n.kind='screening' and n.message='approved: '
 and exists(select 1 from app_private.memberships m where m.user_id=n.user_id);
update app_private.notifications n set message='Approved! Pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.'
 from app_private.fee_assessments f where n.kind='fee' and n.record_id=f.id and f.purpose='Membership fee' and n.message='Fee assessment: Membership fee';

-- SOURCE: 202611120025_no_duplicate_roles.sql
-- A person holds a role once. Assigning a role someone already holds (and that is not about to
-- end) is refused; renewing is allowed in the last 30 days of a term or after it ends.

create or replace function app_private.no_duplicate_role() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_end timestamptz; v_role text;
begin
 select ra.ends_at into v_end from app_private.role_assignments ra
  where ra.user_id=new.user_id and ra.role_id=new.role_id and ra.retired_at is null and ra.ends_at>now()+interval '30 days'
  order by ra.ends_at desc limit 1;
 if found then
  select name into v_role from app_private.roles where id=new.role_id;
  raise exception 'This person is already % until %. Renew the term in its last 30 days instead.', v_role, to_char(v_end,'Mon FMDD, YYYY');
 end if;
 return new;
end $$;
revoke all on function app_private.no_duplicate_role() from public,anon,authenticated;

create trigger no_duplicate_role before insert on app_private.role_assignments for each row execute function app_private.no_duplicate_role();

-- SOURCE: 202611120026_role_access.sql
-- Roles & access. Each club decides which desks a role opens (Administration -> Roles & access).
-- A role is a name plus a set of permissions; every action already checks that set, so changing
-- it changes what holders can see and do on their next refresh. Built-in roles remember their
-- default so a club can undo its changes. The last-administrator rule still applies.

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

-- SOURCE: 202611120027_equipment_photos.sql
-- Equipment photos. A photo is an ordinary club document (module 10, requirement "Photo") attached to an
-- equipment item, so it reuses the upload, storage and audit path every other attachment uses. Only the
-- Equipment desk can attach one; every active member can view it when browsing gear.

create or replace function app_private.document_access(p_module integer,p_record uuid,p_write boolean default false) returns boolean language plpgsql stable security definer set search_path='' as $$
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
 -- Equipment photos: the Equipment desk adds them; any active member can see them in the catalog.
 when 10 then return exists(select 1 from app_private.equipment where id=p_record) and (app_private.can('equipment')
  or (not p_write and exists(select 1 from app_private.memberships where user_id=u and status='Active')));
 else return false;end case;end
$$;

-- SOURCE: 202611120028_clear_approval_notice.sql
-- Say what was approved. "Approved! Pay the fee" read as if the payment had been approved; the approval
-- is the application's, and paying comes next.

create or replace function app_private.tidy_notice() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_status text; v_remark text; f app_private.fee_assessments;
begin
 if new.kind in ('screening','finance') and position(': ' in new.message)>0 then
  v_status:=split_part(new.message,': ',1); v_remark:=trim(substr(new.message,length(v_status)+3));
 end if;
 if new.kind='screening' then
  -- Approved with a fee: the fee notice says it all.
  if v_status='approved' and exists(select 1 from app_private.memberships where user_id=new.user_id and status='Pending payment') and v_remark='' then return null; end if;
  new.message:=case v_status
   when 'approved' then 'Your membership application was approved.'
   when 'returned_for_correction' then 'Changes requested on your application'
   when 'rejected' then 'Your membership application was not approved'
   when 'under_review' then 'Your application is being reviewed.'
   else new.message end
   ||case when v_status is not null and v_remark<>'' then ': '||v_remark else (case when v_status in ('returned_for_correction','rejected') then '.' else '' end) end;
 elsif new.kind='finance' and v_status is not null then
  new.message:=case v_status
   when 'verified' then 'Your payment was verified'
   when 'returned_for_correction' then 'Your payment was sent back'
   when 'incomplete' then 'Your payment is incomplete'
   when 'rejected' then 'Your payment was not accepted'
   else v_status end
   ||case when v_remark<>'' and v_status<>'verified' then ': '||v_remark else '.' end;
 elsif new.kind='membership' and new.message like 'Approved. Pay the membership fee%' then
  return null;
 elsif new.kind='fee' then
  select * into f from app_private.fee_assessments where id=new.record_id;
  -- the text must not depend on payment_id: paying re-sends the same notice to mark it complete
  if found and f.purpose='Membership fee' then
   new.message:='Your application was approved. Next, pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.';
  elsif found then
   new.message:='Fee due: '||f.purpose||' · '||app_private.peso_text(f.amount)||' by '||to_char(f.due_on,'Dy, Mon FMDD');
  end if;
 end if;
 return new;
end $$;

update app_private.notifications set message=replace(message,'Approved! Pay the ','Your application was approved. Next, pay the ')
 where kind='fee' and message like 'Approved! Pay the %';

-- SOURCE: 202611120029_clear_review_notices.sql
-- Officer review notices say whose submission they are about. Before, the Treasurer saw identical
-- "Financial submission requires review" cards. (Handled ones already get completed_at from
-- sync_workflow_notices; the app now hides completed notices.)

create or replace function app_private.tidy_notice() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_status text; v_remark text; f app_private.fee_assessments;
begin
 if new.kind in ('screening','finance') and position(': ' in new.message)>0 then
  v_status:=split_part(new.message,': ',1); v_remark:=trim(substr(new.message,length(v_status)+3));
 end if;
 if new.kind='screening' then
  -- Approved with a fee: the fee notice says it all.
  if v_status='approved' and exists(select 1 from app_private.memberships where user_id=new.user_id and status='Pending payment') and v_remark='' then return null; end if;
  new.message:=case v_status
   when 'approved' then 'Your membership application was approved.'
   when 'returned_for_correction' then 'Changes requested on your application'
   when 'rejected' then 'Your membership application was not approved'
   when 'under_review' then 'Your application is being reviewed.'
   else new.message end
   ||case when v_status is not null and v_remark<>'' then ': '||v_remark else (case when v_status in ('returned_for_correction','rejected') then '.' else '' end) end;
 elsif new.kind='finance' and v_status is not null then
  new.message:=case v_status
   when 'verified' then 'Your payment was verified'
   when 'returned_for_correction' then 'Your payment was sent back'
   when 'incomplete' then 'Your payment is incomplete'
   when 'rejected' then 'Your payment was not accepted'
   else v_status end
   ||case when v_remark<>'' and v_status<>'verified' then ': '||v_remark else '.' end;
 elsif new.kind='membership' and new.message like 'Approved. Pay the membership fee%' then
  return null;
 elsif new.kind='review' and new.module=12 and new.message='Financial submission requires review' then
  -- Name who sent what, so the Treasurer can tell submissions apart. Kept to fields that don't change on a
  -- resubmission, so a corrected submission reopens the same notice instead of adding another.
  select 'Check '||coalesce(nullif(p.full_name,''),'a member')||'''s '||case r.kind when 'payment' then 'payment' when 'reimbursement' then 'reimbursement request' else 'expense' end||' · '||r.purpose
    into new.message
    from app_private.financial_records r left join app_private.profiles p on p.id=r.user_id where r.id=new.record_id;
  new.message:=coalesce(new.message,'A financial submission needs checking');
 elsif new.kind='review' and new.module=10 and new.message='Borrowing request requires review' then
  select coalesce(nullif(p.full_name,''),'A member')||' asked to borrow '||e.label
    into new.message
    from app_private.borrowings b join app_private.equipment e on e.id=b.equipment_id left join app_private.profiles p on p.id=b.user_id where b.id=new.record_id;
  new.message:=coalesce(new.message,'A borrowing request needs review');
 elsif new.kind='fee' then
  select * into f from app_private.fee_assessments where id=new.record_id;
  -- the text must not depend on payment_id: paying re-sends the same notice to mark it complete
  if found and f.purpose='Membership fee' then
   new.message:='Your application was approved. Next, pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.';
  elsif found then
   new.message:='Fee due: '||f.purpose||' · '||app_private.peso_text(f.amount)||' by '||to_char(f.due_on,'Dy, Mon FMDD');
  end if;
 end if;
 return new;
end $$;

update app_private.notifications n set message='Check '||coalesce(nullif(p.full_name,''),'a member')||'''s '||case r.kind when 'payment' then 'payment' when 'reimbursement' then 'reimbursement request' else 'expense' end||' · '||r.purpose
 from app_private.financial_records r left join app_private.profiles p on p.id=r.user_id
 where n.kind='review' and n.module=12 and n.record_id=r.id and n.message='Financial submission requires review'
   and not exists(select 1 from app_private.notifications d where d.user_id=n.user_id and d.record_id=n.record_id and d.kind='review' and d.id<>n.id);
update app_private.notifications n set message=coalesce(nullif(p.full_name,''),'A member')||' asked to borrow '||e.label
 from app_private.borrowings b join app_private.equipment e on e.id=b.equipment_id left join app_private.profiles p on p.id=b.user_id
 where n.kind='review' and n.module=10 and n.record_id=b.id and n.message='Borrowing request requires review'
   and not exists(select 1 from app_private.notifications d where d.user_id=n.user_id and d.record_id=n.record_id and d.kind='review' and d.id<>n.id);

-- SOURCE: 202611120030_round_arrows.sql
-- Rounds keep their arrows. A round can carry its ends and each arrow's score (X, 10..1, M), plus where it hit
-- when a scoring app exported positions. When arrows are sent, the server works out the score and maximum
-- from them, so the total can never disagree with the arrows. Rounds without arrows keep working as before.

alter table app_private.training_records add column arrows jsonb;

create function app_private.arrow_totals(p jsonb, out score integer, out arrows integer)
language plpgsql immutable set search_path='' as $$
declare e jsonb; a jsonb; v text;
begin
 score:=0; arrows:=0;
 if jsonb_typeof(p->'ends') is distinct from 'array' or jsonb_array_length(p->'ends') not between 1 and 60 then
  raise exception 'A round needs 1 to 60 ends.'; end if;
 for e in select value from jsonb_array_elements(p->'ends') loop
  if jsonb_typeof(e) is distinct from 'array' or jsonb_array_length(e) not between 1 and 24 then
   raise exception 'Each end needs 1 to 24 arrows.'; end if;
  for a in select value from jsonb_array_elements(e) loop
   v:=upper(coalesce(a->>'p',''));
   if v not in ('X','10','9','8','7','6','5','4','3','2','1','M') then raise exception 'Arrow scores are X, 10 to 1, or M.'; end if;
   if (a ? 'x' and jsonb_typeof(a->'x')<>'number') or (a ? 'y' and jsonb_typeof(a->'y')<>'number') then
    raise exception 'Arrow positions must be numbers.'; end if;
   score:=score+case v when 'X' then 10 when 'M' then 0 else v::integer end;
   arrows:=arrows+1;
  end loop;
 end loop;
end $$;
revoke all on function app_private.arrow_totals(jsonb) from public,anon,authenticated;

-- Save a round (coach: save_training, member: save_personal_training) with or without arrows; optionally send it
-- to the coach in the same step. Permissions are the training commands' own.
create function public.save_round(p_action text,p_data jsonb,p_submit boolean default false) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_score integer; v_count integer; v_data jsonb:=p_data-'arrows'; v_result jsonb; v_id uuid;
 v_arrows jsonb:=nullif(p_data->'arrows','null'::jsonb);
begin
 perform app_private.require_user();
 if p_action not in ('save_training','save_personal_training') then raise exception 'Unknown action.'; end if;
 if v_arrows is not null then
  select score,arrows into v_score,v_count from app_private.arrow_totals(v_arrows);
  v_data:=v_data||jsonb_build_object('score',v_score,'maximum_score',v_count*10);
 end if;
 v_result:=app_private.training_command(p_action,v_data);
 v_id:=(v_result->>'id')::uuid;
 if p_data ? 'arrows' then update app_private.training_records set arrows=v_arrows where id=v_id; end if;
 if p_submit then perform app_private.training_command('submit_training',jsonb_build_object('id',v_id)); end if;
 return v_result;
end $$;
revoke all on function public.save_round(text,jsonb,boolean) from public,anon;
grant execute on function public.save_round(text,jsonb,boolean) to authenticated;

-- The coach's CSV import, arrows included. All rows save or none do.
create function public.import_rounds(p_rows jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare entry jsonb; results jsonb:='[]';
begin
 perform app_private.require_user();
 perform app_private.require_permission('training');
 if jsonb_typeof(p_rows) is distinct from 'array' or jsonb_array_length(p_rows) not between 1 and 500 then
  raise exception 'Import 1 to 500 rounds at a time.'; end if;
 for entry in select value from jsonb_array_elements(p_rows) loop
  perform app_private.required(entry,'import_key',200);
  if exists(select 1 from app_private.training_records where import_key=entry->>'import_key') then
   raise exception 'This file was already imported (%). Nothing was saved.',entry->>'import_key'; end if;
  results:=results||jsonb_build_array(public.save_round('save_training',entry,false));
 end loop;
 return results;
end $$;
revoke all on function public.import_rounds(jsonb) from public,anon;
grant execute on function public.import_rounds(jsonb) to authenticated;

-- SOURCE: 202611120031_delete_records.sql
-- Delete what can safely disappear; everything official keeps its archive/retire path instead.
-- Deletable:
--   training_round  the owner's personal or returned round; a coach may delete any round (reason required
--                   once validated, e.g. a duplicate import)
--   payment_draft   the owner's financial submission while it is still a draft
--   event_draft     an unpublished event nobody registered for (Events & attendance)
--   equipment       an item that was never borrowed (otherwise mark it Retired)
--   role            a club-made role nobody has ever held (Administration)
-- Each delete is written to the activity log with who, why and a copy of what was removed.

create function public.delete_record(p_kind text,p_id uuid,p_reason text default '') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_reason text:=trim(coalesce(p_reason,'')); v_row jsonb; v_module integer;
 t app_private.training_records; f app_private.financial_records; e app_private.events; q app_private.equipment;
begin
 case p_kind
 when 'training_round' then
  select * into t from app_private.training_records where id=p_id for update;
  if not found then raise exception 'This round no longer exists. Refresh and try again.'; end if;
  if t.user_id=u and t.validation in ('personal','returned_for_correction') then null;
  elsif app_private.can('training') then
   if t.validation='validated' and v_reason='' then raise exception 'Say why this official round is being deleted.'; end if;
  else raise exception 'Only your own personal rounds can be deleted.' using errcode='42501'; end if;
  v_row:=to_jsonb(t); v_module:=11;
  delete from app_private.corrections where module in (4,11) and record_id=p_id;
  delete from app_private.documents where module in (4,11) and record_id=p_id;
  delete from app_private.training_records where id=p_id;
 when 'payment_draft' then
  select * into f from app_private.financial_records where id=p_id for update;
  if not found then raise exception 'This submission no longer exists. Refresh and try again.'; end if;
  if f.user_id<>u or f.status<>'draft' then raise exception 'Only your own drafts can be deleted. Sent submissions stay on record.' using errcode='42501'; end if;
  v_row:=to_jsonb(f); v_module:=12;
  delete from app_private.documents where module=12 and record_id=p_id;
  delete from app_private.financial_records where id=p_id;
 when 'event_draft' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=p_id for update;
  if not found then raise exception 'This event no longer exists. Refresh and try again.'; end if;
  if e.published then raise exception 'Published events stay on record. Archive it instead.'; end if;
  v_row:=to_jsonb(e); v_module:=8;
  delete from app_private.events where id=p_id;
 when 'equipment' then
  perform app_private.require_permission('equipment');
  select * into q from app_private.equipment where id=p_id for update;
  if not found then raise exception 'This item no longer exists. Refresh and try again.'; end if;
  if exists(select 1 from app_private.borrowings where equipment_id=p_id) then
   raise exception 'This item has been borrowed before, so its history stays. Set its condition to Retired instead.'; end if;
  v_row:=to_jsonb(q); v_module:=10;
  delete from app_private.documents where module=10 and record_id=p_id;
  delete from app_private.equipment_history where equipment_id=p_id and borrowing_id is null;
  delete from app_private.equipment where id=p_id;
 else raise exception 'This kind of record cannot be deleted.';
 end case;
 delete from app_private.notifications where record_id=p_id;
 perform app_private.note(v_module,p_id,'delete_'||p_kind,jsonb_build_object('reason',v_reason,'deleted',v_row));
 return jsonb_build_object('deleted',p_id);
exception when foreign_key_violation then
 raise exception 'Other records still point to this one, so it stays. Archive or retire it instead.';
end $$;
revoke all on function public.delete_record(text,uuid,text) from public,anon;
grant execute on function public.delete_record(text,uuid,text) to authenticated;

-- Roles have text ids, so they get their own door.
create function public.delete_role(p_role_id text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_row jsonb;
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 perform pg_advisory_xact_lock(14001);
 select to_jsonb(r) into v_row from app_private.roles r where r.id=p_role_id;
 if v_row is null then raise exception 'This role no longer exists. Refresh and try again.'; end if;
 if v_row->'default_permissions' is not null and jsonb_typeof(v_row->'default_permissions')<>'null' then
  raise exception 'Built-in roles stay. Turn off its desks or reset it instead.'; end if;
 if exists(select 1 from app_private.role_assignments where role_id=p_role_id) then
  raise exception 'Someone has held this role, so its history stays. Turn off its desks instead.'; end if;
 delete from app_private.roles where id=p_role_id;
 perform app_private.note(14,null,'delete_role',jsonb_build_object('deleted',v_row));
 return jsonb_build_object('deleted',p_role_id);
end $$;
revoke all on function public.delete_role(text) from public,anon;
grant execute on function public.delete_role(text) to authenticated;

-- SOURCE: 202611120032_send_training.sql
-- Send a whole training to the coach in one step: every personal or returned round the member owns among the
-- given ids goes to pending, all or none. The coach still validates each round.

create function public.submit_training_session(p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_id uuid; n integer:=0;
begin
 perform app_private.require_user();
 if coalesce(array_length(p_ids,1),0) not between 1 and 50 then raise exception 'Send 1 to 50 rounds at a time.'; end if;
 foreach v_id in array p_ids loop
  perform app_private.training_command('submit_training',jsonb_build_object('id',v_id));
  n:=n+1;
 end loop;
 return jsonb_build_object('sent',n);
end $$;
revoke all on function public.submit_training_session(uuid[]) from public,anon;
grant execute on function public.submit_training_session(uuid[]) to authenticated;

-- SOURCE: 202611120033_inventory.sql
-- Inventory the club sets up itself, like its Inventory spreadsheet.
--   Categories (Riser, Limbs, Sight…) each have a colour and their own fields: text, a choice from a list,
--   a number, or a range such as 68-66. A field can show on the inventory list or only on the item page.
--   A category can be borrowable or not, labelled piece by piece or just counted, and matched to the archer.
--   Places are where things are kept (each spreadsheet tab: Tall White Cabinet, Short White Cabinet (Left)…).
--   Items keep their existing columns, so borrowing keeps working; the new columns hold the item name,
--   category, place, quantity, owner and the category's field values. The server fills label, category,
--   specifications, handedness and draw weight from them.
-- Who: anyone with the Equipment desk, and the Administrator.

create table app_private.inventory_categories (
 id uuid primary key default gen_random_uuid(),
 name text not null unique check(length(trim(name)) between 1 and 60),
 color text not null default 'green' check(color in ('red','orange','yellow','green','blue','teal','purple','gray')),
 fields jsonb not null default '[]',
 borrowable boolean not null default true,
 labelled boolean not null default true,
 match_archer boolean not null default false,
 position integer not null default 0,
 version bigint not null default 1
);
create table app_private.inventory_places (
 id uuid primary key default gen_random_uuid(),
 name text not null unique check(length(trim(name)) between 1 and 80),
 position integer not null default 0,
 version bigint not null default 1
);
alter table app_private.equipment
 add column name text not null default '',
 add column category_id uuid references app_private.inventory_categories,
 add column place_id uuid references app_private.inventory_places,
 add column quantity integer not null default 1 check(quantity between 1 and 9999),
 add column owner text not null default 'Club property' check(length(trim(owner)) between 1 and 120),
 add column details jsonb not null default '{}';

alter table app_private.inventory_categories enable row level security;
alter table app_private.inventory_places enable row level security;
revoke all on app_private.inventory_categories, app_private.inventory_places from public,anon,authenticated;
grant select on app_private.inventory_categories, app_private.inventory_places to authenticated;
create policy inventory_categories_read on app_private.inventory_categories for select to authenticated using(app_private.member() or app_private.can('equipment') or app_private.can('administration'));
create policy inventory_places_read on app_private.inventory_places for select to authenticated using(app_private.member() or app_private.can('equipment') or app_private.can('administration'));

-- Starting set taken from the club's Inventory (26-27) sheet; every club can rename, recolour or delete them.
insert into app_private.inventory_categories(name,color,fields,borrowable,labelled,match_archer,position) values
 ('Riser','red','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"sight","label":"Sight","type":"text","options":[],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":true},{"key":"length","label":"Length (in.)","type":"number","options":[],"on_list":true}]',true,false,true,1),
 ('Limbs','orange','[{"key":"label","label":"Label","type":"text","options":[],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"length","label":"Length (in.)","type":"range","options":[],"on_list":true},{"key":"lbs","label":"Draw weight (lbs.)","type":"range","options":[],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":false}]',true,true,true,2),
 ('Sight','yellow','[{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":true},{"key":"pins","label":"Pins","type":"text","options":[],"on_list":true}]',true,false,false,3),
 ('Finger Tab','green','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"kind","label":"Leather/Plate","type":"choice","options":["Leather","Plate"],"on_list":true}]',true,false,true,4),
 ('Stringers','blue','[]',true,false,false,5),
 ('Quiver','teal','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true}]',true,false,false,6),
 ('Arm/Chest Guard','purple','[{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true}]',true,false,false,7),
 ('Arrows','gray','[{"key":"length","label":"Length (in.)","type":"number","options":[],"on_list":true},{"key":"spine","label":"Spine","type":"text","options":[],"on_list":true}]',true,false,false,8);
insert into app_private.inventory_places(name,position) values
 ('Arrows',1),('Tall White Cabinet',2),('Short White Cabinet (Left)',3),('Short White Cabinet (Right)',4),('Benel Cabinet (Left)',5);

create function app_private.require_inventory() returns uuid language plpgsql stable security definer set search_path='' as $$
declare u uuid:=app_private.require_user();
begin
 if not (app_private.can('equipment') or app_private.can('administration')) then
  raise exception 'Only the Equipment desk or the Administrator can change the inventory.' using errcode='42501'; end if;
 return u;
end $$;
revoke all on function app_private.require_inventory() from public,anon,authenticated;

-- Checks a category's field list and returns it cleaned: keys made from labels, unique labels, known types.
create function app_private.clean_fields(p jsonb) returns jsonb language plpgsql immutable set search_path='' as $$
declare f jsonb; v_out jsonb:='[]'; v_label text; v_key text; v_type text; v_opts jsonb; seen text[]:='{}'; keys text[]:='{}';
begin
 if jsonb_typeof(coalesce(p,'[]')) <> 'array' or jsonb_array_length(coalesce(p,'[]')) > 12 then raise exception 'A category can have up to 12 fields.'; end if;
 for f in select value from jsonb_array_elements(coalesce(p,'[]')) loop
  v_label:=trim(coalesce(f->>'label',''));
  if length(v_label) not between 1 and 40 then raise exception 'Each field needs a name of up to 40 characters.'; end if;
  if lower(v_label) = any(seen) then raise exception 'Two fields are both called %.',v_label; end if;
  seen:=seen||lower(v_label);
  v_type:=coalesce(f->>'type','text');
  if v_type not in ('text','choice','number','range') then raise exception 'Field types are text, choice, number or range.'; end if;
  v_opts:=coalesce(f->'options','[]');
  if v_type='choice' then
   -- Drop blanks and repeats, keeping the order the club typed (S, M, L).
   select coalesce(jsonb_agg(o order by first_n),'[]') into v_opts from (
    select trim(t.o) o,min(t.n) first_n from jsonb_array_elements_text(v_opts) with ordinality t(o,n) where trim(t.o)<>'' group by trim(t.o)) s;
   if jsonb_array_length(v_opts) not between 1 and 30 then raise exception '% needs 1 to 30 choices.',v_label; end if;
  else v_opts:='[]'; end if;
  -- Keep a field's key when it is renamed so items keep their values; new fields get one from the name.
  v_key:=coalesce(nullif(f->>'key',''),trim(both '_' from regexp_replace(lower(v_label),'[^a-z0-9]+','_','g')));
  if v_key='' then v_key:='field'; end if;
  while v_key = any(keys) loop v_key:=v_key||'_2'; end loop;
  keys:=keys||v_key;
  v_out:=v_out||jsonb_build_array(jsonb_build_object('key',v_key,'label',v_label,'type',v_type,'options',v_opts,'on_list',coalesce((f->>'on_list')::boolean,true)));
 end loop;
 return v_out;
end $$;
revoke all on function app_private.clean_fields(jsonb) from public,anon,authenticated;

create function public.save_inventory_category(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c app_private.inventory_categories; v_id uuid; v_fields jsonb:=app_private.clean_fields(p_data->'fields');
begin
 perform app_private.require_inventory();
 if nullif(p_data->>'id','') is null then
  insert into app_private.inventory_categories(name,color,fields,borrowable,labelled,match_archer,position)
  values(app_private.required(p_data,'name',60),coalesce(nullif(p_data->>'color',''),'green'),v_fields,
   coalesce((p_data->>'borrowable')::boolean,true),coalesce((p_data->>'labelled')::boolean,false),coalesce((p_data->>'match_archer')::boolean,false),
   (select coalesce(max(position),0)+1 from app_private.inventory_categories)) returning id into v_id;
 else
  select * into c from app_private.inventory_categories where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This category no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(c.version,p_data);
  update app_private.inventory_categories set name=app_private.required(p_data,'name',60),color=coalesce(nullif(p_data->>'color',''),color),fields=v_fields,
   borrowable=coalesce((p_data->>'borrowable')::boolean,borrowable),labelled=coalesce((p_data->>'labelled')::boolean,labelled),
   match_archer=coalesce((p_data->>'match_archer')::boolean,match_archer),version=version+1 where id=c.id;
  -- Items keep the category's name in their own category column for older screens.
  update app_private.equipment set category=app_private.required(p_data,'name',60) where category_id=c.id;
  v_id:=c.id;
 end if;
 perform app_private.note(10,v_id,'save_inventory_category',p_data);
 return jsonb_build_object('id',v_id);
exception when unique_violation then raise exception 'Another category already has this name.';
end $$;

create function public.save_inventory_place(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare pl app_private.inventory_places; v_id uuid;
begin
 perform app_private.require_inventory();
 if nullif(p_data->>'id','') is null then
  insert into app_private.inventory_places(name,position) values(app_private.required(p_data,'name',80),(select coalesce(max(position),0)+1 from app_private.inventory_places)) returning id into v_id;
 else
  select * into pl from app_private.inventory_places where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This place no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(pl.version,p_data);
  update app_private.inventory_places set name=app_private.required(p_data,'name',80),version=version+1 where id=pl.id; v_id:=pl.id;
 end if;
 perform app_private.note(10,v_id,'save_inventory_place',p_data);
 return jsonb_build_object('id',v_id);
exception when unique_violation then raise exception 'Another place already has this name.';
end $$;

-- Order categories or places as listed.
create function public.order_inventory(p_kind text,p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_inventory();
 if p_kind='category' then
  update app_private.inventory_categories c set position=o.n from unnest(p_ids) with ordinality o(id,n) where c.id=o.id;
 elsif p_kind='place' then
  update app_private.inventory_places pl set position=o.n from unnest(p_ids) with ordinality o(id,n) where pl.id=o.id;
 else raise exception 'Unknown list.'; end if;
 return jsonb_build_object('ordered',coalesce(array_length(p_ids,1),0));
end $$;

-- Only empty categories and places can go; otherwise the items would lose where they belong.
create function public.delete_inventory_group(p_kind text,p_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare n integer; v_row jsonb;
begin
 perform app_private.require_inventory();
 if p_kind='category' then
  select count(*) into n from app_private.equipment where category_id=p_id;
  if n>0 then raise exception 'Move or delete its % item(s) first.',n; end if;
  delete from app_private.inventory_categories c where c.id=p_id returning to_jsonb(c) into v_row;
 elsif p_kind='place' then
  select count(*) into n from app_private.equipment where place_id=p_id;
  if n>0 then raise exception 'Move its % item(s) somewhere else first.',n; end if;
  delete from app_private.inventory_places pl where pl.id=p_id returning to_jsonb(pl) into v_row;
 else raise exception 'Unknown list.'; end if;
 if v_row is null then raise exception 'It no longer exists. Refresh and try again.'; end if;
 perform app_private.note(10,p_id,'delete_inventory_'||p_kind,jsonb_build_object('deleted',v_row));
 return jsonb_build_object('deleted',p_id);
end $$;

-- Save one item from a category's fields. Returns its id.
create function app_private.save_inventory_item(u uuid,p_data jsonb) returns uuid
language plpgsql security definer set search_path='' as $$
declare c app_private.inventory_categories; e app_private.equipment; f jsonb; v_details jsonb:='{}'; v_val text; v_key text;
 v_name text:=app_private.required(p_data,'name',120); v_label text; v_base text; n integer:=1; v_hand text:='not_applicable';
 v_weight numeric; v_specs text:=''; v_id uuid; v_place uuid:=nullif(p_data->>'place_id','')::uuid;
 v_condition text:=coalesce(nullif(p_data->>'condition',''),'serviceable'); v_qty integer;
begin
 select * into c from app_private.inventory_categories where id=nullif(p_data->>'category_id','')::uuid;
 if not found then raise exception 'Pick a category for the item.'; end if;
 if v_place is not null and not exists(select 1 from app_private.inventory_places where id=v_place) then raise exception 'That place no longer exists.'; end if;
 if v_condition not in ('serviceable','maintenance','damaged','retired') then raise exception 'Unknown condition.'; end if;
 v_qty:=coalesce(nullif(p_data->>'quantity','')::integer,1);
 if v_qty not between 1 and 9999 then raise exception 'Quantity is 1 to 9999.'; end if;
 if c.labelled and v_qty<>1 then raise exception '% are labelled one by one, so each piece is its own item with quantity 1.',c.name; end if;
 for f in select value from jsonb_array_elements(c.fields) loop
  v_key:=f->>'key'; v_val:=trim(coalesce(p_data->'details'->>v_key,''));
  if v_val='' then continue; end if;
  if length(v_val)>80 then raise exception '% is too long.',f->>'label'; end if;
  if f->>'type'='choice' and not (f->'options' ? v_val) then raise exception '% must be one of: %.',f->>'label',(select string_agg(o,', ') from jsonb_array_elements_text(f->'options') o); end if;
  if f->>'type'='number' and v_val !~ '^\d+(\.\d+)?$' then raise exception '% must be a number.',f->>'label'; end if;
  if f->>'type'='range' and v_val !~ '^\d+(\.\d+)?(\s*-\s*\d+(\.\d+)?)?$' then raise exception '% must be a number or a range like 68-66.',f->>'label'; end if;
  v_details:=v_details||jsonb_build_object(v_key,v_val);
  v_specs:=v_specs||case when v_specs='' then '' else ' · ' end||(f->>'label')||': '||v_val;
  -- Hand and draw weight feed the existing matching for borrowers.
  if lower(f->>'label') in ('hand','handedness') then
   v_hand:=case when upper(v_val) in ('RH','RIGHT','R') then 'right' when upper(v_val) in ('LH','LEFT','L') then 'left' else v_hand end; end if;
  if lower(f->>'label') like '%lb%' or lower(f->>'label') like '%weight%' then
   select max(t.x[1]::numeric) into v_weight from regexp_matches(v_val,'(\d+(?:\.\d+)?)','g') as t(x); end if;
 end loop;
 -- The label is what borrowers and officers see; it must be unique, so repeats get #2, #3…
 v_base:=trim(v_name||coalesce(' '||nullif(v_details->>'label',''),'')||case when v_hand='right' then ' (RH)' when v_hand='left' then ' (LH)' else '' end);
 v_label:=v_base;
 while exists(select 1 from app_private.equipment where label=v_label and id is distinct from nullif(p_data->>'id','')::uuid) loop
  n:=n+1; v_label:=v_base||' #'||n; end loop;
 if nullif(p_data->>'id','') is null then
  insert into app_private.equipment(label,category,specifications,handedness,draw_weight,bow_setup,condition,acquired_on,notes,name,category_id,place_id,quantity,owner,details)
  values(v_label,c.name,coalesce(nullif(v_specs,''),c.name),v_hand,v_weight,'',v_condition,nullif(p_data->>'acquired_on','')::date,coalesce(p_data->>'notes',''),
   v_name,c.id,v_place,v_qty,coalesce(nullif(trim(p_data->>'owner'),''),'Club property'),v_details) returning id into v_id;
 else
  select * into e from app_private.equipment where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This item no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(e.version,p_data);
  update app_private.equipment set label=v_label,category=c.name,specifications=coalesce(nullif(v_specs,''),c.name),handedness=v_hand,draw_weight=v_weight,
   condition=v_condition,acquired_on=coalesce(nullif(p_data->>'acquired_on','')::date,acquired_on),notes=coalesce(p_data->>'notes',''),name=v_name,category_id=c.id,
   place_id=v_place,quantity=v_qty,owner=coalesce(nullif(trim(p_data->>'owner'),''),'Club property'),details=v_details,version=version+1 where id=e.id;
  v_id:=e.id;
 end if;
 insert into app_private.equipment_history(equipment_id,condition,notes,actor) values(v_id,v_condition,coalesce(p_data->>'notes',''),u);
 return v_id;
end $$;
revoke all on function app_private.save_inventory_item(uuid,jsonb) from public,anon,authenticated;

create function public.save_inventory_item(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_inventory(); v_id uuid;
begin
 v_id:=app_private.save_inventory_item(u,p_data);
 perform app_private.note(10,v_id,'save_inventory_item',p_data);
 return jsonb_build_object('id',v_id);
end $$;

-- One spreadsheet tab into one place. All rows save or none do.
create function public.import_inventory(p_place_id uuid,p_rows jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_inventory(); r jsonb; n integer:=0; i integer:=0;
begin
 if jsonb_typeof(p_rows) is distinct from 'array' or jsonb_array_length(p_rows) not between 1 and 500 then raise exception 'Import 1 to 500 items at a time.'; end if;
 if p_place_id is not null and not exists(select 1 from app_private.inventory_places where id=p_place_id) then raise exception 'That place no longer exists.'; end if;
 for r in select value from jsonb_array_elements(p_rows) loop
  i:=i+1;
  begin
   perform app_private.save_inventory_item(u,(r-'id')||jsonb_build_object('place_id',p_place_id));
  exception when others then raise exception 'Row %: %',i,sqlerrm;
  end;
  n:=n+1;
 end loop;
 perform app_private.note(10,p_place_id,'import_inventory',jsonb_build_object('items',n));
 return jsonb_build_object('imported',n);
end $$;

-- Categories that aren't borrowable can't be requested, whatever the app shows.
create function app_private.only_borrowable() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if exists(select 1 from app_private.equipment e join app_private.inventory_categories c on c.id=e.category_id where e.id=new.equipment_id and not c.borrowable) then
  raise exception 'This item is not lent out.'; end if;
 return new;
end $$;
create trigger borrow_only_borrowable before insert or update of equipment_id on app_private.borrowings for each row execute function app_private.only_borrowable();

do $$ declare f text; begin
 foreach f in array array['save_inventory_category(jsonb)','save_inventory_place(jsonb)','order_inventory(text,uuid[])','delete_inventory_group(text,uuid)','save_inventory_item(jsonb)','import_inventory(uuid,jsonb)'] loop
  execute format('revoke all on function public.%s from public,anon',f);
  execute format('grant execute on function public.%s to authenticated',f);
 end loop;
end $$;

-- The app reads categories and places with everything else.
create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611130034_announcement_reads.sql
-- Announcements remember who has read them. Read ones stay on Home and Activities, shown in gray,
-- instead of disappearing. Each person sees and writes only their own read marks.

create table app_private.announcement_reads (
 user_id uuid not null references app_private.profiles,
 announcement_id uuid not null references app_private.announcements on delete cascade,
 read_at timestamptz not null default now(),
 primary key(user_id,announcement_id)
);
alter table app_private.announcement_reads enable row level security;
revoke all on app_private.announcement_reads from public,anon,authenticated;
grant select on app_private.announcement_reads to authenticated;
create policy announcement_reads_own on app_private.announcement_reads for select to authenticated
 using(app_private.active_user() and user_id=auth.uid());

create function public.mark_announcements_read(p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); n integer;
begin
 if coalesce(array_length(p_ids,1),0) not between 1 and 200 then raise exception 'Mark 1 to 200 announcements at a time.'; end if;
 insert into app_private.announcement_reads(user_id,announcement_id)
  select u,a.id from app_private.announcements a where a.id=any(p_ids) on conflict do nothing;
 get diagnostics n=row_count;
 return jsonb_build_object('marked',n);
end $$;
revoke all on function public.mark_announcements_read(uuid[]) from public,anon;
grant execute on function public.mark_announcements_read(uuid[]) to authenticated;

create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p),
  'announcement_reads',(select coalesce(jsonb_agg(r),'[]') from app_private.announcement_reads r));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611130035_application_details.sql
-- The application asks for college, program, year level and an emergency contact.
-- They're saved with the application (the existing save/submit runs first, unchanged), are required to send it,
-- show to the membership officer, and copy into the member's profile when the application is approved.

alter table app_private.applications
 add column college text not null default '' check(length(college)<=60),
 add column program text not null default '' check(length(program)<=120),
 add column year_level text not null default '' check(length(year_level)<=40),
 add column emergency_contact_name text not null default '' check(length(emergency_contact_name)<=160),
 add column emergency_contact_phone text not null default '' check(length(emergency_contact_phone)<=40);
alter table app_private.profiles add column college text not null default '' check(length(college)<=60);

create function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='') then
  raise exception 'Add your college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;
revoke all on function public.save_application_details(text,jsonb) from public,anon;
grant execute on function public.save_application_details(text,jsonb) to authenticated;

-- Approval carries the answers into the profile (only where the profile is still empty).
create function app_private.application_to_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.status='approved' and old.status is distinct from 'approved' then
  update app_private.profiles set
   college=case when college='' then new.college else college end,
   program=case when program='' then new.program else program end,
   year_level=case when year_level='' then new.year_level else year_level end,
   emergency_contact_name=case when emergency_contact_name='' then new.emergency_contact_name else emergency_contact_name end,
   emergency_contact_phone=case when emergency_contact_phone='' then new.emergency_contact_phone else emergency_contact_phone end,
   updated_at=now()
  where id=new.user_id;
 end if;
 return new;
end $$;
create trigger application_to_profile after update of status on app_private.applications for each row execute function app_private.application_to_profile();

-- SOURCE: 202611130036_application_sex.sql
-- Sex on the application and profile (archery events are split into men's and women's divisions),
-- and Alumni as a year level (the app's list). Same save path as migration 035.

alter table app_private.applications add column sex text not null default '' check(sex in ('','Male','Female'));
alter table app_private.profiles add column sex text not null default '' check(sex in ('','Male','Female'));

create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

create or replace function app_private.application_to_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.status='approved' and old.status is distinct from 'approved' then
  update app_private.profiles set
   college=case when college='' then new.college else college end,
   sex=case when sex='' then new.sex else sex end,
   program=case when program='' then new.program else program end,
   year_level=case when year_level='' then new.year_level else year_level end,
   emergency_contact_name=case when emergency_contact_name='' then new.emergency_contact_name else emergency_contact_name end,
   emergency_contact_phone=case when emergency_contact_phone='' then new.emergency_contact_phone else emergency_contact_phone end,
   updated_at=now()
  where id=new.user_id;
 end if;
 return new;
end $$;

-- SOURCE: 202611130037_announcements_not_notices.sql
-- Announcements have their own unread/read (migration 034), so they no longer also land in each member's Notices.
-- An edited announcement turns unread again instead. Notices stay for personal alerts (application, payments, borrowing). Existing announcement notices are closed so the
-- Profile count only shows personal ones; the announcements themselves are untouched.

create function app_private.skip_announcement_notices() returns trigger language plpgsql set search_path='' as $$
begin
 if new.kind='announcement' then return null; end if;
 return new;
end $$;
create trigger skip_announcement_notices before insert on app_private.notifications for each row execute function app_private.skip_announcement_notices();

update app_private.notifications set completed_at=now() where kind='announcement' and completed_at is null;

-- An edited announcement (new title or message) shows as unread again, which is what its edit notice used to do.
create function app_private.unread_after_edit() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.title is distinct from old.title or new.message is distinct from old.message then
  delete from app_private.announcement_reads where announcement_id=new.id; end if;
 return new;
end $$;
create trigger unread_after_edit after update on app_private.announcements for each row execute function app_private.unread_after_edit();

-- SOURCE: 202611180038_contact_checks_and_officer_roles.sql
-- Findings from group testing (18 Nov 2026):
--  * Mobile numbers are Philippine mobiles (09XXXXXXXXX or +639XXXXXXXXX) everywhere: the applicant's own number,
--    the emergency contact, and a member editing their profile. The application and profile both require one.
--  * The DLSU ID number is exactly 8 digits and is required to send an application.
--  * New accounts that haven't applied have no name, so admins saw "Club record" for all of them. Profiles now
--    keep the sign-in email (visible only to the owner, membership officers and administrators, as before).
--  * An officer role can only go to an Active member. The Administrator role (administration permission) is
--    exempt, because the system administrator can be the adviser or a developer. Existing assignments are untouched.

create or replace function app_private.valid_mobile(p text) returns boolean language sql immutable set search_path='' as $$
 select coalesce(p,'')='' or regexp_replace(p,'[[:space:]().-]','','g') ~ '^(09[0-9]{9}|\+?639[0-9]{9})$'
$$;

-- Emails for naming accounts that haven't applied yet.
alter table app_private.profiles add column if not exists email text not null default '';
update app_private.profiles p set email=lower(u.email) from auth.users u where u.id=p.id and p.email='';

create or replace function public.initialize_account() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=auth.uid(); v_email text;
begin
 select lower(email) into v_email from auth.users where id=u and email_confirmed_at is not null;
 if u is null or v_email is null then raise exception 'Verify your email before continuing.' using errcode='42501'; end if;
 insert into app_private.profiles(id,email) values(u,v_email) on conflict(id) do update set email=excluded.email where app_private.profiles.email<>excluded.email;
 perform app_private.require_user(); perform app_private.note(14,u,'session_access');
 return jsonb_build_object('id',u);
end $$;

create or replace function public.complete_account_creation(p_request uuid,p_user uuid) returns void language plpgsql security definer set search_path='' as $$
declare r app_private.account_requests;
begin
 select * into r from app_private.account_requests where id=p_request and completed_at is null for update;
 if not found or not exists(select 1 from auth.users where id=p_user and lower(email)=r.email) then raise exception 'Account creation request does not match.'; end if;
 insert into app_private.profiles(id,full_name,email) values(p_user,r.full_name,r.email) on conflict do nothing;
 update app_private.account_requests set user_id=p_user,completed_at=now() where id=r.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail) values(r.actor,14,p_user,'account_invited',jsonb_build_object('request_id',r.id));
end $$;

-- Application: same as migration 036, plus the mobile, emergency contact and ID number checks.
create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
 v_phone text:=trim(coalesce(p_data->>'phone','')); v_id text:=trim(coalesce(p_data->>'student_number',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_id<>'' and v_id !~ '^[0-9]{8}$' then raise exception 'Your DLSU ID number is 8 digits.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_phone='' or v_id='') then raise exception 'Add your mobile number and DLSU ID number before sending.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

-- Profile: same as live, but the mobile number is required and both numbers are Philippine mobiles.
create or replace function app_private.save_member_profile(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_phone text:=trim(coalesce(p_data->>'phone','')); v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone',''));
 v_name text; v_current text;
begin
 v_name:=app_private.required(p_data,'full_name',160);
 select full_name into v_current from app_private.profiles where id=u;
 if trim(v_name)<>trim(v_current) and exists(select 1 from app_private.memberships where user_id=u) then
  raise exception 'Your name was verified when your membership was approved. Request a name correction instead.'; end if;
 if v_phone='' then raise exception 'Enter your mobile number.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if (v_ec_phone='') <> (trim(coalesce(p_data->>'emergency_contact_name',''))='') then raise exception 'Provide both the emergency contact name and number.'; end if;
 update app_private.profiles set full_name=v_name, phone=v_phone, emergency_contact_name=trim(coalesce(p_data->>'emergency_contact_name','')),
  emergency_contact_phone=v_ec_phone, program=left(trim(coalesce(p_data->>'program','')),120), year_level=left(trim(coalesce(p_data->>'year_level','')),40),
  dominant_hand=coalesce(p_data->>'dominant_hand',''), bow_type=coalesce(p_data->>'bow_type',''), draw_weight=nullif(p_data->>'draw_weight','')::numeric,
  arrow_length=nullif(p_data->>'arrow_length','')::numeric, experience_level=coalesce(p_data->>'experience_level',''), bio=trim(coalesce(p_data->>'bio','')), updated_at=now()
 where id=u;
 perform app_private.note(1,u,'save_profile',p_data);
 return jsonb_build_object('id',u);
end $$;

-- Roles: same as migration 016, plus the membership check on assign_role.
create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
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
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  if not exists(select 1 from app_private.roles r where r.id=p_data->>'role_id' and 'administration'=any(r.permissions))
     and not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>'user_id')::uuid and m.status='Active') then
   raise exception 'Officer roles can only go to Active members. This account has no Active membership.' using errcode='42501';
  end if;
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

-- SOURCE: 202611190039_member_standing.sql
-- Testing round (19 Nov): the role picker showed every account as "not a member". Administrators can't read
-- memberships (only the owner and membership officers can, migration 001), so the app found no membership row.
-- Administrators and membership officers now get each account's standing (user_id + status only, nothing else
-- from the membership), which is what the role picker needs. Everyone else gets an empty list.

create or replace function app_private.member_standing() returns jsonb language sql stable security definer set search_path='' as $$
 select case when app_private.can('administration') or app_private.can('membership')
  then (select coalesce(jsonb_agg(jsonb_build_object('user_id',m.user_id,'status',m.status)),'[]') from app_private.memberships m)
  else '[]'::jsonb end
$$;
revoke all on function app_private.member_standing() from public,anon;
grant execute on function app_private.member_standing() to authenticated;

create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p),
  'announcement_reads',(select coalesce(jsonb_agg(r),'[]') from app_private.announcement_reads r),
  'member_standing',app_private.member_standing());
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611190040_edit_role_terms.sql
-- Administration: edit a role's title or end date from the officer's card (19 Nov). Same as migration 038's
-- admin_command plus the update_role_term action. Extending a term past today still needs an Active membership
-- for officer roles; ending a term early is always allowed, except for the last active Administrator.

create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; ra app_private.role_assignments; v_ends timestamptz; begin
 perform app_private.require_permission('administration');
 -- Serialize access changes to protect against two concurrent last-admin retirements.
 perform pg_advisory_xact_lock(14001);
 case p_action
 when 'configure_club' then
  update app_private.settings set club_name=app_private.required(p_data,'club_name',160),
   application_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'application_requirements','[]'))),
   renewal_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'renewal_requirements','[]'))),
   membership_categories=array(select jsonb_array_elements_text(coalesce(p_data->'membership_categories','["Regular member"]'))),
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  if not exists(select 1 from app_private.roles r where r.id=p_data->>'role_id' and 'administration'=any(r.permissions))
     and not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>'user_id')::uuid and m.status='Active') then
   raise exception 'Officer roles can only go to Active members. This account has no Active membership.' using errcode='42501';
  end if;
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 -- Change an existing appointment's title or end date (extend or shorten a term) without making a new one.
 -- The previous values go into the audit log with the reason, so turnover history stays complete.
 when 'update_role_term' then
  select * into ra from app_private.role_assignments where id=(p_data->>'id')::uuid and retired_at is null for update;
  if not found then raise exception 'That role is no longer active. Assign it again instead.'; end if;
  v_ends:=(app_private.required(p_data,'ends_at'))::timestamptz;
  if v_ends<=ra.starts_at then raise exception 'The term must end after it starts (%).', to_char(ra.starts_at at time zone 'Asia/Manila','Mon DD, YYYY'); end if;
  if v_ends>now() and not exists(select 1 from app_private.roles r where r.id=ra.role_id and 'administration'=any(r.permissions))
     and not exists(select 1 from app_private.memberships m where m.user_id=ra.user_id and m.status='Active') then
   raise exception 'Officer roles can only go to Active members. This account has no Active membership.' using errcode='42501';
  end if;
  update app_private.role_assignments set ends_at=v_ends,position=app_private.required(p_data,'position',160),
   reason=reason||E'\nChanged: '||app_private.required(p_data,'reason') where id=ra.id returning id into v_id;
  p_data:=p_data||jsonb_build_object('previous',jsonb_build_object('ends_at',ra.ends_at,'position',ra.position));
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

-- Called directly (like save_application_details) so the long club_command dispatcher stays untouched.
create or replace function public.update_role_term(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
begin return app_private.admin_command('update_role_term',p_data); end $$;
revoke all on function public.update_role_term(jsonb) from public,anon;
grant execute on function public.update_role_term(jsonb) to authenticated;

-- SOURCE: 202611190041_payment_method.sql
-- Payments say how they were paid (GCash, Maya or bank), and the reference number is checked to match (19 Nov).
--  * GCash: the receipt's Ref No. is 13 digits.
--  * Maya and bank transfers: 6 to 30 letters, numbers or dashes (formats differ by bank).
-- The check is a trigger, so it also covers older app versions that save through club_command directly.
-- It only runs when the reference or method changes, so verifying or returning an older payment is never blocked.

alter table app_private.financial_records add column if not exists payment_method text not null default ''
 check (payment_method in ('','gcash','maya','bank'));

create or replace function app_private.check_payment_reference() returns trigger language plpgsql set search_path='' as $$
begin
 if new.kind<>'payment' then return new; end if;
 if tg_op='UPDATE' and new.reference is not distinct from old.reference and new.payment_method is not distinct from old.payment_method then return new; end if;
 new.reference:=upper(regexp_replace(coalesce(new.reference,''),'[[:space:]]','','g'));
 if new.reference='' then return new; end if;
 if new.payment_method='gcash' and new.reference !~ '^[0-9]{13}$' then
  raise exception 'A GCash reference number is the 13-digit Ref No. on your receipt.'; end if;
 if new.reference !~ '^[A-Z0-9-]{6,30}$' then
  raise exception 'Use the reference number exactly as on the receipt: 6 to 30 letters or numbers.'; end if;
 return new;
end $$;
drop trigger if exists check_payment_reference on app_private.financial_records;
create trigger check_payment_reference before insert or update on app_private.financial_records
 for each row execute function app_private.check_payment_reference();

-- Saves a payment with its method: the usual save, then the method on the same record (the trigger checks both).
create or replace function public.save_payment(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_method text:=coalesce(p_data->>'payment_method',''); result jsonb; v_id uuid;
begin
 if v_method not in ('gcash','maya','bank') then raise exception 'Choose how you paid: GCash, Maya or bank transfer.'; end if;
 if v_method='gcash' and upper(regexp_replace(coalesce(p_data->>'reference',''),'[[:space:]]','','g')) !~ '^[0-9]{13}$' then
  raise exception 'A GCash reference number is the 13-digit Ref No. on your receipt.'; end if;
 result:=public.club_command('save_financial_request',p_data||jsonb_build_object('kind','payment'));
 v_id:=coalesce(nullif(result->>'id','')::uuid,nullif(p_data->>'id','')::uuid);
 update app_private.financial_records set payment_method=v_method where id=v_id and user_id=u;
 return result;
end $$;
revoke all on function public.save_payment(jsonb) from public,anon;
grant execute on function public.save_payment(jsonb) to authenticated;

-- SOURCE: 202611190042_roles_need_membership.sql
-- Every role, Administrator included, needs an Active membership (testing round, 19 Nov: "no exceptions").
-- Accounts with no membership or a pending fee can't be assigned a role or have a term extended.
-- Shortening a term and retiring a role are always allowed, so a mistaken role can still be removed.
-- Same as migration 040's admin_command otherwise.

create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; ra app_private.role_assignments; v_ends timestamptz; begin
 perform app_private.require_permission('administration');
 -- Serialize access changes to protect against two concurrent last-admin retirements.
 perform pg_advisory_xact_lock(14001);
 case p_action
 when 'configure_club' then
  update app_private.settings set club_name=app_private.required(p_data,'club_name',160),
   application_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'application_requirements','[]'))),
   renewal_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'renewal_requirements','[]'))),
   membership_categories=array(select jsonb_array_elements_text(coalesce(p_data->'membership_categories','["Regular member"]'))),
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  if not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>'user_id')::uuid and m.status='Active') then
   raise exception 'Roles, including Administrator, can only go to Active members. This account has no Active membership.';
  end if;
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 -- Change an existing appointment's title or end date (extend or shorten a term) without making a new one.
 -- The previous values go into the audit log with the reason, so turnover history stays complete.
 when 'update_role_term' then
  select * into ra from app_private.role_assignments where id=(p_data->>'id')::uuid and retired_at is null for update;
  if not found then raise exception 'That role is no longer active. Assign it again instead.'; end if;
  v_ends:=(app_private.required(p_data,'ends_at'))::timestamptz;
  if v_ends<=ra.starts_at then raise exception 'The term must end after it starts (%).', to_char(ra.starts_at at time zone 'Asia/Manila','Mon DD, YYYY'); end if;
  if v_ends>ra.ends_at and not exists(select 1 from app_private.memberships m where m.user_id=ra.user_id and m.status='Active') then
   raise exception 'Only Active members can have a term extended. This account has no Active membership.';
  end if;
  update app_private.role_assignments set ends_at=v_ends,position=app_private.required(p_data,'position',160),
   reason=reason||E'\nChanged: '||app_private.required(p_data,'reason') where id=ra.id returning id into v_id;
  p_data:=p_data||jsonb_build_object('previous',jsonb_build_object('ends_at',ra.ends_at,'position',ra.position));
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

-- SOURCE: 202611200043_fee_notice_on_submit.sql
-- The "pay your fee" notice closes as soon as the payment is sent for checking (20 Nov testing).
-- Before, it stayed open until the Treasurer verified, so members who had already paid kept seeing "pay the fee".
--  * Sent for checking: the open fee notice for the same purpose is marked done.
--  * Sent back, incomplete or not accepted: the fee is still unpaid, so the notice comes back (alongside the
--    "Your payment was sent back" notice the payer already gets), unless another payment for it is still being checked.
--  * Verified: unchanged; linking the payment to the fee completes the notice as before.
-- A separate trigger, so sync_workflow_notices stays as it is.

create or replace function app_private.fee_notice_follows_payment() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.kind<>'payment' or new.status is not distinct from (case when tg_op='UPDATE' then old.status end) then return new; end if;
 if new.status='pending_verification' then
  update app_private.notifications n set completed_at=now()
   from app_private.fee_assessments f
   where n.kind='fee' and n.record_id=f.id and n.completed_at is null
     and f.user_id=new.user_id and f.purpose=new.purpose and f.payment_id is null;
 elsif new.status in ('returned_for_correction','incomplete','rejected') then
  update app_private.notifications n set completed_at=null, acknowledged_at=null, notified_at=now()
   from app_private.fee_assessments f
   where n.kind='fee' and n.record_id=f.id and n.completed_at is not null
     and f.user_id=new.user_id and f.purpose=new.purpose and f.payment_id is null
     and not exists(select 1 from app_private.financial_records r where r.user_id=new.user_id and r.kind='payment'
                    and r.purpose=new.purpose and r.id<>new.id and r.status in ('pending_verification','verified'));
 end if;
 return new;
end $$;
revoke all on function app_private.fee_notice_follows_payment() from public,anon,authenticated;
drop trigger if exists fee_notice_follows_payment on app_private.financial_records;
create trigger fee_notice_follows_payment after insert or update on app_private.financial_records
 for each row execute function app_private.fee_notice_follows_payment();

-- Payments already waiting to be checked: close their fee notices now.
update app_private.notifications n set completed_at=now()
 from app_private.fee_assessments f
 where n.kind='fee' and n.record_id=f.id and n.completed_at is null and f.payment_id is null
   and exists(select 1 from app_private.financial_records r where r.user_id=f.user_id and r.kind='payment'
              and r.purpose=f.purpose and r.status='pending_verification');

-- SOURCE: 202611200044_partner_invite_codes.sql
-- Partner representatives join with an invite code (20 Nov testing: a UP rep was sent into the DLSU membership
-- application and asked for a DLSU ID number).
--  * The Interclub officer creates a single-use code for one event and one partner club and sends it through that
--    club's official channel. It expires when the event's partner sign-ups close.
--  * Whoever signs up and enters the code becomes that club's representative for that event only. Holding the code is
--    what grants access, not the email address used to sign up (email confirmation is off, so emails prove nothing).
--  * The rep gives their name, university and a Philippine mobile number; no DLSU ID and no membership application.
--  * Wrong codes are counted: 8 wrong tries in an hour locks claiming for that account for the rest of the hour.
--  * One person, one club: an account with a DLSU membership or a submitted application can't claim a partner code.

alter table app_private.profiles add column if not exists university text not null default '';
alter table app_private.profiles add column if not exists account_type text not null default 'member'
 check (account_type in ('member','partner'));

create table if not exists app_private.partner_invites (
 id uuid primary key default gen_random_uuid(),
 code text not null unique,
 event_id uuid not null references app_private.interclub_events,
 partner_id uuid not null references app_private.partner_clubs,
 created_by uuid not null references app_private.profiles,
 created_at timestamptz not null default now(),
 expires_at timestamptz not null,
 claimed_by uuid references app_private.profiles,
 claimed_at timestamptz,
 revoked_at timestamptz
);
create table if not exists app_private.partner_invite_attempts (
 user_id uuid not null references app_private.profiles, at timestamptz not null default now()
);
alter table app_private.partner_invites enable row level security;
alter table app_private.partner_invite_attempts enable row level security;
grant select on app_private.partner_invites to authenticated;
drop policy if exists partner_invites_read on app_private.partner_invites;
create policy partner_invites_read on app_private.partner_invites for select to authenticated
 using (app_private.event_staff(event_id) or claimed_by=auth.uid());

-- Interclub officer: a new code for one partner club and one shared event.
create or replace function public.create_partner_invite(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); i app_private.interclub_events; v_code text; v_id uuid; v_partner uuid;
begin
 select * into i from app_private.interclub_events where event_id=(p_data->>'event_id')::uuid;
 if not found then raise exception 'That shared event no longer exists.'; end if;
 if not app_private.event_staff(i.event_id) then raise exception 'Only the event''s interclub staff can invite partner clubs.' using errcode='42501'; end if;
 if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Partner sign-ups for this event have closed.'; end if;
 v_partner:=(p_data->>'partner_id')::uuid;
 if not exists(select 1 from app_private.partner_clubs where id=v_partner) then raise exception 'Choose a partner club.'; end if;
 loop
  v_code:=upper(substr(md5(gen_random_uuid()::text),1,4)||'-'||substr(md5(gen_random_uuid()::text),1,4));
  exit when not exists(select 1 from app_private.partner_invites where code=v_code);
 end loop;
 insert into app_private.partner_invites(code,event_id,partner_id,created_by,expires_at)
  values(v_code,i.event_id,v_partner,u,i.registration_closes) returning id into v_id;
 perform app_private.note(15,v_id,'create_partner_invite',jsonb_build_object('event_id',i.event_id,'partner_id',v_partner));
 return jsonb_build_object('id',v_id,'code',v_code,'expires_at',i.registration_closes);
end $$;

-- Interclub officer: cancel an unused code (for example, sent to the wrong person).
create or replace function public.revoke_partner_invite(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.partner_invites;
begin
 select * into v from app_private.partner_invites where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'That invite no longer exists.'; end if;
 if not app_private.event_staff(v.event_id) then raise exception 'Only the event''s interclub staff can cancel invites.' using errcode='42501'; end if;
 if v.claimed_by is not null then raise exception 'This code was already used. Remove the representative from the event instead.'; end if;
 update app_private.partner_invites set revoked_at=now() where id=v.id;
 perform app_private.note(15,v.id,'revoke_partner_invite','{}');
 return jsonb_build_object('id',v.id);
end $$;

-- A new account claims a code. Returns {ok:false,message} for a wrong code (so the try is still counted), or
-- {ok:true,event_id} once the account is that club's representative.
create or replace function public.claim_partner_invite(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.partner_invites; v_code text; v_phone text; v_name text; v_school text;
begin
 if (select count(*) from app_private.partner_invite_attempts where user_id=u and at>now()-interval '1 hour')>=8 then
  raise exception 'Too many wrong codes. Try again in an hour, or ask the host club to check the code.'; end if;
 if exists(select 1 from app_private.memberships where user_id=u)
    or exists(select 1 from app_private.applications where user_id=u and status<>'draft') then
  raise exception 'This account belongs to a DLSU Archery Club member or applicant. Partner representatives need their own account.'; end if;
 v_name:=app_private.required(p_data,'full_name',160);
 v_school:=app_private.required(p_data,'university',160);
 v_phone:=trim(coalesce(p_data->>'phone',''));
 if v_phone='' or not app_private.valid_mobile(v_phone) then
  raise exception 'Enter a Philippine mobile number (09XXXXXXXXX or +639XXXXXXXXX).'; end if;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g'));
 v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 select * into v from app_private.partner_invites where code=v_code for update;
 if not found or v.revoked_at is not null or v.expires_at<now() or (v.claimed_by is not null and v.claimed_by<>u) then
  insert into app_private.partner_invite_attempts(user_id) values(u);
  return jsonb_build_object('ok',false,'message','That invite code isn''t valid or has expired. Check it with the host club.');
 end if;
 update app_private.profiles set full_name=v_name, university=v_school, phone=v_phone, account_type='partner', updated_at=now() where id=u;
 insert into app_private.event_access(event_id,user_id,partner_id,role,invited_by) values(v.event_id,u,v.partner_id,'partner',v.created_by)
  on conflict(event_id,user_id) do update set partner_id=excluded.partner_id, role='partner', revoked_at=null;
 update app_private.partner_invites set claimed_by=u, claimed_at=coalesce(claimed_at,now()) where id=v.id;
 perform app_private.notify(v.created_by,15,v.event_id,'invitation',
  v_name||' joined as the representative of '||(select name from app_private.partner_clubs where id=v.partner_id));
 perform app_private.note(15,v.id,'claim_partner_invite',jsonb_build_object('event_id',v.event_id,'partner_id',v.partner_id));
 return jsonb_build_object('ok',true,'event_id',v.event_id);
end $$;

revoke all on function public.create_partner_invite(jsonb),public.revoke_partner_invite(jsonb),public.claim_partner_invite(jsonb) from public,anon;
grant execute on function public.create_partner_invite(jsonb),public.revoke_partner_invite(jsonb),public.claim_partner_invite(jsonb) to authenticated;

-- The codes reach the app with the rest of the workspace (only the rows the reader may see, by the policy above).
create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p),
  'announcement_reads',(select coalesce(jsonb_agg(r),'[]') from app_private.announcement_reads r),
  'member_standing',app_private.member_standing(),
  'partner_invites',(select coalesce(jsonb_agg(v order by v.created_at desc),'[]') from app_private.partner_invites v));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

-- SOURCE: 202611210045_interclub_partner_clarity.sql
-- Interclub run, 21 Nov testing.
--  * A concern's reply reached nobody: the partner rep's concern simply vanished once the officer resolved it.
--    Resolving now sends the rep a notice with the reply (the app also shows answered concerns on the event page).
--  * "Responsibility" was required when giving an archer a target, which makes no sense for an archer. It is now
--    optional. Only that one expression in interclub_command changes: the live definition is read, checked to contain
--    exactly the expected text, and re-created with the replacement, so nothing else in the function is retyped.
--  * The invite code list said "Used by Club member": officers can't read a partner rep's profile. The invite now
--    keeps the name the rep gave when claiming it.

-- 1. The rep hears back.
create or replace function app_private.concern_reply_notice() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.status='resolved' and old.status is distinct from 'resolved' then
  perform app_private.notify(new.submitted_by,15,new.event_id,'interclub',
   'DLSU replied to your concern about '||coalesce((select shared_title from app_private.interclub_events where event_id=new.event_id),'the event')
   ||': '||new.resolution);
 end if;
 return new;
end $$;
revoke all on function app_private.concern_reply_notice() from public,anon,authenticated;
drop trigger if exists concern_reply_notice on app_private.interclub_concerns;
create trigger concern_reply_notice after update on app_private.interclub_concerns
 for each row execute function app_private.concern_reply_notice();

-- 2. Responsibility optional when assigning a target.
do $do$
declare d text; v_old text:='app_private.required(p_data,''responsibility'')'; v_new text:='coalesce(p_data->>''responsibility'','''')';
begin
 d:=pg_get_functiondef('app_private.interclub_command(text,jsonb)'::regprocedure);
 if (length(d)-length(replace(d,v_old,'')))/length(v_old)<>1 then
  raise exception 'interclub_command is not the expected version (responsibility check not found exactly once); nothing changed.';
 end if;
 execute replace(d,v_old,v_new);
end $do$;

-- 3. The rep's name on the invite they used.
alter table app_private.partner_invites add column if not exists claimed_name text not null default '';
create or replace function app_private.invite_claimed_name() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.claimed_by is not null and (tg_op='INSERT' or old.claimed_by is distinct from new.claimed_by) then
  new.claimed_name:=coalesce((select full_name from app_private.profiles where id=new.claimed_by),'');
 end if;
 return new;
end $$;
revoke all on function app_private.invite_claimed_name() from public,anon,authenticated;
drop trigger if exists invite_claimed_name on app_private.partner_invites;
create trigger invite_claimed_name before insert or update on app_private.partner_invites
 for each row execute function app_private.invite_claimed_name();
update app_private.partner_invites v set claimed_name=p.full_name from app_private.profiles p
 where p.id=v.claimed_by and v.claimed_name='';

-- SOURCE: 202611210046_result_recorded_by.sql
-- Interclub results, 21 Nov testing: the officer who recorded a result was offered "Verify", tapped it, and got a vague
-- "no permission" (the server rightly refuses: someone else must verify). The app couldn't tell who recorded a result,
-- because that was only in the audit log. Each result now keeps who recorded it, so the app can say
-- "Another officer verifies this" instead of offering the button. The server's check is unchanged.

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

-- SOURCE: 202611210047_developer_accounts.sql
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

-- SOURCE: 202611230048_fund_totals.sql
-- Club funds: the President and the Treasurer see the same balance (22 Nov testing).
-- The President may read only expenses and reimbursements (policy financial_read, migration 005): members' payments,
-- with their references and receipts, stay with the Treasurer. The app added up the rows it could read, so the
-- President's Club funds showed P0 and an empty ledger while the Treasurer's showed P11,200.
-- Fix: the server sends both offices the verified totals (no names, references or receipts); nobody else gets them.

create or replace function app_private.fund_totals() returns jsonb language sql stable security definer set search_path='' as $$
 select case when app_private.can('finance') or app_private.can('president') then jsonb_build_object(
   'received',coalesce(sum(amount) filter (where kind='payment'),0),
   'spent',coalesce(sum(amount) filter (where kind in ('expense','reimbursement')),0),
   'payments',count(*) filter (where kind='payment'))
 end
 from app_private.financial_records where status='verified' $$;
revoke all on function app_private.fund_totals() from public,anon;
grant execute on function app_private.fund_totals() to authenticated;

-- Add the totals to the live workspace without restating it: replace its last entry, only if it occurs exactly once.
do $$
declare
 def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:=$s$'partner_invites',(select coalesce(jsonb_agg(v order by v.created_at desc),'[]') from app_private.partner_invites v));$s$;
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then
  raise exception 'club_workspace() is not the version this migration expects; update the snippet.';
 end if;
 execute replace(def,snippet,$s$'partner_invites',(select coalesce(jsonb_agg(v order by v.created_at desc),'[]') from app_private.partner_invites v),
  'fund_totals',app_private.fund_totals());$s$);
end $$;

-- SOURCE: 202611230049_application_school.sql
-- Students from other schools can apply for membership (23 Nov). Each application now records the applicant's school;
-- existing applications are DLSU (the default). DLSU keeps its 8-digit ID number; other schools' IDs vary, so those
-- accept 4 to 20 letters, digits or dashes. Everything else is the same as migration 038.

alter table app_private.applications add column school text not null default 'De La Salle University'
 check(length(school) between 1 and 120);

create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
 v_phone text:=trim(coalesce(p_data->>'phone','')); v_id text:=trim(coalesce(p_data->>'student_number',''));
 v_school text:=coalesce(nullif(trim(coalesce(p_data->>'school','')),''),'De La Salle University');
 v_dlsu boolean;
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_school)>120 then raise exception 'Keep the school name under 120 characters.'; end if;
 v_dlsu:=lower(v_school) in ('de la salle university','dlsu','dlsu manila');
 if v_dlsu then v_school:='De La Salle University'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_dlsu and v_id<>'' and v_id !~ '^[0-9]{8}$' then raise exception 'Your DLSU ID number is 8 digits.'; end if;
 if not v_dlsu and v_id<>'' and v_id !~ '^[A-Za-z0-9-]{4,20}$' then raise exception 'Your student ID number is 4 to 20 letters, numbers or dashes.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_phone='' or v_id='') then
  raise exception 'Add your mobile number and % before sending.',case when v_dlsu then 'DLSU ID number' else 'student ID number' end; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,school=v_school,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

-- SOURCE: 202611230050_clubs.sql
-- Many clubs, one app (23 Nov). DLSU Archery Club becomes the first club; a platform administrator creates more
-- (e.g. UP, Ateneo), each with its own settings, officers, members, activities, equipment, money and elections.
--
-- How the clubs are kept apart, without rewriting every function:
--  * Every table except the role catalogue gets club_id. Existing rows are DLSU's; new rows default to the
--    caller's club (app_private.current_club()).
--  * One restrictive policy per table: a row is visible and writable only inside its own club.
--  * The server functions used to belong to postgres, which skips row policies. They now belong to club_api, a
--    role that does not, so every function sees and changes only the caller's club, including ones not edited here.
--  * Kept with postgres: the small "who am I" checks used inside policies (they read only the caller's own rows),
--    functions that touch file storage, and the few that must cross clubs on purpose (joining a club, creating
--    one, claiming a partner invite, and the two called by server jobs with no signed-in user); those set the club
--    explicitly.
-- Each club has its own settings row, so a new club starts from blank rules rather than DLSU's.
-- Future migrations: give new functions to club_api too (see the owner loop at the end), or they skip the policies.

-- 1. Clubs ----------------------------------------------------------------------------------------------------
create table app_private.clubs (
 id uuid primary key default gen_random_uuid(),
 name text not null check(length(trim(name)) between 2 and 160),
 short_name text not null default '' check(length(short_name)<=40),
 school text not null default '' check(length(school)<=160),
 -- The app's colour for this club: DLSU green, UP red, Ateneo blue. Workflows are the same for every club.
 theme text not null default 'green' check(theme in ('green','red','blue')),
 created_at timestamptz not null default now(),
 created_by uuid
);
create unique index clubs_name_key on app_private.clubs(lower(name));
alter table app_private.clubs enable row level security;
create policy clubs_read on app_private.clubs for select to authenticated using (true);
grant select on app_private.clubs to authenticated;
insert into app_private.clubs(id,name,short_name,school)
 values('00000000-0000-4000-8000-00000000d150',coalesce(nullif((select club_name from app_private.settings limit 1),'Archery Club'),'DLSU Archery Club'),'DLSU','De La Salle University');

-- Accounts made while only one club exists join it, so a one-club setup needs no extra step; once there are more,
-- a new account picks its club (public.join_club).
create function app_private.default_club() returns uuid language sql stable security definer set search_path='' as $$
 select case when (select count(*) from app_private.clubs)=1 then (select id from app_private.clubs) end $$;

alter table app_private.profiles add column club_id uuid references app_private.clubs default '00000000-0000-4000-8000-00000000d150';
alter table app_private.profiles alter column club_id set default app_private.default_club();
alter table app_private.profiles add column platform_admin boolean not null default false;
update app_private.profiles set platform_admin=true where developer;

create function app_private.current_club() returns uuid language sql stable security definer set search_path='' as $$
 select club_id from app_private.profiles where id=auth.uid() $$;
create function app_private.platform_admin() returns boolean language sql stable security definer set search_path='' as $$
 select coalesce((select platform_admin from app_private.profiles where id=auth.uid()),false) $$;
grant execute on function app_private.current_club(),app_private.platform_admin(),app_private.default_club() to authenticated;

-- 2. club_id on every table -------------------------------------------------------------------------------------
do $$ declare t text; begin
 for t in select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
          where n.nspname='app_private' and c.relkind='r' and c.relname not in ('clubs','roles','profiles') loop
  execute format('alter table app_private.%I add column club_id uuid references app_private.clubs default %L',t,'00000000-0000-4000-8000-00000000d150');
  execute format('alter table app_private.%I alter column club_id set default app_private.current_club()',t);
  execute format('create index %I on app_private.%I(club_id)',t||'_club_idx',t);
 end loop;
end $$;
create index profiles_club_idx on app_private.profiles(club_id);

-- Settings: one row per club instead of one row.
alter table app_private.settings drop constraint settings_pkey;
alter table app_private.settings alter column club_id set not null;
alter table app_private.settings add primary key (club_id);

-- Names a club chooses are unique within that club, not across all clubs.
alter table app_private.academic_terms drop constraint academic_terms_name_key, add unique(club_id,name);
alter table app_private.academic_terms drop constraint academic_terms_sequence_key, add unique(club_id,sequence);
alter table app_private.equipment drop constraint equipment_label_key, add unique(club_id,label);
alter table app_private.inventory_categories drop constraint inventory_categories_name_key, add unique(club_id,name);
alter table app_private.inventory_places drop constraint inventory_places_name_key, add unique(club_id,name);
alter table app_private.partner_clubs drop constraint partner_clubs_name_key, add unique(club_id,name);

-- The role catalogue (Treasurer, President...) is shared; who holds a role is per club. With several clubs, only
-- the platform administrator may change the catalogue, so one club can't rename another club's roles.
create function app_private.guard_role_catalogue() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if (select count(*) from app_private.clubs)>1 and not app_private.platform_admin() then
  raise exception 'Role definitions are shared by every club. Ask the platform administrator to change them.' using errcode='42501'; end if;
 return coalesce(new,old);
end $$;
create trigger guard_role_catalogue before insert or update or delete on app_private.roles for each row execute function app_private.guard_role_catalogue();

-- 3. The function owner that obeys row policies --------------------------------------------------------------
do $$ begin
 if not exists(select 1 from pg_roles where rolname='club_api') then create role club_api nologin noinherit; end if;
 execute format('grant club_api to %I',current_user);
end $$;
grant usage, create on schema app_private, public to club_api;
grant usage on schema auth, storage to club_api;
grant select on auth.users to club_api;
grant execute on function auth.uid() to club_api;
grant select,insert,update,delete on all tables in schema app_private to club_api;
grant usage, select on all sequences in schema app_private to club_api;
grant select,insert,update,delete on storage.objects to club_api;
grant execute on all functions in schema app_private, public to club_api;

-- 4. Policies: club_api may do what the functions ask; everyone (club_api included) stays inside their own club.
do $$ declare t text; begin
 for t in select c.relname from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname='app_private' and c.relkind='r' loop
  execute format('create policy club_api_all on app_private.%I for all to club_api using (true) with check (true)',t);
  if t not in ('clubs','roles') then
   execute format('create policy own_club on app_private.%I as restrictive for all to public using (club_id is not distinct from (select app_private.current_club())%s) with check (club_id is not distinct from (select app_private.current_club())%s)',
    t, case when t='profiles' then ' or id=auth.uid()' else '' end, case when t='profiles' then ' or id=auth.uid()' else '' end);
  end if;
 end loop;
end $$;

-- 5. Functions that cross clubs on purpose ---------------------------------------------------------------------
-- Called by the server (no signed-in user): take the club from the related record.
create or replace function public.complete_account_creation(p_request uuid,p_user uuid) returns void language plpgsql security definer set search_path='' as $$
declare r app_private.account_requests;
begin
 select * into r from app_private.account_requests where id=p_request and completed_at is null for update;
 if not found or not exists(select 1 from auth.users where id=p_user and lower(email)=r.email) then raise exception 'Account creation request does not match.'; end if;
 insert into app_private.profiles(id,full_name,email,club_id) values(p_user,r.full_name,r.email,r.club_id) on conflict do nothing;
 update app_private.account_requests set user_id=p_user,completed_at=now() where id=r.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(r.actor,14,p_user,'account_invited',jsonb_build_object('request_id',r.id),r.club_id);
end $$;

do $$ declare def text:=pg_get_functiondef('public.record_video_verification(text,uuid,integer)'::regprocedure);
 snippet text:='insert into app_private.video_verifications(path,duration_ms) values(p_path,p_duration_ms)';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'record_video_verification() is not the expected version.'; end if;
 execute replace(def,snippet,'insert into app_private.video_verifications(path,duration_ms,club_id) values(p_path,p_duration_ms,(select club_id from app_private.profiles where id=p_owner))');
end $$;

-- A new account picks its club. An account already in a club (a member, applicant, officer or partner guest)
-- can't move: one account belongs to one club.
create function public.join_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); c uuid; cur uuid;
begin
 c:=nullif(p_data->>'club_id','')::uuid;
 if c is null or not exists(select 1 from app_private.clubs where id=c) then raise exception 'Pick a club from the list.'; end if;
 select club_id into cur from app_private.profiles where id=u;
 if cur is not null and cur<>c and (exists(select 1 from app_private.memberships where user_id=u)
    or exists(select 1 from app_private.applications where user_id=u)
    or exists(select 1 from app_private.event_access where user_id=u)
    or exists(select 1 from app_private.role_assignments where user_id=u and retired_at is null)) then
  raise exception 'Your account already belongs to a club. Each account joins one club.'; end if;
 update app_private.profiles set club_id=c, updated_at=now() where id=u;
 return jsonb_build_object('club_id',c);
end $$;

-- The platform administrator creates a club with blank settings and names its first administrator, who must
-- already have an account that isn't part of another club.
create function public.create_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_id uuid; v_admin uuid; v_name text; v_role text;
 v_email text:=lower(trim(coalesce(p_data->>'admin_email','')));
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator can create clubs.' using errcode='42501'; end if;
 v_name:=trim(app_private.required(p_data,'name',160));
 if exists(select 1 from app_private.clubs where lower(name)=lower(v_name)) then raise exception 'A club with that name already exists.'; end if;
 if v_email='' then raise exception 'Enter the email of the club''s first administrator.'; end if;
 select id into v_admin from auth.users where lower(email)=v_email;
 if v_admin is null then raise exception 'No account uses %. Ask them to create an account in the app first.',v_email; end if;
 if v_admin=u or exists(select 1 from app_private.memberships where user_id=v_admin)
    or exists(select 1 from app_private.applications where user_id=v_admin and status<>'draft')
    or exists(select 1 from app_private.event_access where user_id=v_admin)
    or exists(select 1 from app_private.role_assignments where user_id=v_admin and retired_at is null) then
  raise exception 'That account already belongs to a club. The first administrator needs an account of their own.'; end if;
 insert into app_private.clubs(name,short_name,school,created_by)
  values(v_name,left(trim(coalesce(p_data->>'short_name','')),40),left(trim(coalesce(p_data->>'school','')),160),u) returning id into v_id;
 if coalesce(p_data->>'theme','green') not in ('green','red','blue') then raise exception 'Pick green, red or blue for the club''s colour.'; end if;
 update app_private.clubs set theme=coalesce(p_data->>'theme','green') where id=v_id;
 insert into app_private.settings(club_id,club_name) values(v_id,v_name);
 insert into app_private.profiles(id,email,club_id) values(v_admin,v_email,v_id)
  on conflict(id) do update set club_id=v_id, updated_at=now();
 select id into v_role from app_private.roles where 'administration'=any(permissions) order by (id='administrator') desc, id limit 1;
 insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason,club_id)
  values(v_admin,v_role,'Administrator',now(),now()+interval '1 year',u,'First administrator of the club',v_id);
 -- Like DLSU's developer account: the club's top administrator can hold officer roles before having a membership.
 update app_private.profiles set developer=true where id=v_admin;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,14,v_id,'create_club',jsonb_build_object('name',v_name,'admin_email',v_email),app_private.current_club());
 return jsonb_build_object('id',v_id);
end $$;
revoke all on function public.join_club(jsonb),public.create_club(jsonb) from public,anon;
grant execute on function public.join_club(jsonb),public.create_club(jsonb) to authenticated;

-- 6. The app learns its club and the list of clubs (to join one, or for the platform administrator) -----------
do $$ declare def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:='''fund_totals'',app_private.fund_totals());';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'club_workspace() is not the expected version.'; end if;
 execute replace(def,snippet,'''fund_totals'',app_private.fund_totals(),
  ''club'',(select to_jsonb(c) from app_private.clubs c where c.id=app_private.current_club()),
  ''clubs'',(select coalesce(jsonb_agg(c order by c.name),''[]'') from app_private.clubs c));');
end $$;


-- 6b. Each club's own fee rules: the amount was already per club (settings); the days to pay become a setting too.
alter table app_private.settings add column fee_due_days integer not null default 14 check(fee_due_days between 1 and 120);
do $$ declare def text:=pg_get_functiondef('app_private.membership_fee_assess()'::regprocedure); snippet text:='v_fee,current_date+14,new.approved_by'; begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'membership_fee_assess() is not the expected version.'; end if;
 execute replace(def,snippet,'v_fee,current_date+coalesce((select fee_due_days from app_private.settings where id),14),new.approved_by');
end $$;
do $$ declare def text:=pg_get_functiondef('app_private.hardening_command(text,jsonb)'::regprocedure);
 snippet text:='update app_private.settings set membership_fee=(p_data->>''amount'')::numeric,updated_at=now() where id=true;'; begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'hardening_command() is not the expected version.'; end if;
 execute replace(def,snippet,'if nullif(p_data->>''due_days'','''') is not null and (p_data->>''due_days'')::int not between 1 and 120 then
   raise exception ''Days to pay is 1 to 120.''; end if;
  update app_private.settings set membership_fee=(p_data->>''amount'')::numeric,
   fee_due_days=coalesce(nullif(p_data->>''due_days'','''')::int,fee_due_days),updated_at=now() where id=true;');
end $$;

-- 6c. Interclub across clubs: UP and Ateneo accounts join a DLSU event (and the other way round) with an invite code,
-- without leaving their own club. The event's rows stay with the host club; a guest sees and writes them only for
-- the events they were invited to (event_guest), and everyone sees notices addressed to them.
create function app_private.guest_partner(p_partner uuid) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.event_access where partner_id=p_partner and user_id=auth.uid() and revoked_at is null) $$;
grant execute on function app_private.guest_partner(uuid) to authenticated;
-- Rows of a shared event always belong to the host club, whoever adds them.
create function app_private.inherit_event_club() returns trigger language plpgsql security definer set search_path='' as $$
begin
 new.club_id:=coalesce((select club_id from app_private.events where id=new.event_id),new.club_id);
 return new;
end $$;
do $$ declare t text; begin
 foreach t in array array['interclub_events','delegates','interclub_assignments','interclub_results','interclub_concerns','interclub_archives','event_access','partner_invites'] loop
  execute format('create trigger inherit_event_club before insert on app_private.%I for each row execute function app_private.inherit_event_club()',t);
  execute format('drop policy own_club on app_private.%I',t);
  execute format('create policy own_club on app_private.%I as restrictive for all to public using (club_id is not distinct from (select app_private.current_club()) or app_private.event_guest(event_id)) with check (club_id is not distinct from (select app_private.current_club()) or app_private.event_guest(event_id))',t);
 end loop;
end $$;
-- The claim itself creates the guest's access row, so event_access also allows the guest's own row.
drop policy own_club on app_private.event_access;
create policy own_club on app_private.event_access as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club()) or user_id=auth.uid() or app_private.event_guest(event_id))
 with check (club_id is not distinct from (select app_private.current_club()) or user_id=auth.uid() or app_private.event_guest(event_id));
drop policy own_club on app_private.events;
create policy own_club on app_private.events as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club()) or app_private.event_guest(id))
 with check (club_id is not distinct from (select app_private.current_club()));
drop policy own_club on app_private.partner_clubs;
create policy own_club on app_private.partner_clubs as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club()) or app_private.guest_partner(id))
 with check (club_id is not distinct from (select app_private.current_club()));
drop policy own_club on app_private.notifications;
create policy own_club on app_private.notifications as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club()) or user_id=auth.uid())
 with check (true);

-- Claiming a code: an account already in a club (e.g. UP's administrator) stays in its club and only gains access
-- to the event; an account with no club becomes the host club's guest representative, as before. Members and
-- applicants of the host club itself still can't be partner representatives.
create or replace function public.claim_partner_invite(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.partner_invites; v_code text; v_phone text; v_name text; v_school text; v_club uuid;
begin
 if (select count(*) from app_private.partner_invite_attempts where user_id=u and at>now()-interval '1 hour')>=8 then
  raise exception 'Too many wrong codes. Try again in an hour, or ask the host club to check the code.'; end if;
 select club_id into v_club from app_private.profiles where id=u;
 -- The representative details are checked first (before the code), as before; another club's account skips them.
 if v_club is null or not exists(select 1 from app_private.clubs where id<>v_club) then
  v_name:=app_private.required(p_data,'full_name',160);
  v_school:=app_private.required(p_data,'university',160);
  v_phone:=trim(coalesce(p_data->>'phone',''));
  if v_phone='' or not app_private.valid_mobile(v_phone) then
   raise exception 'Enter a Philippine mobile number (09XXXXXXXXX or +639XXXXXXXXX).'; end if;
 end if;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g'));
 v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 select * into v from app_private.partner_invites where code=v_code for update;
 if not found or v.revoked_at is not null or v.expires_at<now() or (v.claimed_by is not null and v.claimed_by<>u) then
  insert into app_private.partner_invite_attempts(user_id) values(u);
  return jsonb_build_object('ok',false,'message','That invite code isn''t valid or has expired. Check it with the host club.');
 end if;
 if v_club=v.club_id and (exists(select 1 from app_private.memberships where user_id=u)
    or exists(select 1 from app_private.applications where user_id=u and status<>'draft')) then
  raise exception 'This account belongs to a member or applicant of the host club. Partner representatives need their own account.'; end if;
 -- Another club's account keeps its club and only gains access; anyone else becomes the host club's representative.
 if v_club is not null and v_club<>v.club_id then v_name:=null;
 else
  if v_name is null then
   v_name:=app_private.required(p_data,'full_name',160); v_school:=app_private.required(p_data,'university',160);
   v_phone:=trim(coalesce(p_data->>'phone',''));
   if v_phone='' or not app_private.valid_mobile(v_phone) then raise exception 'Enter a Philippine mobile number (09XXXXXXXXX or +639XXXXXXXXX).'; end if;
  end if;
  update app_private.profiles set full_name=v_name, university=v_school, phone=v_phone, account_type='partner', club_id=v.club_id, updated_at=now() where id=u;
 end if;
 insert into app_private.event_access(event_id,user_id,partner_id,role,invited_by) values(v.event_id,u,v.partner_id,'partner',v.created_by)
  on conflict(event_id,user_id) do update set partner_id=excluded.partner_id, role='partner', revoked_at=null;
 update app_private.partner_invites set claimed_by=u, claimed_at=coalesce(claimed_at,now()) where id=v.id;
 perform app_private.notify(v.created_by,15,v.event_id,'invitation',
  coalesce(v_name,(select full_name from app_private.profiles where id=u))||' joined as the representative of '||(select name from app_private.partner_clubs where id=v.partner_id));
 perform app_private.note(15,v.id,'claim_partner_invite',jsonb_build_object('event_id',v.event_id,'partner_id',v.partner_id));
 return jsonb_build_object('ok',true,'event_id',v.event_id);
end $$;

-- 7. Hand the functions to club_api ---------------------------------------------------------------------------
do $$ declare f record; begin
 for f in select p.oid::regprocedure as sig, p.proname, pg_get_functiondef(p.oid) as d
          from pg_proc p join pg_namespace n on n.oid=p.pronamespace
          where n.nspname in ('app_private','public') and p.prokind='f'
            and not exists(select 1 from pg_depend e where e.objid=p.oid and e.deptype='e') loop
  if f.proname = any(array['current_club','platform_admin','default_club','active_user','can','document_access','event_guest','event_staff',
     'member','registered_path','storage_write','claim_partner_invite','record_video_verification','complete_account_creation',
     'create_club','join_club','guard_role_catalogue','guest_partner','inherit_event_club']) or f.d ~ 'storage\.objects' then continue; end if;
  execute format('alter function %s owner to club_api',f.sig);
 end loop;
end $$;

-- On Supabase, postgres can't grant the auth schema to a new role (the grant is silently skipped), so club_api reaches
-- auth.uid() through the authenticated role, and the functions that read auth.users stay with postgres. Applied on
-- the live database right after this migration on 23 Nov, when sign-in failed without it.
alter role club_api inherit;
grant authenticated to club_api;
do $$ declare f record; begin
 for f in select p.oid::regprocedure as sig from pg_proc p join pg_namespace n on n.oid=p.pronamespace join pg_roles r on r.oid=p.proowner
          where r.rolname='club_api' and p.prokind='f' and pg_get_functiondef(p.oid) ~ 'auth\.(users|jwt|email|role|identities|sessions)' loop
  execute format('alter function %s owner to postgres',f.sig);
 end loop;
end $$;
-- Changing an owner rewrites the function's grants, so club_api lost the right to call the two moved back
-- (interclub_command, register_document). Grant again once every owner is final.
grant execute on all functions in schema app_private, public to club_api;

-- SOURCE: 202611230051_club_email_domains.sql
-- The email domain decides the club (23 Nov): an @dlsu.edu.ph account belongs to DLSU's club, an @up.edu.ph account
-- to UP's, an @ateneo.edu.ph account to Ateneo's. Accounts are placed in their club when they are created, and an
-- account can never belong to a club whose domain doesn't match its email, by any route (joining, being named an
-- administrator, edits). The only thing that crosses clubs is interclub access to one event (migration 050), and the
-- account stays in its own club. Partner-only accounts (account_type 'partner') are the host club's guests.
-- Which domain each club uses is data, set per club (live: DLSU dlsu.edu.ph, UP up.edu.ph, Ateneo ateneo.edu.ph);
-- a club without a domain keeps the old behaviour.

alter table app_private.clubs add column email_domain text check(email_domain ~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$');
create unique index clubs_email_domain_key on app_private.clubs(email_domain) where email_domain is not null;

-- The club for an email: its domain, or a parent domain (student.dlsu.edu.ph -> dlsu.edu.ph); the longest match wins.
create function app_private.club_for_email(p_email text) returns uuid language sql stable security definer set search_path='' as $$
 select c.id from app_private.clubs c, lower(split_part(coalesce(p_email,''),'@',2)) d
 where c.email_domain is not null and (d=c.email_domain or d like '%.'||c.email_domain)
 order by length(c.email_domain) desc limit 1 $$;
grant execute on function app_private.club_for_email(text) to authenticated;

create function app_private.profile_club_by_domain() returns trigger language plpgsql security definer set search_path='' as $$
declare v_match uuid:=app_private.club_for_email(new.email); v_domain text;
begin
 -- A new account goes to the club of its email domain.
 if tg_op='INSERT' and v_match is not null then new.club_id:=v_match; end if;
 if new.club_id is not null and new.account_type is distinct from 'partner' then
  select email_domain into v_domain from app_private.clubs where id=new.club_id;
  if v_domain is not null and v_match is distinct from new.club_id then
   raise exception '% can''t join %: each club is only for its own school''s email (@%).', new.email,
    (select name from app_private.clubs where id=new.club_id), v_domain using errcode='42501';
  end if;
  -- An account whose email matches a club can't be placed in a club without a domain either.
  if v_domain is null and v_match is not null and v_match<>new.club_id then
   raise exception '% belongs to %.', new.email, (select name from app_private.clubs where id=v_match) using errcode='42501';
  end if;
 end if;
 return new;
end $$;
create trigger profile_club_by_domain before insert or update of club_id, email, account_type on app_private.profiles
 for each row execute function app_private.profile_club_by_domain();

-- Creating a club now takes its email domain; the first administrator's email must be on it.
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
 update app_private.profiles set developer=true where id=v_admin;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,14,v_id,'create_club',jsonb_build_object('name',v_name,'admin_email',v_email,'email_domain',v_domain),app_private.current_club());
 return jsonb_build_object('id',v_id);
end $$;

-- The platform administrator sets or changes a club's domain (existing clubs had none).
create function public.set_club_domain(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_club uuid:=(p_data->>'club_id')::uuid;
 v_domain text:=nullif(lower(trim(both '@ ' from coalesce(p_data->>'email_domain',''))),'');
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator can change a club''s email domain.' using errcode='42501'; end if;
 if v_domain is not null and v_domain !~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$' then raise exception 'Enter a domain like dlsu.edu.ph.'; end if;
 update app_private.clubs set email_domain=v_domain where id=v_club;
 if not found then raise exception 'That club no longer exists.'; end if;
 return jsonb_build_object('id',v_club,'email_domain',v_domain);
end $$;
revoke all on function public.set_club_domain(jsonb) from public,anon;
grant execute on function public.set_club_domain(jsonb),public.create_club(jsonb) to authenticated;
grant execute on all functions in schema app_private, public to club_api;

-- SOURCE: 202611230052_club_requests.sql
-- A student whose school has no club yet asks for one (23 Nov). The request is for their own email's domain only
-- (an @feu.edu.ph account can request FEU's club, nobody else's), never a personal email provider. The platform
-- administrator approves (the club is created with the requester as its first administrator, migration 051's rules
-- apply) or declines with a reason. Both sides get a notice.

create table app_private.club_requests (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references app_private.profiles,
 -- Name and email at request time: the platform administrator can't read a profile outside their own club.
 requester text not null default '' check(length(requester)<=320),
 email_domain text not null check(email_domain ~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$'),
 name text not null check(length(trim(name)) between 2 and 160),
 short_name text not null default '' check(length(short_name)<=40),
 school text not null check(length(trim(school)) between 2 and 160),
 theme text not null default 'green' check(theme in ('green','red','blue')),
 note text not null default '' check(length(note)<=500),
 status text not null default 'pending' check(status in ('pending','approved','declined')),
 remark text not null default '' check(length(remark)<=500),
 club_id uuid references app_private.clubs,
 created_at timestamptz not null default now(),
 decided_at timestamptz, decided_by uuid
);
-- One open request per school at a time.
create unique index club_requests_pending_domain on app_private.club_requests(email_domain) where status='pending';
alter table app_private.club_requests enable row level security;
create policy club_requests_read on app_private.club_requests for select to authenticated
 using (user_id=auth.uid() or app_private.platform_admin());
create policy club_api_all on app_private.club_requests for all to club_api using (true) with check (true);
grant select on app_private.club_requests to authenticated;
grant select,insert,update,delete on app_private.club_requests to club_api;

create function public.request_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_email text; v_domain text; v_id uuid; a uuid;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_domain:=split_part(v_email,'@',2);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 if app_private.club_for_email(v_email) is not null then raise exception 'Your school already has a club. Sign out and back in to join it.'; end if;
 if v_domain in ('gmail.com','yahoo.com','ymail.com','outlook.com','hotmail.com','live.com','icloud.com','me.com','proton.me','protonmail.com','aol.com','gmx.com') then
  raise exception 'Use your school email to request a club. A personal email can''t stand for a school.'; end if;
 if exists(select 1 from app_private.club_requests where email_domain=v_domain and status='pending') then
  raise exception 'A request for @% is already waiting for the platform administrator.',v_domain; end if;
 insert into app_private.club_requests(user_id,requester,email_domain,name,short_name,school,theme,note)
  values(u,coalesce(nullif((select full_name from app_private.profiles where id=u),'')||' · ','')||v_email,v_domain,trim(app_private.required(p_data,'name',160)),left(trim(coalesce(p_data->>'short_name','')),40),
   trim(app_private.required(p_data,'school',160)),coalesce(nullif(p_data->>'theme',''),'green'),left(trim(coalesce(p_data->>'note','')),500))
  returning id into v_id;
 for a in select id from app_private.profiles where platform_admin loop
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(a,14,v_id,'club_request',coalesce(nullif((select full_name from app_private.profiles where id=u),''),v_email)||' ('||v_email||') asked for a club: '||trim(p_data->>'name'),
   (select club_id from app_private.profiles where id=a));
 end loop;
 return jsonb_build_object('id',v_id);
end $$;

create function public.decide_club_request(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); r app_private.club_requests; v_club uuid; v_email text; v_remark text:=trim(coalesce(p_data->>'remark',''));
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator decides club requests.' using errcode='42501'; end if;
 select * into r from app_private.club_requests where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'That request no longer exists.'; end if;
 if r.status<>'pending' then raise exception 'This request was already decided.'; end if;
 if p_data->>'decision'='approve' then
  select email into v_email from auth.users where id=r.user_id;
  v_club:=(public.create_club(jsonb_build_object('name',r.name,'short_name',r.short_name,'school',r.school,'email_domain',r.email_domain,
   'admin_email',v_email,'theme',r.theme))->>'id')::uuid;
  update app_private.club_requests set status='approved',club_id=v_club,remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club was approved: '||r.name||'. You are its first administrator. Open Club setup to set its rules.',v_club);
 elsif p_data->>'decision'='decline' then
  if v_remark='' then raise exception 'Say why the request is declined, so the student knows what to do.'; end if;
  update app_private.club_requests set status='declined',remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club request wasn''t approved: '||v_remark,null);
 else raise exception 'Approve or decline the request.'; end if;
 return jsonb_build_object('id',r.id,'club_id',v_club);
end $$;
revoke all on function public.request_club(jsonb),public.decide_club_request(jsonb) from public,anon;
grant execute on function public.request_club(jsonb),public.decide_club_request(jsonb) to authenticated;

-- The requester and the platform administrator see the requests in the workspace (by the read policy above).
do $$ declare def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:='''clubs'',(select coalesce(jsonb_agg(c order by c.name),''[]'') from app_private.clubs c));';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'club_workspace() is not the expected version.'; end if;
 execute replace(def,snippet,'''clubs'',(select coalesce(jsonb_agg(c order by c.name),''[]'') from app_private.clubs c),
  ''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q));');
end $$;
grant execute on all functions in schema app_private, public to club_api;

-- SOURCE: 202611230053_join_codes.sql
-- Join codes (23 Nov): people without a school email (alumni, coaches, faculty) join a club only when that club lets
-- them in. The club's Membership officer or an Administrator makes a single-use code for one personal email; the
-- person enters it on Create account. The code places the account in that club and nowhere else, and is the only
-- exception to "the email domain decides the club" (migration 051). They then apply and are reviewed as usual.
-- The sign-up form checks a code before creating the account, so a personal email never ends up with no club.

create table app_private.join_codes (
 id uuid primary key default gen_random_uuid(),
 club_id uuid not null references app_private.clubs default app_private.current_club(),
 code text not null unique,
 email text not null check(email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
 full_name text not null check(length(trim(full_name)) between 2 and 160),
 reason text not null check(length(trim(reason)) between 2 and 300),
 created_by uuid not null default auth.uid(),
 created_at timestamptz not null default now(),
 expires_at timestamptz not null default now()+interval '14 days',
 used_by uuid, used_at timestamptz, revoked_at timestamptz
);
alter table app_private.join_codes enable row level security;
create policy join_codes_read on app_private.join_codes for select to authenticated
 using (app_private.can('membership') or app_private.can('administration'));
create policy club_api_all on app_private.join_codes for all to club_api using (true) with check (true);
create policy own_club on app_private.join_codes as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club())) with check (club_id is not distinct from (select app_private.current_club()));
grant select on app_private.join_codes to authenticated;
grant select,insert,update,delete on app_private.join_codes to club_api;

-- Officer: make a code for one person's personal email.
create function public.create_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_email text:=lower(trim(coalesce(p_data->>'email',''))); v_code text; v_id uuid;
 v_days int:=coalesce(nullif(p_data->>'days','')::int,14); v_match uuid; v_club uuid:=app_private.current_club();
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can make join codes.' using errcode='42501'; end if;
 if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then raise exception 'Enter the person''s email.'; end if;
 if v_days not between 1 and 60 then raise exception 'A join code lasts 1 to 60 days.'; end if;
 v_match:=app_private.club_for_email(v_email);
 if v_match=v_club then raise exception 'They don''t need a code: an @% account joins your club when it signs up.',split_part(v_email,'@',2); end if;
 if v_match is not null then raise exception '% belongs to %. Each club is only for its own school.',v_email,(select name from app_private.clubs where id=v_match); end if;
 if exists(select 1 from auth.users where lower(email)=v_email) then
  raise exception 'An account with % already exists. Join codes are for new accounts.',v_email; end if;
 loop
  v_code:=upper(substr(md5(gen_random_uuid()::text),1,4)||'-'||substr(md5(gen_random_uuid()::text),1,4));
  exit when not exists(select 1 from app_private.join_codes where code=v_code)
        and not exists(select 1 from app_private.partner_invites where code=v_code);
 end loop;
 insert into app_private.join_codes(club_id,code,email,full_name,reason,created_by,expires_at)
  values(v_club,v_code,v_email,trim(app_private.required(p_data,'full_name',160)),trim(app_private.required(p_data,'reason',300)),u,now()+make_interval(days=>v_days))
  returning id into v_id;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,v_id,'create_join_code',jsonb_build_object('email',v_email,'reason',p_data->>'reason'),v_club);
 return jsonb_build_object('id',v_id,'code',v_code);
end $$;

create function public.revoke_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes;
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can cancel join codes.' using errcode='42501'; end if;
 select * into j from app_private.join_codes where id=(p_data->>'id')::uuid and club_id=app_private.current_club() for update;
 if not found then raise exception 'That code no longer exists.'; end if;
 if j.used_by is not null then raise exception 'This code was already used.'; end if;
 update app_private.join_codes set revoked_at=now() where id=j.id;
 return jsonb_build_object('id',j.id);
end $$;

-- Create account, before the account exists: is this a usable code for this email? Join codes must match the email;
-- interclub invite codes (migration 044) work with any email. Says nothing else about the code or the club's data.
create function public.check_signup_code(p_code text,p_email text) returns jsonb language sql stable security definer set search_path='' as $$
 with c as (select upper(substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),1,4)||'-'||substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),5,4)) code)
 select coalesce(
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.join_codes j join app_private.clubs cl on cl.id=j.club_id, c
    where j.code=c.code and j.used_by is null and j.revoked_at is null and j.expires_at>now() and j.email=lower(trim(p_email))),
  (select jsonb_build_object('kind','invite','club',cl.name) from app_private.partner_invites v join app_private.clubs cl on cl.id=v.club_id, c
    where v.code=c.code and v.claimed_by is null and v.revoked_at is null and v.expires_at>now()),
  jsonb_build_object('kind',null)) $$;

-- After signing up: the code places the account in its club.
create function public.claim_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes; v_email text; v_code text;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g')); v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 select * into j from app_private.join_codes where code=v_code for update;
 if not found or j.revoked_at is not null or j.expires_at<now() or j.used_by is not null or j.email<>v_email then
  raise exception 'That join code isn''t valid for %. Check it with the club that sent it.',v_email; end if;
 update app_private.join_codes set used_by=u, used_at=now() where id=j.id;
 update app_private.profiles set club_id=j.club_id, full_name=coalesce(nullif(full_name,''),j.full_name), updated_at=now() where id=u;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,j.id,'claim_join_code',jsonb_build_object('email',v_email),j.club_id);
 return jsonb_build_object('club_id',j.club_id);
end $$;
revoke all on function public.create_join_code(jsonb),public.revoke_join_code(jsonb),public.claim_join_code(jsonb),public.check_signup_code(text,text) from public;
grant execute on function public.create_join_code(jsonb),public.revoke_join_code(jsonb),public.claim_join_code(jsonb) to authenticated;
grant execute on function public.check_signup_code(text,text) to anon,authenticated;

-- The domain guard (migration 051) accepts an account its club let in with a join code.
create or replace function app_private.profile_club_by_domain() returns trigger language plpgsql security definer set search_path='' as $$
declare v_match uuid:=app_private.club_for_email(new.email); v_domain text;
begin
 if tg_op='INSERT' and v_match is not null then new.club_id:=v_match; end if;
 if new.club_id is not null and new.account_type is distinct from 'partner'
    and not exists(select 1 from app_private.join_codes where used_by=new.id and club_id=new.club_id) then
  select email_domain into v_domain from app_private.clubs where id=new.club_id;
  if v_domain is not null and v_match is distinct from new.club_id then
   raise exception '% can''t join %: each club is only for its own school''s email (@%).', new.email,
    (select name from app_private.clubs where id=new.club_id), v_domain using errcode='42501';
  end if;
  if v_domain is null and v_match is not null and v_match<>new.club_id then
   raise exception '% belongs to %.', new.email, (select name from app_private.clubs where id=v_match) using errcode='42501';
  end if;
 end if;
 return new;
end $$;

-- Officers see their club's codes in the workspace (by the read policy above).
do $$ declare def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:='''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q));';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'club_workspace() is not the expected version.'; end if;
 execute replace(def,snippet,'''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q),
  ''join_codes'',(select coalesce(jsonb_agg(j order by j.created_at desc),''[]'') from app_private.join_codes j));');
end $$;
grant execute on all functions in schema app_private, public to club_api;

-- SOURCE: 202611230054_first_admin_membership.sql
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

-- SOURCE: 202611230055_club_features.sql
-- 055: each club turns features on and off for its own members and officers (23 Nov, the developer's decision).
-- Switchable: activities (with announcements), equipment and borrowing, training, elections, interclub, reports.
-- Membership, payments and administration always stay on: without them nobody can join, pay or manage the club.
-- Off means off everywhere in that club:
--  * app_private.can() answers no for that desk, so officer screens, their row policies and every desk-checked
--    function refuse, even for people whose role still lists the desk (roles are shared by every club);
--  * member actions sent through club_command for that feature are refused (borrow, register, log training, vote...);
--  * the app hides the feature's tabs, tiles and desks (it reads settings.disabled_features).
-- Records are kept: turning a feature back on shows everything as it was.

alter table app_private.settings add column if not exists disabled_features text[] not null default '{}';
alter table app_private.settings add constraint settings_disabled_features_known
 check (disabled_features <@ array['activities','equipment','training','elections','interclub','reports']);

-- Is this feature turned off in the caller's club? (Owned by postgres: reads settings whatever the caller's policies.)
create function app_private.feature_off(p_feature text) returns boolean language sql stable security definer set search_path='' as $$
 select p_feature=any(coalesce((select s.disabled_features from app_private.settings s where s.club_id is not distinct from app_private.current_club() limit 1),'{}'::text[]))
$$;
revoke all on function app_private.feature_off(text) from public,anon;
grant execute on function app_private.feature_off(text) to authenticated;

-- Same as migration 001 plus the club's switches.
create or replace function app_private.can(p_permission text) returns boolean language sql stable security definer set search_path='' as $$
 select app_private.active_user() and not app_private.feature_off(p_permission) and exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at and p_permission=any(r.permissions))
$$;

-- Which feature a club_command action belongs to (null: always available).
create function app_private.feature_for_action(p_action text) returns text language sql immutable set search_path='' as $$
 select case
  when p_action in ('save_event','publish_event','archive_event','register_event','cancel_registration','open_attendance','check_in',
                    'record_attendance','request_attendance_correction','review_attendance_correction','publish_announcement','update_announcement') then 'activities'
  when p_action in ('save_equipment','request_borrowing','review_borrowing','cancel_borrowing') then 'equipment'
  when p_action in ('save_training','save_personal_training','submit_training','validate_training','import_training',
                    'request_training_correction','review_training_correction') then 'training'
  when p_action in ('save_term','record_residency','create_election','update_election','file_candidacy','nominate_candidate','confirm_candidate',
                    'confirm_voter','cast_ballot','set_election_status','record_external_results','confirm_results','record_leadership') then 'elections'
  when p_action in ('save_partner','share_interclub_event','invite_partner','revoke_event_access','save_delegate','review_delegate','substitute_delegate',
                    'assign_delegate','raise_concern','resolve_concern','record_result','verify_result','update_shared_event','archive_interclub') then 'interclub'
  when p_action='save_goal' then 'reports'
 end
$$;

-- club_command gains one more outer check, the same way earlier migrations wrapped it.
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_features;
revoke all on function app_private.club_command_before_features(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_feature text:=app_private.feature_for_action(p_action);
begin
 if v_feature is not null and app_private.feature_off(v_feature) then
  raise exception 'Your club has turned off %.', case v_feature when 'activities' then 'activities' when 'equipment' then 'equipment borrowing'
   when 'training' then 'training' when 'elections' then 'elections' when 'interclub' then 'partner club events' else 'reports and goals' end
   using errcode='42501';
 end if;
 return app_private.club_command_before_features(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

-- The administrator sets the switches for their own club only.
create function public.set_club_features(p_disabled text[]) returns jsonb language plpgsql security definer set search_path='' as $$
declare v_old text[]; v_new text[];
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 select coalesce(array_agg(distinct x order by x),'{}') into v_new from unnest(coalesce(p_disabled,'{}')) x;
 if not v_new <@ array['activities','equipment','training','elections','interclub','reports'] then
  raise exception 'Membership, payments and administration can''t be turned off.'; end if;
 select disabled_features into v_old from app_private.settings where club_id is not distinct from app_private.current_club();
 if not found then raise exception 'Your club has no settings yet. Open Club setup first.'; end if;
 update app_private.settings set disabled_features=v_new, updated_at=now() where club_id is not distinct from app_private.current_club();
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(auth.uid(),14,null,'set_club_features',jsonb_build_object('off',to_jsonb(v_new),'before',to_jsonb(v_old)),app_private.current_club());
 return jsonb_build_object('off',to_jsonb(v_new));
end $$;
revoke all on function public.set_club_features(text[]) from public,anon;
grant execute on function public.set_club_features(text[]) to authenticated;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';

-- SOURCE: 202611230056_club_join_code.sql
-- 056: one shared join code per club (23 Nov, the developer's decision). Per-person codes (053) needed the officer to
-- know a name and email before the person signed up. Now each club has one reusable code it posts where its alumni
-- are (group chat, Facebook page). Anyone with a personal email signs up with it, lands in that club and applies as
-- usual: the membership application is still where the club decides. Officers can change the code if it leaks.
-- A school email of another club can't use it (each club is only for its own school). Per-person codes still work.
-- Each use is recorded as a used row in join_codes, so officers see who joined with it and the domain guard (053)
-- accepts the account as one its club let in.

create function app_private.new_signup_code() returns text language plpgsql volatile security definer set search_path='' as $$
declare v_code text;
begin
 loop
  v_code:=upper(substr(md5(gen_random_uuid()::text),1,4)||'-'||substr(md5(gen_random_uuid()::text),1,4));
  exit when not exists(select 1 from app_private.join_codes where code=v_code or code like v_code||':%')
        and not exists(select 1 from app_private.partner_invites where code=v_code)
        and not exists(select 1 from app_private.settings where join_code=v_code);
 end loop;
 return v_code;
end $$;
revoke all on function app_private.new_signup_code() from public,anon,authenticated;

alter table app_private.settings add column if not exists join_code text unique;
update app_private.settings set join_code=app_private.new_signup_code() where join_code is null;
alter table app_private.settings alter column join_code set default app_private.new_signup_code();

-- Officer: replace the club's code; the old one stops working at once.
create function public.reset_club_join_code() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_code text:=app_private.new_signup_code(); v_club uuid:=app_private.current_club();
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can change the join code.' using errcode='42501'; end if;
 update app_private.settings set join_code=v_code, updated_at=now() where club_id is not distinct from v_club;
 if not found then raise exception 'Your club has no settings yet. Open Club setup first.'; end if;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(u,1,null,'reset_club_join_code','{}',v_club);
 return jsonb_build_object('code',v_code);
end $$;
revoke all on function public.reset_club_join_code() from public,anon;
grant execute on function public.reset_club_join_code() to authenticated;

-- Create account, before the account exists. Adds the club code: any personal email, or that club's own school.
create or replace function public.check_signup_code(p_code text,p_email text) returns jsonb language sql stable security definer set search_path='' as $$
 with c as (select upper(substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),1,4)||'-'||substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),5,4)) code)
 select coalesce(
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.join_codes j join app_private.clubs cl on cl.id=j.club_id, c
    where j.code=c.code and j.used_by is null and j.revoked_at is null and j.expires_at>now() and j.email=lower(trim(p_email))),
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.settings s join app_private.clubs cl on cl.id=s.club_id, c
    where s.join_code=c.code and coalesce(app_private.club_for_email(p_email),s.club_id)=s.club_id),
  (select jsonb_build_object('kind','invite','club',cl.name) from app_private.partner_invites v join app_private.clubs cl on cl.id=v.club_id, c
    where v.code=c.code and v.claimed_by is null and v.revoked_at is null and v.expires_at>now()),
  jsonb_build_object('kind',null)) $$;

-- After signing up: a personal code first (it carries the name the officer typed), then the club code.
create or replace function public.claim_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes; v_email text; v_code text; v_club uuid; v_name text; v_id uuid;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g')); v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 select * into j from app_private.join_codes where code=v_code for update;
 if found and j.revoked_at is null and j.expires_at>=now() and j.used_by is null and j.email=v_email then
  update app_private.join_codes set used_by=u, used_at=now() where id=j.id;
  update app_private.profiles set club_id=j.club_id, full_name=coalesce(nullif(full_name,''),j.full_name), updated_at=now() where id=u;
  insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
   values(u,1,j.id,'claim_join_code',jsonb_build_object('email',v_email),j.club_id);
  return jsonb_build_object('club_id',j.club_id);
 end if;
 select club_id into v_club from app_private.settings where join_code=v_code;
 if v_club is null then raise exception 'That join code isn''t valid for %. Check it with the club that sent it.',v_email; end if;
 if app_private.club_for_email(v_email) is distinct from v_club and app_private.club_for_email(v_email) is not null then
  raise exception '% belongs to %. Each club is only for its own school.',v_email,(select name from app_private.clubs where id=app_private.club_for_email(v_email)); end if;
 select coalesce(nullif(trim(full_name),''),split_part(v_email,'@',1)) into v_name from app_private.profiles where id=u;
 if length(v_name)<2 then v_name:=v_name||' (personal email)'; end if;
 insert into app_private.join_codes(club_id,code,email,full_name,reason,created_by,used_by,used_at)
  values(v_club,v_code||':'||u::text,v_email,left(v_name,160),'Signed up with the club code',u,u,now()) returning id into v_id;
 update app_private.profiles set club_id=v_club, updated_at=now() where id=u;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,v_id,'claim_club_join_code',jsonb_build_object('email',v_email),v_club);
 return jsonb_build_object('club_id',v_club);
end $$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';

notify pgrst, 'reload schema';
commit;
