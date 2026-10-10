begin;
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
commit;
