begin;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action in ('save_profile','save_application','submit_application','review_application','request_renewal','review_renewal','update_membership') then
  return app_private.membership_command(p_action,p_data);
 elsif p_action in ('configure_club','define_role','assign_role','retire_role','set_account_status') then
  return app_private.admin_command(p_action,p_data);
 elsif p_action='acknowledge_notification' then
  update app_private.notifications set acknowledged_at=now() where id=(p_data->>'id')::uuid and user_id=auth.uid();
  return '{}';
 end if;
 raise exception 'Unknown action.';
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
-- Security-invoker: RLS applies to every source query, including officer views.
create function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
declare result jsonb; begin
 if not app_private.active_user() then raise exception 'Sign in with a verified, enabled account.' using errcode='42501'; end if;
 select jsonb_build_object(
 'user_id',auth.uid(),
 'permissions',(select coalesce(jsonb_agg(distinct p),'[]') from app_private.role_assignments a join app_private.roles r on r.id=a.role_id cross join unnest(r.permissions) p where a.user_id=auth.uid() and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at),
 'settings',(select to_jsonb(s) from app_private.settings s),
 'profiles',(select coalesce(jsonb_agg(p),'[]') from app_private.profiles p),
 'memberships',(select coalesce(jsonb_agg(m),'[]') from app_private.memberships m),
 'applications',(select coalesce(jsonb_agg(a),'[]') from app_private.applications a),
 'renewals',(select coalesce(jsonb_agg(r),'[]') from app_private.renewals r),
 'roles',(select coalesce(jsonb_agg(r),'[]') from app_private.roles r),
 'role_assignments',(select coalesce(jsonb_agg(a),'[]') from app_private.role_assignments a),
 'documents',(select coalesce(jsonb_agg(d),'[]') from app_private.documents d),
 'notifications',(select coalesce(jsonb_agg(n order by notified_at desc),'[]') from app_private.notifications n),
 'audit_log',(select coalesce(jsonb_agg(l order by created_at desc),'[]') from (select * from app_private.audit_log order by created_at desc limit 200) l)
 ) into result;
 return result;
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;
commit;
