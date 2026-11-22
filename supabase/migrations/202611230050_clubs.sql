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
begin;

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

commit;
