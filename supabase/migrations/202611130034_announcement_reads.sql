-- Announcements remember who has read them. Read ones stay on Home and Activities, shown in gray,
-- instead of disappearing. Each person sees and writes only their own read marks.
begin;

create table app_private.announcement_reads (
 user_id uuid not null references app_private.profiles,
 announcement_id uuid not null references app_private.announcements on delete cascade,
 read_at timestamptz not null default now(),
 primary key(user_id,announcement_id)
);
alter table app_private.announcement_reads enable row level security;
revoke all on app_private.announcement_reads from public,anon,authenticated;
grant select on app_private.announcement_reads to authenticated;
create policy announcement_reads_own on app_private.announcement_reads for select to authenticated
 using(app_private.active_user() and user_id=auth.uid());

create function public.mark_announcements_read(p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); n integer;
begin
 if coalesce(array_length(p_ids,1),0) not between 1 and 200 then raise exception 'Mark 1 to 200 announcements at a time.'; end if;
 insert into app_private.announcement_reads(user_id,announcement_id)
  select u,a.id from app_private.announcements a where a.id=any(p_ids) on conflict do nothing;
 get diagnostics n=row_count;
 return jsonb_build_object('marked',n);
end $$;
revoke all on function public.mark_announcements_read(uuid[]) from public,anon;
grant execute on function public.mark_announcements_read(uuid[]) to authenticated;

create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p),
  'announcement_reads',(select coalesce(jsonb_agg(r),'[]') from app_private.announcement_reads r));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

commit;
