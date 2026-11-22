-- The email domain decides the club (23 Nov): an @dlsu.edu.ph account belongs to DLSU's club, an @up.edu.ph account
-- to UP's, an @ateneo.edu.ph account to Ateneo's. Accounts are placed in their club when they are created, and an
-- account can never belong to a club whose domain doesn't match its email, by any route (joining, being named an
-- administrator, edits). The only thing that crosses clubs is interclub access to one event (migration 050), and the
-- account stays in its own club. Partner-only accounts (account_type 'partner') are the host club's guests.
-- Which domain each club uses is data, set per club (live: DLSU dlsu.edu.ph, UP up.edu.ph, Ateneo ateneo.edu.ph);
-- a club without a domain keeps the old behaviour.
begin;

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

commit;
