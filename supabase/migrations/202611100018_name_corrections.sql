-- Verified names (consultation follow-up).
-- A member's full name is the identity an officer verified when approving the application,
-- and it appears on rosters, borrowings, ballots and reports. Once a membership exists the
-- member can no longer rename themselves; they file a correction that a membership officer
-- (not themselves) approves or rejects, with the decision kept in the audit log.
begin;

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
commit;
