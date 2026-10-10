begin;
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action in ('save_profile','save_application','submit_application','review_application','save_renewal','request_renewal','review_renewal','update_membership') then
  return app_private.membership_command(p_action,p_data);
 elsif p_action in ('configure_club','define_role','assign_role','retire_role','set_account_status') then
  return app_private.admin_command(p_action,p_data);
 elsif p_action in ('save_event','publish_event','archive_event','publish_announcement','register_event','open_attendance','check_in','record_attendance','request_attendance_correction','review_attendance_correction') then
  return app_private.activity_command(p_action,p_data);
 elsif p_action in ('save_term','record_residency','create_election','file_candidacy','nominate_candidate','confirm_candidate','confirm_voter','cast_ballot','set_election_status','record_external_results','confirm_results','record_leadership') then
  return app_private.election_command(p_action,p_data);
 elsif p_action in ('save_equipment','request_borrowing','review_borrowing') then
  return app_private.equipment_command(p_action,p_data);
 elsif p_action in ('save_training','save_personal_training','submit_training','validate_training','import_training','request_training_correction','review_training_correction') then
  return app_private.training_command(p_action,p_data);
 elsif p_action in ('save_financial_request','submit_financial_request','approve_funds','verify_financial_request','assess_fee','link_fee_payment') then
  return app_private.finance_command(p_action,p_data);
 elsif p_action in ('save_partner','share_interclub_event','invite_partner','revoke_event_access','save_delegate','review_delegate','substitute_delegate','assign_delegate','raise_concern','resolve_concern','record_result','verify_result','update_shared_event','archive_interclub') then
  return app_private.interclub_command(p_action,p_data);
 elsif p_action='save_goal' then
  perform app_private.require_permission('reports');
  if p_data->>'metric' not in ('active_members','applicant_conversion','attendance_rate','equipment_readiness','training_average','collection_completion') then raise exception 'Unknown goal metric.';end if;
  insert into app_private.goals(metric,title,target,starts_on,ends_on,created_by) values(p_data->>'metric',app_private.required(p_data,'title',200),(p_data->>'target')::numeric,(p_data->>'starts_on')::date,(p_data->>'ends_on')::date,auth.uid());return '{}';
 elsif p_action='acknowledge_notification' then
  update app_private.notifications set acknowledged_at=now() where id=(p_data->>'id')::uuid and user_id=auth.uid();
  return '{}';
 end if;
 raise exception 'Unknown action.';
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
-- Full election rows include unconfirmed external tallies. Never expose them through SELECT.
revoke select on app_private.elections from authenticated;
create function app_private.election_cards() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(to_jsonb(e)-'external_results'-'external_turnout'-'confirmation'-'confirmed_by'),'[]')
 from app_private.elections e where app_private.can('elections') or (app_private.member() and e.status<>'draft')
$$;
-- Only names and membership standing needed to select a person; no contact or student details.
create function app_private.member_directory() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object('user_id',p.id,'full_name',p.full_name,'status',m.status)),'[]')
 from app_private.profiles p left join app_private.memberships m on m.user_id=p.id
 where app_private.active_user() and (p.id=auth.uid() or (app_private.member() and m.status='Active')
 or (m.user_id is not null and (app_private.can('membership') or app_private.can('activities') or app_private.can('elections') or app_private.can('training') or app_private.can('finance') or app_private.can('equipment')))
 or (app_private.member() and exists(select 1 from app_private.candidates c join app_private.elections e on e.id=c.election_id where c.user_id=p.id and c.confirmed and e.status<>'draft')))
$$;
create function app_private.shared_participants() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object('id',d.id,'event_id',d.event_id,'full_name',d.full_name,'affiliation',d.affiliation,'category',d.category,'status',d.status)),'[]')
 from app_private.delegates d where d.status in ('eligible','checked_in') and (app_private.event_staff(d.event_id) or app_private.event_guest(d.event_id))
$$;
revoke all on function app_private.election_cards(),app_private.member_directory(),app_private.shared_participants() from public,anon,authenticated;
grant execute on function app_private.election_cards(),app_private.member_directory(),app_private.shared_participants() to authenticated;
create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
declare result jsonb; t text; rows jsonb; begin
 if not app_private.active_user() then raise exception 'Sign in with a verified, enabled account.' using errcode='42501';end if;
 result:=jsonb_build_object('user_id',auth.uid(),'permissions',(select coalesce(jsonb_agg(distinct p),'[]') from app_private.role_assignments a join app_private.roles r on r.id=a.role_id cross join unnest(r.permissions) p where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at));
 foreach t in array array['settings','profiles','memberships','applications','renewals','roles','role_assignments','documents','notifications','audit_log','events','announcements','registrations','attendance_windows','attendance','corrections','academic_terms','member_terms','elections','candidates','eligible_voters','voter_participation','leadership_records','equipment','borrowings','equipment_history','training_records','financial_records','financial_approvals','fee_assessments','partner_clubs','interclub_events','event_access','delegates','interclub_assignments','interclub_concerns','interclub_results','interclub_archives','goals'] loop
  if t='elections' then rows:=app_private.election_cards();
  else execute format('select coalesce(jsonb_agg(x),''[]''::jsonb) from (select * from app_private.%I) x',t) into rows;end if;
  result:=result||jsonb_build_object(t,rows);
 end loop;
 return result||jsonb_build_object('member_directory',app_private.member_directory(),'shared_participants',app_private.shared_participants());
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;
commit;
