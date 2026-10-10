begin;
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
commit;
