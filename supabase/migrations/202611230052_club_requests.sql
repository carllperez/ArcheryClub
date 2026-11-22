-- A student whose school has no club yet asks for one (23 Nov). The request is for their own email's domain only
-- (an @feu.edu.ph account can request FEU's club, nobody else's), never a personal email provider. The platform
-- administrator approves (the club is created with the requester as its first administrator, migration 051's rules
-- apply) or declines with a reason. Both sides get a notice.
begin;

create table app_private.club_requests (
 id uuid primary key default gen_random_uuid(),
 user_id uuid not null references app_private.profiles,
 -- Name and email at request time: the platform administrator can't read a profile outside their own club.
 requester text not null default '' check(length(requester)<=320),
 email_domain text not null check(email_domain ~ '^[a-z0-9-]+(\.[a-z0-9-]+)+$'),
 name text not null check(length(trim(name)) between 2 and 160),
 short_name text not null default '' check(length(short_name)<=40),
 school text not null check(length(trim(school)) between 2 and 160),
 theme text not null default 'green' check(theme in ('green','red','blue')),
 note text not null default '' check(length(note)<=500),
 status text not null default 'pending' check(status in ('pending','approved','declined')),
 remark text not null default '' check(length(remark)<=500),
 club_id uuid references app_private.clubs,
 created_at timestamptz not null default now(),
 decided_at timestamptz, decided_by uuid
);
-- One open request per school at a time.
create unique index club_requests_pending_domain on app_private.club_requests(email_domain) where status='pending';
alter table app_private.club_requests enable row level security;
create policy club_requests_read on app_private.club_requests for select to authenticated
 using (user_id=auth.uid() or app_private.platform_admin());
create policy club_api_all on app_private.club_requests for all to club_api using (true) with check (true);
grant select on app_private.club_requests to authenticated;
grant select,insert,update,delete on app_private.club_requests to club_api;

create function public.request_club(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_email text; v_domain text; v_id uuid; a uuid;
begin
 select lower(email) into v_email from auth.users where id=u;
 v_domain:=split_part(v_email,'@',2);
 if (select club_id from app_private.profiles where id=u) is not null then raise exception 'Your account already belongs to a club.'; end if;
 if app_private.club_for_email(v_email) is not null then raise exception 'Your school already has a club. Sign out and back in to join it.'; end if;
 if v_domain in ('gmail.com','yahoo.com','ymail.com','outlook.com','hotmail.com','live.com','icloud.com','me.com','proton.me','protonmail.com','aol.com','gmx.com') then
  raise exception 'Use your school email to request a club. A personal email can''t stand for a school.'; end if;
 if exists(select 1 from app_private.club_requests where email_domain=v_domain and status='pending') then
  raise exception 'A request for @% is already waiting for the platform administrator.',v_domain; end if;
 insert into app_private.club_requests(user_id,requester,email_domain,name,short_name,school,theme,note)
  values(u,coalesce(nullif((select full_name from app_private.profiles where id=u),'')||' · ','')||v_email,v_domain,trim(app_private.required(p_data,'name',160)),left(trim(coalesce(p_data->>'short_name','')),40),
   trim(app_private.required(p_data,'school',160)),coalesce(nullif(p_data->>'theme',''),'green'),left(trim(coalesce(p_data->>'note','')),500))
  returning id into v_id;
 for a in select id from app_private.profiles where platform_admin loop
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(a,14,v_id,'club_request',coalesce(nullif((select full_name from app_private.profiles where id=u),''),v_email)||' ('||v_email||') asked for a club: '||trim(p_data->>'name'),
   (select club_id from app_private.profiles where id=a));
 end loop;
 return jsonb_build_object('id',v_id);
end $$;

create function public.decide_club_request(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); r app_private.club_requests; v_club uuid; v_email text; v_remark text:=trim(coalesce(p_data->>'remark',''));
begin
 if not app_private.platform_admin() then raise exception 'Only the platform administrator decides club requests.' using errcode='42501'; end if;
 select * into r from app_private.club_requests where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'That request no longer exists.'; end if;
 if r.status<>'pending' then raise exception 'This request was already decided.'; end if;
 if p_data->>'decision'='approve' then
  select email into v_email from auth.users where id=r.user_id;
  v_club:=(public.create_club(jsonb_build_object('name',r.name,'short_name',r.short_name,'school',r.school,'email_domain',r.email_domain,
   'admin_email',v_email,'theme',r.theme))->>'id')::uuid;
  update app_private.club_requests set status='approved',club_id=v_club,remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club was approved: '||r.name||'. You are its first administrator. Open Club setup to set its rules.',v_club);
 elsif p_data->>'decision'='decline' then
  if v_remark='' then raise exception 'Say why the request is declined, so the student knows what to do.'; end if;
  update app_private.club_requests set status='declined',remark=v_remark,decided_at=now(),decided_by=u where id=r.id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(r.user_id,14,r.id,'club_request','Your club request wasn''t approved: '||v_remark,null);
 else raise exception 'Approve or decline the request.'; end if;
 return jsonb_build_object('id',r.id,'club_id',v_club);
end $$;
revoke all on function public.request_club(jsonb),public.decide_club_request(jsonb) from public,anon;
grant execute on function public.request_club(jsonb),public.decide_club_request(jsonb) to authenticated;

-- The requester and the platform administrator see the requests in the workspace (by the read policy above).
do $$ declare def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:='''clubs'',(select coalesce(jsonb_agg(c order by c.name),''[]'') from app_private.clubs c));';
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then raise exception 'club_workspace() is not the expected version.'; end if;
 execute replace(def,snippet,'''clubs'',(select coalesce(jsonb_agg(c order by c.name),''[]'') from app_private.clubs c),
  ''club_requests'',(select coalesce(jsonb_agg(q order by q.created_at desc),''[]'') from app_private.club_requests q));');
end $$;
grant execute on all functions in schema app_private, public to club_api;

commit;
