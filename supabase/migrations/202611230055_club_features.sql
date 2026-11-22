-- 055: each club turns features on and off for its own members and officers (23 Nov, the developer's decision).
-- Switchable: activities (with announcements), equipment and borrowing, training, elections, interclub, reports.
-- Membership, payments and administration always stay on: without them nobody can join, pay or manage the club.
-- Off means off everywhere in that club:
--  * app_private.can() answers no for that desk, so officer screens, their row policies and every desk-checked
--    function refuse, even for people whose role still lists the desk (roles are shared by every club);
--  * member actions sent through club_command for that feature are refused (borrow, register, log training, vote...);
--  * the app hides the feature's tabs, tiles and desks (it reads settings.disabled_features).
-- Records are kept: turning a feature back on shows everything as it was.

alter table app_private.settings add column if not exists disabled_features text[] not null default '{}';
alter table app_private.settings add constraint settings_disabled_features_known
 check (disabled_features <@ array['activities','equipment','training','elections','interclub','reports']);

-- Is this feature turned off in the caller's club? (Owned by postgres: reads settings whatever the caller's policies.)
create function app_private.feature_off(p_feature text) returns boolean language sql stable security definer set search_path='' as $$
 select p_feature=any(coalesce((select s.disabled_features from app_private.settings s where s.club_id is not distinct from app_private.current_club() limit 1),'{}'::text[]))
$$;
revoke all on function app_private.feature_off(text) from public,anon;
grant execute on function app_private.feature_off(text) to authenticated;

-- Same as migration 001 plus the club's switches.
create or replace function app_private.can(p_permission text) returns boolean language sql stable security definer set search_path='' as $$
 select app_private.active_user() and not app_private.feature_off(p_permission) and exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at and p_permission=any(r.permissions))
$$;

-- Which feature a club_command action belongs to (null: always available).
create function app_private.feature_for_action(p_action text) returns text language sql immutable set search_path='' as $$
 select case
  when p_action in ('save_event','publish_event','archive_event','register_event','cancel_registration','open_attendance','check_in',
                    'record_attendance','request_attendance_correction','review_attendance_correction','publish_announcement','update_announcement') then 'activities'
  when p_action in ('save_equipment','request_borrowing','review_borrowing','cancel_borrowing') then 'equipment'
  when p_action in ('save_training','save_personal_training','submit_training','validate_training','import_training',
                    'request_training_correction','review_training_correction') then 'training'
  when p_action in ('save_term','record_residency','create_election','update_election','file_candidacy','nominate_candidate','confirm_candidate',
                    'confirm_voter','cast_ballot','set_election_status','record_external_results','confirm_results','record_leadership') then 'elections'
  when p_action in ('save_partner','share_interclub_event','invite_partner','revoke_event_access','save_delegate','review_delegate','substitute_delegate',
                    'assign_delegate','raise_concern','resolve_concern','record_result','verify_result','update_shared_event','archive_interclub') then 'interclub'
  when p_action='save_goal' then 'reports'
 end
$$;

-- club_command gains one more outer check, the same way earlier migrations wrapped it.
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_features;
revoke all on function app_private.club_command_before_features(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_feature text:=app_private.feature_for_action(p_action);
begin
 if v_feature is not null and app_private.feature_off(v_feature) then
  raise exception 'Your club has turned off %.', case v_feature when 'activities' then 'activities' when 'equipment' then 'equipment borrowing'
   when 'training' then 'training' when 'elections' then 'elections' when 'interclub' then 'partner club events' else 'reports and goals' end
   using errcode='42501';
 end if;
 return app_private.club_command_before_features(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

-- The administrator sets the switches for their own club only.
create function public.set_club_features(p_disabled text[]) returns jsonb language plpgsql security definer set search_path='' as $$
declare v_old text[]; v_new text[];
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 select coalesce(array_agg(distinct x order by x),'{}') into v_new from unnest(coalesce(p_disabled,'{}')) x;
 if not v_new <@ array['activities','equipment','training','elections','interclub','reports'] then
  raise exception 'Membership, payments and administration can''t be turned off.'; end if;
 select disabled_features into v_old from app_private.settings where club_id is not distinct from app_private.current_club();
 if not found then raise exception 'Your club has no settings yet. Open Club setup first.'; end if;
 update app_private.settings set disabled_features=v_new, updated_at=now() where club_id is not distinct from app_private.current_club();
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(auth.uid(),14,null,'set_club_features',jsonb_build_object('off',to_jsonb(v_new),'before',to_jsonb(v_old)),app_private.current_club());
 return jsonb_build_object('off',to_jsonb(v_new));
end $$;
revoke all on function public.set_club_features(text[]) from public,anon;
grant execute on function public.set_club_features(text[]) to authenticated;

grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
