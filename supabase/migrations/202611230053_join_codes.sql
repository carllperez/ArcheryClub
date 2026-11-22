-- Join codes (23 Nov): people without a school email (alumni, coaches, faculty) join a club only when that club lets
-- them in. The club's Membership officer or an Administrator makes a single-use code for one personal email; the
-- person enters it on Create account. The code places the account in that club and nowhere else, and is the only
-- exception to "the email domain decides the club" (migration 051). They then apply and are reviewed as usual.
-- The sign-up form checks a code before creating the account, so a personal email never ends up with no club.
begin;

create table app_private.join_codes (
 id uuid primary key default gen_random_uuid(),
 club_id uuid not null references app_private.clubs default app_private.current_club(),
 code text not null unique,
 email text not null check(email ~ '^[^@\s]+@[^@\s]+\.[^@\s]+$'),
 full_name text not null check(length(trim(full_name)) between 2 and 160),
 reason text not null check(length(trim(reason)) between 2 and 300),
 created_by uuid not null default auth.uid(),
 created_at timestamptz not null default now(),
 expires_at timestamptz not null default now()+interval '14 days',
 used_by uuid, used_at timestamptz, revoked_at timestamptz
);
alter table app_private.join_codes enable row level security;
create policy join_codes_read on app_private.join_codes for select to authenticated
 using (app_private.can('membership') or app_private.can('administration'));
create policy club_api_all on app_private.join_codes for all to club_api using (true) with check (true);
create policy own_club on app_private.join_codes as restrictive for all to public
 using (club_id is not distinct from (select app_private.current_club())) with check (club_id is not distinct from (select app_private.current_club()));
grant select on app_private.join_codes to authenticated;
grant select,insert,update,delete on app_private.join_codes to club_api;

-- Officer: make a code for one person's personal email.
create function public.create_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_email text:=lower(trim(coalesce(p_data->>'email',''))); v_code text; v_id uuid;
 v_days int:=coalesce(nullif(p_data->>'days','')::int,14); v_match uuid; v_club uuid:=app_private.current_club();
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can make join codes.' using errcode='42501'; end if;
 if v_email !~ '^[^@\s]+@[^@\s]+\.[^@\s]+$' then raise exception 'Enter the person''s email.'; end if;
 if v_days not between 1 and 60 then raise exception 'A join code lasts 1 to 60 days.'; end if;
 v_match:=app_private.club_for_email(v_email);
 if v_match=v_club then raise exception 'They don''t need a code: an @% account joins your club when it signs up.',split_part(v_email,'@',2); end if;
 if v_match is not null then raise exception '% belongs to %. Each club is only for its own school.',v_email,(select name from app_private.clubs where id=v_match); end if;
 if exists(select 1 from auth.users where lower(email)=v_email) then
  raise exception 'An account with % already exists. Join codes are for new accounts.',v_email; end if;
 loop
  v_code:=upper(substr(md5(gen_random_uuid()::text),1,4)||'-'||substr(md5(gen_random_uuid()::text),1,4));
  exit when not exists(select 1 from app_private.join_codes where code=v_code)
        and not exists(select 1 from app_private.partner_invites where code=v_code);
 end loop;
 insert into app_private.join_codes(club_id,code,email,full_name,reason,created_by,expires_at)
  values(v_club,v_code,v_email,trim(app_private.required(p_data,'full_name',160)),trim(app_private.required(p_data,'reason',300)),u,now()+make_interval(days=>v_days))
  returning id into v_id;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,v_id,'create_join_code',jsonb_build_object('email',v_email,'reason',p_data->>'reason'),v_club);
 return jsonb_build_object('id',v_id,'code',v_code);
end $$;

create function public.revoke_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes;
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can cancel join codes.' using errcode='42501'; end if;
 select * into j from app_private.join_codes where id=(p_data->>'id')::uuid and club_id=app_private.current_club() for update;
 if not found then raise exception 'That code no longer exists.'; end if;
 if j.used_by is not null then raise exception 'This code was already used.'; end if;
 update app_private.join_codes set revoked_at=now() where id=j.id;
 return jsonb_build_object('id',j.id);
end $$;

-- Create account, before the account exists: is this a usable code for this email? Join codes must match the email;
-- interclub invite codes (migration 044) work with any email. Says nothing else about the code or the club's data.
create function public.check_signup_code(p_code text,p_email text) returns jsonb language sql stable security definer set search_path='' as $$
 with c as (select upper(substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),1,4)||'-'||substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),5,4)) code)
 select coalesce(
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.join_codes j join app_private.clubs cl on cl.id=j.club_id, c
    where j.code=c.code and j.used_by is null and j.revoked_at is null and j.expires_at>now() and j.email=lower(trim(p_email))),
  (select jsonb_build_object('kind','invite','club',cl.name) from app_private.partner_invites v join app_private.clubs cl on cl.id=v.club_id, c
    where v.code=c.code and v.claimed_by is null and v.revoked_at is null and v.expires_at>now()),
  jsonb_build_object('kind',null)) $$;

-- After signing up: the code places the account in its club.
create function public.claim_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes; v_email text; v_code text;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g')); v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 select * into j from app_private.join_codes where code=v_code for update;
 if not found or j.revoked_at is not null or j.expires_at<now() or j.used_by is not null or j.email<>v_email then
  raise exception 'That join code isn''t valid for %. Check it with the club that sent it.',v_email; end if;
 update app_private.join_codes set used_by=u, used_at=now() where id=j.id;
 update app_private.profiles set club_id=j.club_id, full_name=coalesce(nullif(full_name,''),j.full_name), updated_at=now() where id=u;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,j.id,'claim_join_code',jsonb_build_object('email',v_email),j.club_id);
 return jsonb_build_object('club_id',j.club_id);
end $$;
revoke all on function public.create_join_code(jsonb),public.revoke_join_code(jsonb),public.claim_join_code(jsonb),public.check_signup_code(text,text) from public;
grant execute on function public.create_join_code(jsonb),public.revoke_join_code(jsonb),public.claim_join_code(jsonb) to authenticated;
grant execute on function public.check_signup_code(text,text) to anon,authenticated;

-- The domain guard (migration 051) accepts an account its club let in with a join code.
create or replace function app_private.profile_club_by_domain() returns trigger language plpgsql security definer set search_path='' as $$
declare v_match uuid:=app_private.club_for_email(new.email); v_domain text;
begin
 if tg_op='INSERT' and v_match is not null then new.club_id:=v_match; end if;
 if new.club_id is not null and new.account_type is distinct from 'partner'
    and not exists(select 1 from app_private.join_codes where used_by=new.id and club_id=new.club_id) then
  select email_domain into v_domain from app_private.clubs where id=new.club_id;
  if v_domain is not null and v_match is distinct from new.club_id then
   raise exception '% can''t join %: each club is only for its own school''s email (@%).', new.email,
    (select name from app_private.clubs where id=new.club_id), v_domain using errcode='42501';
  end if;
  if v_domain is null and v_match is not null and v_match<>new.club_id then
   raise exception '% belongs to %.', new.email, (select name from app_private.clubs where id=v_match) using errcode='42501';
  end if;
 end if;
 return new;
end $$;

-- Officers see their club's codes in the workspace (by the read policy above).
do $$ declare def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:='''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q));';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'club_workspace() is not the expected version.'; end if;
 execute replace(def,snippet,'''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q),
  ''join_codes'',(select coalesce(jsonb_agg(j order by j.created_at desc),''[]'') from app_private.join_codes j));');
end $$;
grant execute on all functions in schema app_private, public to club_api;

commit;
