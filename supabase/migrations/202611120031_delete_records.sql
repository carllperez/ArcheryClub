-- Delete what can safely disappear; everything official keeps its archive/retire path instead.
-- Deletable:
--   training_round  the owner's personal or returned round; a coach may delete any round (reason required
--                   once validated, e.g. a duplicate import)
--   payment_draft   the owner's financial submission while it is still a draft
--   event_draft     an unpublished event nobody registered for (Events & attendance)
--   equipment       an item that was never borrowed (otherwise mark it Retired)
--   role            a club-made role nobody has ever held (Administration)
-- Each delete is written to the activity log with who, why and a copy of what was removed.
begin;

create function public.delete_record(p_kind text,p_id uuid,p_reason text default '') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_reason text:=trim(coalesce(p_reason,'')); v_row jsonb; v_module integer;
 t app_private.training_records; f app_private.financial_records; e app_private.events; q app_private.equipment;
begin
 case p_kind
 when 'training_round' then
  select * into t from app_private.training_records where id=p_id for update;
  if not found then raise exception 'This round no longer exists. Refresh and try again.'; end if;
  if t.user_id=u and t.validation in ('personal','returned_for_correction') then null;
  elsif app_private.can('training') then
   if t.validation='validated' and v_reason='' then raise exception 'Say why this official round is being deleted.'; end if;
  else raise exception 'Only your own personal rounds can be deleted.' using errcode='42501'; end if;
  v_row:=to_jsonb(t); v_module:=11;
  delete from app_private.corrections where module in (4,11) and record_id=p_id;
  delete from app_private.documents where module in (4,11) and record_id=p_id;
  delete from app_private.training_records where id=p_id;
 when 'payment_draft' then
  select * into f from app_private.financial_records where id=p_id for update;
  if not found then raise exception 'This submission no longer exists. Refresh and try again.'; end if;
  if f.user_id<>u or f.status<>'draft' then raise exception 'Only your own drafts can be deleted. Sent submissions stay on record.' using errcode='42501'; end if;
  v_row:=to_jsonb(f); v_module:=12;
  delete from app_private.documents where module=12 and record_id=p_id;
  delete from app_private.financial_records where id=p_id;
 when 'event_draft' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=p_id for update;
  if not found then raise exception 'This event no longer exists. Refresh and try again.'; end if;
  if e.published then raise exception 'Published events stay on record. Archive it instead.'; end if;
  v_row:=to_jsonb(e); v_module:=8;
  delete from app_private.events where id=p_id;
 when 'equipment' then
  perform app_private.require_permission('equipment');
  select * into q from app_private.equipment where id=p_id for update;
  if not found then raise exception 'This item no longer exists. Refresh and try again.'; end if;
  if exists(select 1 from app_private.borrowings where equipment_id=p_id) then
   raise exception 'This item has been borrowed before, so its history stays. Set its condition to Retired instead.'; end if;
  v_row:=to_jsonb(q); v_module:=10;
  delete from app_private.documents where module=10 and record_id=p_id;
  delete from app_private.equipment_history where equipment_id=p_id and borrowing_id is null;
  delete from app_private.equipment where id=p_id;
 else raise exception 'This kind of record cannot be deleted.';
 end case;
 delete from app_private.notifications where record_id=p_id;
 perform app_private.note(v_module,p_id,'delete_'||p_kind,jsonb_build_object('reason',v_reason,'deleted',v_row));
 return jsonb_build_object('deleted',p_id);
exception when foreign_key_violation then
 raise exception 'Other records still point to this one, so it stays. Archive or retire it instead.';
end $$;
revoke all on function public.delete_record(text,uuid,text) from public,anon;
grant execute on function public.delete_record(text,uuid,text) to authenticated;

-- Roles have text ids, so they get their own door.
create function public.delete_role(p_role_id text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_row jsonb;
begin
 perform app_private.require_user();
 perform app_private.require_permission('administration');
 perform pg_advisory_xact_lock(14001);
 select to_jsonb(r) into v_row from app_private.roles r where r.id=p_role_id;
 if v_row is null then raise exception 'This role no longer exists. Refresh and try again.'; end if;
 if v_row->'default_permissions' is not null and jsonb_typeof(v_row->'default_permissions')<>'null' then
  raise exception 'Built-in roles stay. Turn off its desks or reset it instead.'; end if;
 if exists(select 1 from app_private.role_assignments where role_id=p_role_id) then
  raise exception 'Someone has held this role, so its history stays. Turn off its desks instead.'; end if;
 delete from app_private.roles where id=p_role_id;
 perform app_private.note(14,null,'delete_role',jsonb_build_object('deleted',v_row));
 return jsonb_build_object('deleted',p_role_id);
end $$;
revoke all on function public.delete_role(text) from public,anon;
grant execute on function public.delete_role(text) to authenticated;

commit;
