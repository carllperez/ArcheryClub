-- 062: an Attendance desk (23 Nov, the developer's decision): who came to what, events and training alike, kept apart
-- from Finance. Attendance itself is unchanged (QR or manual check-in per activity, migration 003); this adds:
--  * events.kind: Training, Event or Meeting, chosen when an activity is created or edited, so practices can be told
--    apart from other activities;
--  * attendance_overview(): every started activity of the caller's club with its roster (registered or recorded,
--    present / absent / excused / no record, how it was taken and by whom) and the Active members with the date they
--    joined. Only Administrators, Activity officers, Training officers and Presidents get it; anyone else gets null.
--    Members' own records are unchanged.

alter table app_private.events add column if not exists kind text not null default 'event' check (kind in ('training','event','meeting'));

-- save_event takes an optional "kind": club_command sets it on the saved activity, so the original command stays as is.
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_feature text:=app_private.feature_for_action(p_action); v_result jsonb;
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
 if p_action='save_event' and nullif(p_data->>'kind','') is not null and p_data->>'kind' not in ('training','event','meeting') then
  raise exception 'Pick Training, Event or Meeting for the activity type.'; end if;
 v_result:=app_private.club_command_before_features(p_action,p_data);
 if p_action='save_event' and nullif(p_data->>'kind','') is not null and nullif(v_result->>'id','') is not null then
  update app_private.events set kind=p_data->>'kind' where id=(v_result->>'id')::uuid and club_id is not distinct from app_private.current_club();
 end if;
 return v_result;
end $$;

create function app_private.attendance_overview() returns jsonb language sql stable security definer set search_path='' as $$
 select case when not (app_private.can('administration') or app_private.can('activities') or app_private.can('training') or app_private.can('president'))
  then null else jsonb_build_object(
  'members',(select coalesce(jsonb_agg(jsonb_build_object('user_id',ms.user_id,'name',coalesce(nullif(p.full_name,''),p.email),'since',ms.approved_at)
     order by coalesce(nullif(p.full_name,''),p.email)),'[]')
   from app_private.memberships ms join app_private.profiles p on p.id=ms.user_id
   where ms.status='Active' and p.club_id is not distinct from app_private.current_club()),
  'events',(select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'title',e.title,'kind',e.kind,'starts_at',e.starts_at,'ends_at',e.ends_at,'venue',e.venue,
     'roster',(select coalesce(jsonb_agg(jsonb_build_object('user_id',x.uid,'name',x.name,'registered',x.registered,'status',x.status,'method',x.method,'by',x.by_name)
         order by x.name),'[]')
       from (select u.uid, coalesce(nullif(p.full_name,''),p.email) as name,
              exists(select 1 from app_private.registrations r where r.event_id=e.id and r.user_id=u.uid) as registered,
              a.status, a.method, (select full_name from app_private.profiles where id=a.encoded_by) as by_name
             from (select user_id as uid from app_private.registrations where event_id=e.id
                   union select user_id from app_private.attendance where event_id=e.id) u
             join app_private.profiles p on p.id=u.uid
             left join app_private.attendance a on a.event_id=e.id and a.user_id=u.uid) x))
    order by e.starts_at desc),'[]')
   from app_private.events e
   where e.club_id is not distinct from app_private.current_club() and e.published and not e.archived and e.starts_at<=now()))
 end
$$;
grant execute on function app_private.attendance_overview() to authenticated;

do $do$
declare d text; v_old text:='''my_interclub'',app_private.my_interclub_entries(),';
begin
 d:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 if (length(d)-length(replace(d,v_old,'')))/length(v_old)<>1 then
  raise exception 'club_workspace is not the expected version (my_interclub not found exactly once); nothing changed.';
 end if;
 execute replace(d,v_old,v_old||$$'attendance_overview',app_private.attendance_overview(),$$);
end $do$;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
