-- 058: a guest club's members sign up for another club's interclub event themselves (23 Nov, the developer's decision).
-- 057 let DLSU send an event to ADMU and ADMU's officer accept it, but then the representative typed every archer in.
-- Now, once ADMU has accepted:
--  1. ADMU's representative (or interclub officer / administrator) posts it to ADMU's members, with a slot limit if any;
--  2. ADMU's Active members see it in Activities and sign up with their bow category;
--  3. the representative confirms who goes (each confirmed sign-up becomes a delegate, exactly as if typed in, so the
--     host's review, targets, check-in and results are unchanged) or declines them with a reason;
--  4. the member follows their own entry: signed up, confirmed, the host's review, their target and their result.
-- Typed-in delegates still work, for clubs that don't use the app.

alter table app_private.interclub_invitations add column if not exists published_at timestamptz;
alter table app_private.interclub_invitations add column if not exists member_note text not null default '';
alter table app_private.interclub_invitations add column if not exists slots int check (slots is null or slots between 1 and 200);
alter table app_private.delegates add column if not exists member_id uuid references app_private.profiles;

create table app_private.interclub_signups (
 id uuid primary key default gen_random_uuid(),
 invitation_id uuid not null references app_private.interclub_invitations,
 event_id uuid not null references app_private.interclub_events,
 club_id uuid not null references app_private.clubs,               -- the guest club (the member's own)
 user_id uuid not null references app_private.profiles,
 full_name text not null, category text not null, note text not null default '',
 status text not null default 'signed_up' check(status in ('signed_up','confirmed','declined','withdrawn')),
 remark text not null default '', delegate_id uuid references app_private.delegates,
 created_at timestamptz not null default now(), decided_by uuid references app_private.profiles, decided_at timestamptz,
 unique(invitation_id,user_id)
);
alter table app_private.interclub_signups enable row level security;

-- Who speaks for a guest club on an event: its representative for it, or its interclub officer / administrator.
create function app_private.guest_club_lead(p_invitation uuid) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.interclub_invitations v where v.id=p_invitation and v.guest_club is not distinct from app_private.current_club()
  and (app_private.can('interclub') or app_private.can('administration')
       or exists(select 1 from app_private.event_access a where a.event_id=v.event_id and a.user_id=auth.uid() and a.partner_id=v.partner_id and a.revoked_at is null)))
$$;
grant execute on function app_private.guest_club_lead(uuid) to authenticated;

-- A member sees their own sign-up; the club's lead for that event sees them all. Writes go through the functions below.
create policy own_or_lead on app_private.interclub_signups for select to authenticated
 using (user_id=auth.uid() or app_private.guest_club_lead(invitation_id));
grant select on app_private.interclub_signups to authenticated;

create function public.publish_interclub_invitation(p_id uuid,p_note text default '',p_slots int default null) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.interclub_invitations; i app_private.interclub_events; m uuid; n int:=0;
begin
 select * into v from app_private.interclub_invitations where id=p_id for update;
 if not found or not app_private.guest_club_lead(p_id) then raise exception 'Permission denied: only your club''s representative or interclub officer can post this.' using errcode='42501'; end if;
 if v.status<>'accepted' then raise exception 'Accept the invitation first.'; end if;
 if v.published_at is not null then raise exception 'Your members can already see this event.'; end if;
 select * into i from app_private.interclub_events where event_id=v.event_id;
 if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Sign-ups for this event have closed.'; end if;
 if p_slots is not null and (p_slots<1 or p_slots>200) then raise exception 'Slots must be between 1 and 200.'; end if;
 update app_private.interclub_invitations set published_at=now(), member_note=left(trim(coalesce(p_note,'')),500), slots=p_slots where id=p_id;
 for m in select ms.user_id from app_private.memberships ms join app_private.profiles p on p.id=ms.user_id
          where ms.status='Active' and p.club_id=v.guest_club and ms.user_id<>u loop
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(m,15,v.id,'interclub_open',v.host_name||'''s '||v.event_title||', '||to_char(v.starts_at at time zone 'Asia/Manila','Mon DD')||': sign up in Activities by '||
   to_char(v.registration_closes at time zone 'Asia/Manila','Mon DD')||'.',v.guest_club);
  n:=n+1;
 end loop;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(u,15,p_id,'publish_interclub_invitation',jsonb_build_object('slots',p_slots,'notified',n),v.guest_club);
 return jsonb_build_object('notified',n);
end $$;

create function public.interclub_signup(p_id uuid,p_category text,p_note text default '') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v app_private.interclub_invitations; i app_private.interclub_events; s app_private.interclub_signups; v_id uuid; v_name text;
begin
 select * into v from app_private.interclub_invitations where id=p_id;
 if not found or v.guest_club is distinct from app_private.current_club() or v.published_at is null or v.status<>'accepted' then raise exception 'This event isn''t open to your club.'; end if;
 if not exists(select 1 from app_private.memberships where user_id=u and status='Active') then raise exception 'Only Active members can sign up for interclub events.'; end if;
 select * into i from app_private.interclub_events where event_id=v.event_id;
 if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Sign-ups for this event have closed.'; end if;
 if length(trim(coalesce(p_category,'')))<2 then raise exception 'Pick your bow category.'; end if;
 select coalesce(nullif(trim(full_name),''),email) into v_name from app_private.profiles where id=u;
 select * into s from app_private.interclub_signups where invitation_id=p_id and user_id=u for update;
 if found then
  if s.status in ('signed_up','confirmed') then raise exception 'You''re already signed up.'; end if;
  update app_private.interclub_signups set status='signed_up', category=trim(p_category), note=left(trim(coalesce(p_note,'')),300), full_name=v_name,
   remark='', decided_by=null, decided_at=null, delegate_id=null, created_at=now() where id=s.id returning id into v_id;
 else
  insert into app_private.interclub_signups(invitation_id,event_id,club_id,user_id,full_name,category,note)
  values(p_id,v.event_id,v.guest_club,u,v_name,trim(p_category),left(trim(coalesce(p_note,'')),300)) returning id into v_id;
 end if;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(u,15,v_id,'interclub_signup',jsonb_build_object('event_id',v.event_id),v.guest_club);
 return jsonb_build_object('id',v_id);
end $$;

create function public.withdraw_interclub_signup(p_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); s app_private.interclub_signups;
begin
 select * into s from app_private.interclub_signups where invitation_id=p_id and user_id=u for update;
 if not found or s.status not in ('signed_up','confirmed') then raise exception 'You aren''t signed up for this event.'; end if;
 if s.status='confirmed' then raise exception 'You''re already in the delegation. Ask your club''s representative to take you off.'; end if;
 update app_private.interclub_signups set status='withdrawn' where id=s.id;
 return jsonb_build_object('id',s.id);
end $$;

-- The lead confirms (the member becomes a delegate) or declines with a reason.
create function public.decide_interclub_signup(p_signup uuid,p_confirm boolean,p_remark text default '') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); s app_private.interclub_signups; v app_private.interclub_invitations; i app_private.interclub_events;
 v_club text; v_delegate uuid; v_confirmed int;
begin
 select * into s from app_private.interclub_signups where id=p_signup for update;
 if not found or not app_private.guest_club_lead(s.invitation_id) then raise exception 'Permission denied: only your club''s representative can confirm sign-ups.' using errcode='42501'; end if;
 if s.status<>'signed_up' then raise exception 'This sign-up was already decided or withdrawn.'; end if;
 select * into v from app_private.interclub_invitations where id=s.invitation_id;
 select * into i from app_private.interclub_events where event_id=s.event_id;
 select name into v_club from app_private.clubs where id=s.club_id;
 if p_confirm then
  if i.status<>'preparation' or now()>i.registration_closes then raise exception 'Delegation registration is closed. Request an approved substitution.'; end if;
  select count(*) into v_confirmed from app_private.interclub_signups where invitation_id=s.invitation_id and status='confirmed';
  if v.slots is not null and v_confirmed>=v.slots then raise exception 'All % slots are taken. Decline someone or raise the limit with the host.',v.slots; end if;
  insert into app_private.delegates(event_id,partner_id,full_name,affiliation,category,requirements,member_id)
  values(s.event_id,v.partner_id,s.full_name,v_club,s.category,'Confirmed by '||v_club,s.user_id) returning id into v_delegate;
  update app_private.interclub_signups set status='confirmed', delegate_id=v_delegate, remark=left(trim(coalesce(p_remark,'')),300), decided_by=u, decided_at=now() where id=s.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(s.user_id,15,s.invitation_id,'interclub_open','You''re in '||v_club||'''s delegation for '||v.event_title||'. '||v.host_name||' reviews each delegate next.',s.club_id);
 else
  if length(trim(coalesce(p_remark,'')))<3 then raise exception 'Give the member a short reason.'; end if;
  update app_private.interclub_signups set status='declined', remark=left(trim(p_remark),300), decided_by=u, decided_at=now() where id=s.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(s.user_id,15,s.invitation_id,'interclub_open','Your sign-up for '||v.event_title||' wasn''t confirmed: '||trim(p_remark),s.club_id);
 end if;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,15,s.id,case when p_confirm then 'confirm_interclub_signup' else 'decline_interclub_signup' end,jsonb_build_object('event_id',s.event_id),s.club_id);
 return jsonb_build_object('id',s.id,'delegate_id',v_delegate);
end $$;

-- What a member can't read directly (the delegate row, target and result live with the host): their own entries.
create function app_private.my_interclub_entries() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object(
   'signup_id',s.id,'invitation_id',s.invitation_id,'event_id',s.event_id,'status',s.status,'remark',s.remark,'category',s.category,
   'delegate_status',d.status,'delegate_remark',d.remark,'event_status',i.status,
   'target',(select jsonb_build_object('starts_at',a.starts_at,'target_group',a.target_group) from app_private.interclub_assignments a where a.delegate_id=d.id order by a.starts_at limit 1),
   'result',(select r.result from app_private.interclub_results r where r.delegate_id=d.id and r.verified_by is not null))),'[]')
 from app_private.interclub_signups s join app_private.interclub_events i on i.event_id=s.event_id
 left join app_private.delegates d on d.id=s.delegate_id
 where s.user_id=auth.uid()
$$;
grant execute on function app_private.my_interclub_entries() to authenticated;

do $do$
declare f text;
begin
 foreach f in array array['publish_interclub_invitation(uuid,text,integer)','interclub_signup(uuid,text,text)','withdraw_interclub_signup(uuid)','decide_interclub_signup(uuid,boolean,text)'] loop
  execute format('revoke all on function public.%s from public,anon', f);
  execute format('grant execute on function public.%s to authenticated', f);
 end loop;
end $do$;

-- Workspace: the sign-ups the caller may see, and their own entries with the host's progress.
do $do$
declare d text; v_old text:='''interclub_invitations'',';
begin
 d:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 if (length(d)-length(replace(d,v_old,'')))/length(v_old)<>1 then
  raise exception 'club_workspace is not the expected version (interclub_invitations not found exactly once); nothing changed.';
 end if;
 execute replace(d,v_old,$$'interclub_signups',(select coalesce(jsonb_agg(s order by s.created_at),'[]') from app_private.interclub_signups s),'my_interclub',app_private.my_interclub_entries(),$$||v_old);
end $do$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
