-- 056: one shared join code per club (23 Nov, the developer's decision). Per-person codes (053) needed the officer to
-- know a name and email before the person signed up. Now each club has one reusable code it posts where its alumni
-- are (group chat, Facebook page). Anyone with a personal email signs up with it, lands in that club and applies as
-- usual: the membership application is still where the club decides. Officers can change the code if it leaks.
-- A school email of another club can't use it (each club is only for its own school). Per-person codes still work.
-- Each use is recorded as a used row in join_codes, so officers see who joined with it and the domain guard (053)
-- accepts the account as one its club let in.

create function app_private.new_signup_code() returns text language plpgsql volatile security definer set search_path='' as $$
declare v_code text;
begin
 loop
  v_code:=upper(substr(md5(gen_random_uuid()::text),1,4)||'-'||substr(md5(gen_random_uuid()::text),1,4));
  exit when not exists(select 1 from app_private.join_codes where code=v_code or code like v_code||':%')
        and not exists(select 1 from app_private.partner_invites where code=v_code)
        and not exists(select 1 from app_private.settings where join_code=v_code);
 end loop;
 return v_code;
end $$;
revoke all on function app_private.new_signup_code() from public,anon,authenticated;

alter table app_private.settings add column if not exists join_code text unique;
update app_private.settings set join_code=app_private.new_signup_code() where join_code is null;
alter table app_private.settings alter column join_code set default app_private.new_signup_code();

-- Officer: replace the club's code; the old one stops working at once.
create function public.reset_club_join_code() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_code text:=app_private.new_signup_code(); v_club uuid:=app_private.current_club();
begin
 if not (app_private.can('membership') or app_private.can('administration')) then
  raise exception 'Only the Membership officer or an Administrator can change the join code.' using errcode='42501'; end if;
 update app_private.settings set join_code=v_code, updated_at=now() where club_id is not distinct from v_club;
 if not found then raise exception 'Your club has no settings yet. Open Club setup first.'; end if;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id) values(u,1,null,'reset_club_join_code','{}',v_club);
 return jsonb_build_object('code',v_code);
end $$;
revoke all on function public.reset_club_join_code() from public,anon;
grant execute on function public.reset_club_join_code() to authenticated;

-- Create account, before the account exists. Adds the club code: any personal email, or that club's own school.
create or replace function public.check_signup_code(p_code text,p_email text) returns jsonb language sql stable security definer set search_path='' as $$
 with c as (select upper(substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),1,4)||'-'||substr(regexp_replace(coalesce(p_code,''),'[^A-Za-z0-9]','','g'),5,4)) code)
 select coalesce(
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.join_codes j join app_private.clubs cl on cl.id=j.club_id, c
    where j.code=c.code and j.used_by is null and j.revoked_at is null and j.expires_at>now() and j.email=lower(trim(p_email))),
  (select jsonb_build_object('kind','join','club',cl.name) from app_private.settings s join app_private.clubs cl on cl.id=s.club_id, c
    where s.join_code=c.code and coalesce(app_private.club_for_email(p_email),s.club_id)=s.club_id),
  (select jsonb_build_object('kind','invite','club',cl.name) from app_private.partner_invites v join app_private.clubs cl on cl.id=v.club_id, c
    where v.code=c.code and v.claimed_by is null and v.revoked_at is null and v.expires_at>now()),
  jsonb_build_object('kind',null)) $$;

-- After signing up: a personal code first (it carries the name the officer typed), then the club code.
create or replace function public.claim_join_code(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); j app_private.join_codes; v_email text; v_code text; v_club uuid; v_name text; v_id uuid;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_code:=upper(regexp_replace(coalesce(p_data->>'code',''),'[^A-Za-z0-9]','','g')); v_code:=substr(v_code,1,4)||'-'||substr(v_code,5,4);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 select * into j from app_private.join_codes where code=v_code for update;
 if found and j.revoked_at is null and j.expires_at>=now() and j.used_by is null and j.email=v_email then
  update app_private.join_codes set used_by=u, used_at=now() where id=j.id;
  update app_private.profiles set club_id=j.club_id, full_name=coalesce(nullif(full_name,''),j.full_name), updated_at=now() where id=u;
  insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
   values(u,1,j.id,'claim_join_code',jsonb_build_object('email',v_email),j.club_id);
  return jsonb_build_object('club_id',j.club_id);
 end if;
 select club_id into v_club from app_private.settings where join_code=v_code;
 if v_club is null then raise exception 'That join code isn''t valid for %. Check it with the club that sent it.',v_email; end if;
 if app_private.club_for_email(v_email) is distinct from v_club and app_private.club_for_email(v_email) is not null then
  raise exception '% belongs to %. Each club is only for its own school.',v_email,(select name from app_private.clubs where id=app_private.club_for_email(v_email)); end if;
 select coalesce(nullif(trim(full_name),''),split_part(v_email,'@',1)) into v_name from app_private.profiles where id=u;
 if length(v_name)<2 then v_name:=v_name||' (personal email)'; end if;
 insert into app_private.join_codes(club_id,code,email,full_name,reason,created_by,used_by,used_at)
  values(v_club,v_code||':'||u::text,v_email,left(v_name,160),'Signed up with the club code',u,u,now()) returning id into v_id;
 update app_private.profiles set club_id=v_club, updated_at=now() where id=u;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,1,v_id,'claim_club_join_code',jsonb_build_object('email',v_email),v_club);
 return jsonb_build_object('club_id',v_club);
end $$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
