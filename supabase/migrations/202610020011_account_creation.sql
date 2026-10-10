begin;
create table app_private.account_requests (
 id uuid primary key default gen_random_uuid(),actor uuid not null references app_private.profiles,
 email text not null,full_name text not null,reason text not null,
 created_at timestamptz not null default now(),completed_at timestamptz,user_id uuid references auth.users
);
alter table app_private.account_requests enable row level security;
revoke all on app_private.account_requests from public,anon,authenticated;
grant select on app_private.account_requests to authenticated;
create policy account_request_read on app_private.account_requests for select to authenticated using(app_private.can('administration'));
create function public.request_account_creation(p_email text,p_full_name text,p_reason text) returns uuid language plpgsql security definer set search_path='' as $$
declare v_id uuid;begin
 perform app_private.require_permission('administration');
 if length(p_email)>254 or p_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then raise exception 'Enter a valid email address.';end if;
 if length(btrim(p_full_name)) not between 1 and 160 or length(btrim(p_reason)) not between 1 and 4000 then raise exception 'Full name and reason are required.';end if;
 insert into app_private.account_requests(actor,email,full_name,reason) values(auth.uid(),lower(btrim(p_email)),btrim(p_full_name),btrim(p_reason)) returning id into v_id;
 perform app_private.note(14,v_id,'request_account_creation',jsonb_build_object('email',lower(btrim(p_email))));return v_id;
end $$;
create function public.complete_account_creation(p_request uuid,p_user uuid) returns void language plpgsql security definer set search_path='' as $$
declare r app_private.account_requests;begin
 select * into r from app_private.account_requests where id=p_request and completed_at is null for update;
 if not found or not exists(select 1 from auth.users where id=p_user and lower(email)=r.email) then raise exception 'Account creation request does not match.';end if;
 insert into app_private.profiles(id,full_name) values(p_user,r.full_name) on conflict do nothing;
 update app_private.account_requests set user_id=p_user,completed_at=now() where id=r.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail) values(r.actor,14,p_user,'account_invited',jsonb_build_object('request_id',r.id));
end $$;
revoke all on function public.request_account_creation(text,text,text),public.complete_account_creation(uuid,uuid) from public,anon,authenticated;
grant execute on function public.request_account_creation(text,text,text) to authenticated;
grant execute on function public.complete_account_creation(uuid,uuid) to service_role;
commit;
