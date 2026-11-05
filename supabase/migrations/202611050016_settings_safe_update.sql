-- Supabase safe-update enforcement requires an explicit singleton predicate.
-- Preserve role guards, serialization and the last-administrator invariant.
begin;
create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; begin
 perform app_private.require_permission('administration');
 -- Serialize access changes to protect against two concurrent last-admin retirements.
 perform pg_advisory_xact_lock(14001);
 case p_action
 when 'configure_club' then
  update app_private.settings set club_name=app_private.required(p_data,'club_name',160),
   application_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'application_requirements','[]'))),
   renewal_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'renewal_requirements','[]'))),
   membership_categories=array(select jsonb_array_elements_text(coalesce(p_data->'membership_categories','["Regular member"]'))),
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 when 'retire_role' then
  update app_private.role_assignments set retired_at=now(),reason=reason||E'\nRetired: '||app_private.required(p_data,'reason') where id=(p_data->>'id')::uuid returning id into v_id;
 when 'set_account_status' then
  v_id:=(p_data->>'user_id')::uuid; perform app_private.required(p_data,'reason');
  update app_private.profiles set enabled=(p_data->>'enabled')::boolean,updated_at=now() where id=v_id;
 else raise exception 'Unknown administrative action.';
 end case;
 if not exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id join app_private.profiles p on p.id=a.user_id
 where p.enabled and 'administration'=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at) then raise exception 'Keep at least one active administrator.'; end if;
 perform app_private.note(14,v_id,p_action,p_data); return jsonb_build_object('id',v_id); end
$$;

commit;
