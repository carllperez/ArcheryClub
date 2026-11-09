-- Registration fixes found while walking the sign-up → application → approval story end to end.
--  * A replaced document no longer blocks approval. Uploading a new file for the same requirement
--    (allowed only while the application is editable) marks the earlier file superseded; officers
--    verify the current file, and the old one stays on record for the audit trail.
--  * Starting again after a rejection clears earlier verifications, so the next decision is made
--    on documents someone has checked for this application.
begin;
alter table app_private.documents add column superseded_by uuid references app_private.documents(id);

create function app_private.supersede_document() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.module in (1,2) then
  update app_private.documents set superseded_by=new.id
   where module=new.module and record_id=new.record_id and requirement=new.requirement and owner_id=new.owner_id
     and id<>new.id and superseded_by is null;
 end if;
 return new;
end $$;
create trigger supersede_document after insert on app_private.documents for each row execute function app_private.supersede_document();

create function app_private.reset_verifications_on_restart() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if old.status='rejected' and new.status='draft' then
  update app_private.documents set verified_by=null where module=2 and record_id=new.id;
 end if;
 return new;
end $$;
create trigger reset_verifications_on_restart after update on app_private.applications for each row execute function app_private.reset_verifications_on_restart();

-- Same dispatcher as 202611100019, with superseded files excluded from the approval check.
create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb; e app_private.events; v_user uuid; v_actor uuid;
begin
 if p_action in ('set_membership_fee','assess_fee_bulk','verify_document','restart_application','record_walk_in','confirm_receipt') then
  return app_private.hardening_command(p_action,p_data);
 end if;
 -- checks that must run before the original command
 if p_action='request_borrowing' then
  if exists(select 1 from app_private.borrowings where user_id=u and status='released' and ends_at<now()) then
   raise exception 'Return your overdue equipment before requesting more.'; end if;
  if exists(select 1 from app_private.borrowings x where x.equipment_id=(p_data->>'equipment_id')::uuid and x.status in ('approved','released')
     and x.id is distinct from nullif(p_data->>'id','')::uuid
     and tstzrange(x.starts_at,x.ends_at,'[)') && tstzrange((p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,'[)')) then
   raise exception 'This item is already booked for that time. Choose another time or item.'; end if;
 elsif p_action='review_application' and p_data->>'status'='approved' then
  if exists(select 1 from app_private.documents where module=2 and record_id=(p_data->>'id')::uuid and verified_by is null and superseded_by is null) then
   raise exception 'Verify every uploaded document before approving.'; end if;
 elsif p_action='confirm_results' then
  if exists(select 1 from app_private.audit_log where module=9 and record_id=(p_data->>'id')::uuid and actor=u
     and (action='record_external_results' or (action='set_election_status' and detail->>'status'='closed'))) then
   raise exception 'Another COMELEC member must confirm results you closed or entered.' using errcode='42501'; end if;
 elsif p_action='verify_result' then
  select actor into v_actor from app_private.audit_log where module=15 and record_id=(p_data->>'id')::uuid and action='record_result' order by created_at desc, id desc limit 1;
  if v_actor=u then raise exception 'Another officer must verify a result you recorded.' using errcode='42501'; end if;
 elsif p_action='archive_event' then
  select * into e from app_private.events where id=(p_data->>'id')::uuid;
 end if;

 result:=app_private.club_command_before_hardening(p_action,p_data);

 -- consequences that follow the original command
 if p_action='archive_event' and e.id is not null and not e.archived and e.ends_at>now() then
  for v_user in select user_id from app_private.registrations where event_id=e.id union select user_id from app_private.waitlist where event_id=e.id loop
   perform app_private.notify(v_user,3,e.id,'event_cancelled','Activity cancelled: '||e.title);
  end loop;
  update app_private.notifications set completed_at=now() where record_id=e.id and kind='event' and completed_at is null;
  delete from app_private.waitlist where event_id=e.id;
  update app_private.borrowings set status='cancelled',remark='Activity cancelled',version=version+1
   where event_id=e.id and status in ('pending','approved','returned_for_correction');
 elsif p_action='review_borrowing' and p_data->>'status'='returned' and p_data->>'condition'='damaged' then
  perform app_private.notify_role('finance',12,(p_data->>'id')::uuid,'Equipment returned damaged: decide on a repair fee');
 end if;
 return result;
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
revoke all on function app_private.supersede_document(),app_private.reset_verifications_on_restart() from public,anon,authenticated;
commit;
