-- Partner representatives join with an invite code (20 Nov testing: a UP rep was sent into the DLSU membership
-- application and asked for a DLSU ID number).
--  * The Interclub officer creates a single-use code for one event and one partner club and sends it through that
--    club's official channel. It expires when the event's partner sign-ups close.
--  * Whoever signs up and enters the code becomes that club's representative for that event only. Holding the code is
--    what grants access, not the email address used to sign up (email confirmation is off, so emails prove nothing).
--  * The rep gives their name, university and a Philippine mobile number; no DLSU ID and no membership application.
--  * Wrong codes are counted: 8 wrong tries in an hour locks claiming for that account for the rest of the hour.
--  * One person, one club: an account with a DLSU membership or a submitted application can't claim a partner code.
begin;

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

commit;
