-- Equipment photos. A photo is an ordinary club document (module 10, requirement "Photo") attached to an
-- equipment item, so it reuses the upload, storage and audit path every other attachment uses. Only the
-- Equipment desk can attach one; every active member can view it when browsing gear.
begin;

create or replace function app_private.document_access(p_module integer,p_record uuid,p_write boolean default false) returns boolean language plpgsql stable security definer set search_path='' as $$
 declare u uuid:=auth.uid();begin
 if not app_private.active_user() then return false;end if;
 case p_module
 when 2 then return exists(select 1 from app_private.applications where id=p_record and ((user_id=u and (not p_write or status in ('draft','incomplete','returned_for_correction'))) or (not p_write and app_private.can('membership'))));
 when 1 then return exists(select 1 from app_private.renewals where id=p_record and ((user_id=u and (not p_write or status in ('draft','returned_for_correction'))) or (not p_write and app_private.can('membership'))));
 when 9 then return exists(select 1 from app_private.candidates c join app_private.elections e on e.id=c.election_id where c.id=p_record and ((c.user_id=u and (not p_write or (e.status='announced' and now()<=e.candidacy_closes))) or app_private.can('elections')));
 when 11 then return exists(select 1 from app_private.training_records where id=p_record and ((user_id=u and (not p_write or validation in ('personal','returned_for_correction'))) or app_private.can('training')));
 when 12 then return exists(select 1 from app_private.financial_records where id=p_record and ((user_id=u and (not p_write or status in ('draft','incomplete','returned_for_correction'))) or (not p_write and (app_private.can('finance') or (kind<>'payment' and app_private.can('president'))))));
 when 15 then return exists(select 1 from app_private.delegates d join app_private.interclub_events e on e.event_id=d.event_id where d.id=p_record and ((app_private.event_guest(d.event_id,d.partner_id) and (not p_write or (e.status='preparation' and now()<=e.registration_closes and d.status in ('pending','incomplete')))) or (app_private.event_staff(d.event_id) and (not p_write or e.status<>'archived'))))
 or exists(select 1 from app_private.interclub_events e where e.event_id=p_record and ((app_private.event_staff(e.event_id) and (not p_write or e.status<>'archived')) or (not p_write and app_private.event_guest(e.event_id))));
 -- Equipment photos: the Equipment desk adds them; any active member can see them in the catalog.
 when 10 then return exists(select 1 from app_private.equipment where id=p_record) and (app_private.can('equipment')
  or (not p_write and exists(select 1 from app_private.memberships where user_id=u and status='Active')));
 else return false;end case;end
$$;

commit;
