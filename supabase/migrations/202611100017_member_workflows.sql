-- Member workflow completion (consultation follow-up, November 2026).
--  * Full member profile editing (contact, emergency contact, academic and archery details).
--  * Registration lifecycle: register -> (full) waitlist -> cancel -> automatic waitlist promotion.
--    The registrations table keeps its meaning (confirmed seats), so capacity checks, QR/manual
--    attendance and reports continue to count only confirmed participants.
--  * Member-side borrowing cancellation before release.
--  * Read models for the app: per-event seat counts and live equipment status, so members see
--    "3 spots left" / "Borrowed until Nov 12" without reading other members' records.
begin;

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
commit;
