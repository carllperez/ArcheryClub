-- Testing round (19 Nov): the role picker showed every account as "not a member". Administrators can't read
-- memberships (only the owner and membership officers can, migration 001), so the app found no membership row.
-- Administrators and membership officers now get each account's standing (user_id + status only, nothing else
-- from the membership), which is what the role picker needs. Everyone else gets an empty list.
begin;

create or replace function app_private.member_standing() returns jsonb language sql stable security definer set search_path='' as $$
 select case when app_private.can('administration') or app_private.can('membership')
  then (select coalesce(jsonb_agg(jsonb_build_object('user_id',m.user_id,'status',m.status)),'[]') from app_private.memberships m)
  else '[]'::jsonb end
$$;
revoke all on function app_private.member_standing() from public,anon;
grant execute on function app_private.member_standing() to authenticated;

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
  'member_standing',app_private.member_standing());
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

commit;
