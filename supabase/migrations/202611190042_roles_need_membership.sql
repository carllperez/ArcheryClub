-- Every role, Administrator included, needs an Active membership (testing round, 19 Nov: "no exceptions").
-- Accounts with no membership or a pending fee can't be assigned a role or have a term extended.
-- Shortening a term and retiring a role are always allowed, so a mistaken role can still be removed.
-- Same as migration 040's admin_command otherwise.
begin;

create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; ra app_private.role_assignments; v_ends timestamptz; begin
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
  if not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>'user_id')::uuid and m.status='Active') then
   raise exception 'Roles, including Administrator, can only go to Active members. This account has no Active membership.';
  end if;
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 -- Change an existing appointment's title or end date (extend or shorten a term) without making a new one.
 -- The previous values go into the audit log with the reason, so turnover history stays complete.
 when 'update_role_term' then
  select * into ra from app_private.role_assignments where id=(p_data->>'id')::uuid and retired_at is null for update;
  if not found then raise exception 'That role is no longer active. Assign it again instead.'; end if;
  v_ends:=(app_private.required(p_data,'ends_at'))::timestamptz;
  if v_ends<=ra.starts_at then raise exception 'The term must end after it starts (%).', to_char(ra.starts_at at time zone 'Asia/Manila','Mon DD, YYYY'); end if;
  if v_ends>ra.ends_at and not exists(select 1 from app_private.memberships m where m.user_id=ra.user_id and m.status='Active') then
   raise exception 'Only Active members can have a term extended. This account has no Active membership.';
  end if;
  update app_private.role_assignments set ends_at=v_ends,position=app_private.required(p_data,'position',160),
   reason=reason||E'\nChanged: '||app_private.required(p_data,'reason') where id=ra.id returning id into v_id;
  p_data:=p_data||jsonb_build_object('previous',jsonb_build_object('ends_at',ra.ends_at,'position',ra.position));
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
