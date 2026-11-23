-- 057: send an interclub event to other clubs in the app (23 Nov testing: "I can't choose which clubs to send it to").
-- Until now a partner club was a typed name and its representative joined with an invite code. Clubs that use the app
-- (UP, Ateneo, FEU...) are now picked from the list instead:
--  * the host's interclub officer sends the shared event to the clubs they pick; each gets a partner_clubs row linked
--    to the real club, and the club's interclub officers and administrators get a notice;
--  * one of them accepts (they become the club's representative for that event: the same event_access row an invite
--    code gives, so the existing delegate, concern and results flow works unchanged) or declines with a reason;
--  * the host hears the answer. Invite codes stay for clubs that don't use the app.
-- A guest officer who also holds the interclub desk at their own club must not act as the host's staff, so
-- event_staff() now needs the event to belong to the caller's club, and club_command refuses host-only interclub
-- actions on another club's event.

alter table app_private.partner_clubs add column if not exists linked_club uuid references app_private.clubs;
create unique index if not exists partner_clubs_linked on app_private.partner_clubs(club_id,linked_club) where linked_club is not null;

create table app_private.interclub_invitations (
 id uuid primary key default gen_random_uuid(),
 event_id uuid not null references app_private.interclub_events,
 club_id uuid not null references app_private.clubs,            -- the host club
 guest_club uuid not null references app_private.clubs,
 partner_id uuid not null references app_private.partner_clubs,
 host_name text not null, event_title text not null, starts_at timestamptz not null, venue text not null, registration_closes timestamptz not null,
 status text not null default 'pending' check(status in ('pending','accepted','declined')),
 note text not null default '',
 sent_by uuid not null references app_private.profiles, sent_at timestamptz not null default now(),
 responded_by uuid references app_private.profiles, responded_at timestamptz,
 unique(event_id,guest_club), check(guest_club<>club_id)
);
alter table app_private.interclub_invitations enable row level security;
create policy both_clubs on app_private.interclub_invitations for select to authenticated
 using (club_id is not distinct from (select app_private.current_club()) or guest_club is not distinct from (select app_private.current_club()));
grant select on app_private.interclub_invitations to authenticated;

-- Staff of an event means its own club's interclub desk, or a coordinator the host added.
create or replace function app_private.event_staff(p_event uuid) returns boolean language sql stable security definer set search_path='' as $$
 select (app_private.can('interclub') and exists(select 1 from app_private.events where id=p_event and club_id is not distinct from app_private.current_club()))
  or (app_private.active_user() and exists(select 1 from app_private.event_access where event_id=p_event and user_id=auth.uid() and role='coordinator' and revoked_at is null))
$$;

-- The interclub officers (or, failing that, administrators) of a club: who hears about and answers an invitation.
create function app_private.club_interclub_contacts(p_club uuid) returns setof uuid language sql stable security definer set search_path='' as $$
 select distinct a.user_id from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 where a.club_id=p_club and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at
   and ('interclub'=any(r.permissions) or 'administration'=any(r.permissions))
$$;
revoke all on function app_private.club_interclub_contacts(uuid) from public,anon,authenticated;

create function public.send_interclub_invitations(p_event uuid,p_clubs uuid[]) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_host uuid:=app_private.current_club(); i app_private.interclub_events; c app_private.clubs;
 v_partner uuid; v_id uuid; v_sent int:=0; v_host_name text; t uuid;
begin
 if not app_private.can('interclub') then raise exception 'Only the interclub officer can send an event to other clubs.' using errcode='42501'; end if;
 select * into i from app_private.interclub_events where event_id=p_event and club_id is not distinct from v_host;
 if not found then raise exception 'Shared event not found.'; end if;
 if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Sign-ups for this event are closed, so it can''t be sent to more clubs.'; end if;
 if coalesce(cardinality(p_clubs),0)=0 then raise exception 'Pick at least one club.'; end if;
 select name into v_host_name from app_private.clubs where id=v_host;
 for c in select * from app_private.clubs where id=any(p_clubs) and id is distinct from v_host order by name loop
  -- A club that turned partner club events off (055) isn't sent anything.
  if 'interclub'=any(coalesce((select disabled_features from app_private.settings where club_id=c.id),'{}')) then continue; end if;
  v_partner:=null; v_id:=null;
  select id into v_partner from app_private.partner_clubs where club_id is not distinct from v_host and linked_club=c.id;
  if v_partner is null then
   select id into v_partner from app_private.partner_clubs where club_id is not distinct from v_host and lower(name)=lower(c.name);
   if v_partner is null then
    insert into app_private.partner_clubs(name,official_channel,club_id,linked_club) values(c.name,'Archery Club app',v_host,c.id) returning id into v_partner;
   else update app_private.partner_clubs set linked_club=c.id where id=v_partner; end if;
  end if;
  insert into app_private.interclub_invitations(event_id,club_id,guest_club,partner_id,host_name,event_title,starts_at,venue,registration_closes,sent_by)
   values(p_event,v_host,c.id,v_partner,v_host_name,i.shared_title,i.shared_start,i.shared_venue,i.registration_closes,u)
   on conflict(event_id,guest_club) do update set status='pending',note='',responded_by=null,responded_at=null,sent_by=u,sent_at=now(),
    event_title=excluded.event_title,starts_at=excluded.starts_at,venue=excluded.venue,registration_closes=excluded.registration_closes
    where app_private.interclub_invitations.status='declined'
   returning id into v_id;
  if v_id is null then continue; end if;   -- already waiting or accepted
  v_sent:=v_sent+1;
  for t in select * from app_private.club_interclub_contacts(c.id) loop
   insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
   values(t,15,v_id,'interclub_invitation',v_host_name||' invited your club to '||i.shared_title||', '||to_char(i.shared_start at time zone 'Asia/Manila','Mon DD')||' at '||i.shared_venue||'. Accept or decline it in Partner clubs.',c.id);
  end loop;
 end loop;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(u,15,p_event,'send_interclub_invitations',jsonb_build_object('clubs',to_jsonb(p_clubs),'sent',v_sent),v_host);
 return jsonb_build_object('sent',v_sent);
end $$;
revoke all on function public.send_interclub_invitations(uuid,uuid[]) from public,anon;
grant execute on function public.send_interclub_invitations(uuid,uuid[]) to authenticated;

create function public.respond_interclub_invitation(p_id uuid,p_accept boolean,p_note text default '') returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.interclub_invitations; i app_private.interclub_events; v_guest text; v_me text;
begin
 select * into v from app_private.interclub_invitations where id=p_id for update;
 if not found or v.guest_club is distinct from app_private.current_club() then raise exception 'Invitation not found.'; end if;
 if not (app_private.can('interclub') or app_private.can('administration')) then
  raise exception 'Only your club''s interclub officer or an administrator can answer this invitation.' using errcode='42501'; end if;
 if v.status<>'pending' then raise exception 'This invitation was already answered.'; end if;
 select name into v_guest from app_private.clubs where id=v.guest_club;
 select coalesce(nullif(full_name,''),email) into v_me from app_private.profiles where id=u;
 if p_accept then
  select * into i from app_private.interclub_events where event_id=v.event_id;
  if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Sign-ups for this event have closed.'; end if;
  insert into app_private.event_access(event_id,user_id,partner_id,role,invited_by) values(v.event_id,u,v.partner_id,'partner',v.sent_by)
   on conflict(event_id,user_id) do update set partner_id=excluded.partner_id, role='partner', revoked_at=null;
 elsif length(trim(coalesce(p_note,'')))<3 then raise exception 'Say briefly why your club is declining.';
 end if;
 update app_private.interclub_invitations set status=case when p_accept then 'accepted' else 'declined' end, note=left(trim(coalesce(p_note,'')),500),
  responded_by=u, responded_at=now() where id=p_id;
 insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
 values(v.sent_by,15,v.event_id,'interclub',
  case when p_accept then v_guest||' accepted '||v.event_title||'. '||v_me||' is their representative and will add their delegates.'
   else v_guest||' declined '||v.event_title||': '||trim(p_note) end, v.club_id);
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,15,p_id,case when p_accept then 'accept_interclub_invitation' else 'decline_interclub_invitation' end,jsonb_build_object('event_id',v.event_id),v.guest_club);
 return jsonb_build_object('event_id',v.event_id,'status',case when p_accept then 'accepted' else 'declined' end);
end $$;
revoke all on function public.respond_interclub_invitation(uuid,boolean,text) from public,anon;
grant execute on function public.respond_interclub_invitation(uuid,boolean,text) to authenticated;

-- The host's own controls stay with the host, even for a guest whose club gave them the interclub desk.
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_feature text:=app_private.feature_for_action(p_action);
begin
 if v_feature is not null and app_private.feature_off(v_feature) then
  raise exception 'Your club has turned off %.', case v_feature when 'activities' then 'activities' when 'equipment' then 'equipment borrowing'
   when 'training' then 'training' when 'elections' then 'elections' when 'interclub' then 'partner club events' else 'reports and goals' end
   using errcode='42501';
 end if;
 if p_action in ('invite_partner','revoke_event_access','review_delegate','assign_delegate','resolve_concern','record_result','verify_result',
                 'update_shared_event','archive_interclub')
    and nullif(p_data->>'event_id','') is not null and not app_private.event_staff((p_data->>'event_id')::uuid) then
  raise exception 'Permission denied: only the host club''s interclub staff can do that.' using errcode='42501';
 end if;
 return app_private.club_command_before_features(p_action,p_data);
end $$;

-- The workspace carries invitations both ways (sent by this club, and sent to it). Later migrations extended
-- club_workspace in place, so the live definition is read and one key is added after member_standing.
do $do$
declare d text; v_old text:='''member_standing'',app_private.member_standing()';
begin
 d:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 if (length(d)-length(replace(d,v_old,'')))/length(v_old)<>1 then
  raise exception 'club_workspace is not the expected version (member_standing not found exactly once); nothing changed.';
 end if;
 execute replace(d,v_old,v_old||$$,'interclub_invitations',(select coalesce(jsonb_agg(x order by x.sent_at desc),'[]') from app_private.interclub_invitations x)$$);
end $do$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
