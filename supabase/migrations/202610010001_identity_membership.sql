begin;
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
commit;
