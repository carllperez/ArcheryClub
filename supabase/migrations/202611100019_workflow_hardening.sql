-- Workflow hardening after the process review (November 2026).
-- Connects modules that were independent and closes the "what if" gaps:
--  * Membership fee before Active: approval creates a "Pending payment" membership and a fee;
--    linking the verified payment activates it. Off while the configured fee is 0.
--  * Bulk fee assessment for every active member.
--  * Documents verified one by one; approval refused while any uploaded document is unverified.
--  * Rejected applicants may start a new application.
--  * Waitlist promotion continues until the activity starts (not only until the deadline).
--  * Walk-in attendance for active members who were not registered.
--  * Archiving an upcoming activity tells registrants and waitlisted members and cancels linked
--    equipment requests.
--  * Equipment: overdue items block new requests; overlapping bookings refused at request time;
--    members confirm receipt; a damaged return alerts the finance officers.
--  * Separation of duties: results of an election are confirmed by someone other than the person
--    who closed voting or entered paper tallies; interclub results are verified by someone other
--    than the person who recorded them.
--  * One attendance-rate definition everywhere: present / (registered - excused).
begin;

-- ---------------------------------------------------------------- membership fee before Active
alter table app_private.settings add column membership_fee numeric(12,2) not null default 0 check(membership_fee>=0);
alter table app_private.memberships drop constraint memberships_status_check;
alter table app_private.memberships add constraint memberships_status_check
 check(status in ('Active','Inactive','Suspended','Expired','Pending payment'));

create function app_private.membership_fee_gate() returns trigger language plpgsql security definer set search_path='' as $$
declare v_fee numeric;
begin
 select membership_fee into v_fee from app_private.settings where id;
 if tg_op='INSERT' and coalesce(v_fee,0)>0 and new.status='Active' then new.status:='Pending payment'; end if;
 return new;
end $$;
create trigger membership_fee_gate before insert on app_private.memberships for each row execute function app_private.membership_fee_gate();

create function app_private.membership_fee_assess() returns trigger language plpgsql security definer set search_path='' as $$
declare v_fee numeric;
begin
 if new.status='Pending payment' then
  select membership_fee into v_fee from app_private.settings where id;
  insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by)
   values(new.user_id,'Membership fee',v_fee,current_date+14,new.approved_by);
  perform app_private.notify(new.user_id,1,new.user_id,'membership','Approved. Pay the membership fee to activate your membership.');
 end if;
 return new;
end $$;
create trigger membership_fee_assess after insert on app_private.memberships for each row execute function app_private.membership_fee_assess();

create function app_private.membership_fee_paid() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.payment_id is not null and old.payment_id is null and new.purpose='Membership fee' then
  update app_private.memberships set status='Active',updated_at=now(),version=version+1
   where user_id=new.user_id and status='Pending payment';
  if found then perform app_private.notify(new.user_id,1,new.user_id,'membership','Membership fee verified. Your membership is now Active.'); end if;
 end if;
 return new;
end $$;
create trigger membership_fee_paid after update on app_private.fee_assessments for each row execute function app_private.membership_fee_paid();

-- ---------------------------------------------------------------- equipment receipt
alter table app_private.borrowings add column received_at timestamptz;

-- ---------------------------------------------------------------- seat counts include excused
create or replace function app_private.event_stats() returns jsonb language sql stable security definer set search_path='' as $$
 select coalesce(jsonb_agg(jsonb_build_object(
  'event_id',e.id,
  'registered',(select count(*) from app_private.registrations r where r.event_id=e.id),
  'waitlisted',(select count(*) from app_private.waitlist w where w.event_id=e.id),
  'present',(select count(*) from app_private.attendance a where a.event_id=e.id and a.status='present'),
  'excused',(select count(*) from app_private.attendance a where a.event_id=e.id and a.status='excused'),
  'waitlist_position',(select p.pos from (select w.user_id,row_number() over(order by w.created_at) pos from app_private.waitlist w where w.event_id=e.id) p where p.user_id=auth.uid())
 )),'[]')
 from app_private.events e
 where app_private.can('activities') or (e.published and (app_private.member() or app_private.can('equipment') or app_private.can('training') or app_private.can('interclub')))
$$;

-- ---------------------------------------------------------------- new commands
create function app_private.hardening_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.events; b app_private.borrowings; a app_private.applications;
 d app_private.documents; v_id uuid; n integer:=0; v_user uuid;
begin
 case p_action
 when 'set_membership_fee' then
  if not (app_private.can('administration') or app_private.can('finance')) then raise exception 'Permission denied: finance' using errcode='42501'; end if;
  if (p_data->>'amount')::numeric<0 then raise exception 'The fee cannot be negative.'; end if;
  update app_private.settings set membership_fee=(p_data->>'amount')::numeric,updated_at=now() where id=true;
  perform app_private.note(12,null,p_action,p_data); return jsonb_build_object('amount',(p_data->>'amount')::numeric);
 when 'assess_fee_bulk' then
  perform app_private.require_permission('finance');
  if coalesce((p_data->>'amount')::numeric,0)<=0 then raise exception 'Enter an amount above zero.'; end if;
  if (p_data->>'due_on')::date<current_date then raise exception 'Choose a due date that has not passed.'; end if;
  insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by)
   select m.user_id,app_private.required(p_data,'purpose',200),(p_data->>'amount')::numeric,(p_data->>'due_on')::date,u
   from app_private.memberships m where m.status='Active'
   and not exists(select 1 from app_private.fee_assessments f where f.user_id=m.user_id and f.purpose=p_data->>'purpose' and f.due_on=(p_data->>'due_on')::date);
  get diagnostics n=row_count;
  perform app_private.note(12,null,p_action,p_data||jsonb_build_object('assessed',n));
  return jsonb_build_object('assessed',n);
 when 'verify_document' then
  perform app_private.require_permission('membership');
  select * into d from app_private.documents where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Document not found.'; end if;
  if d.owner_id=u then raise exception 'You cannot verify your own document.' using errcode='42501'; end if;
  update app_private.documents set verified_by=u where id=d.id;
  perform app_private.note(d.module,d.record_id,p_action,jsonb_build_object('document_id',d.id,'requirement',d.requirement));
  return jsonb_build_object('id',d.id);
 when 'restart_application' then
  select * into a from app_private.applications where user_id=u for update;
  if not found or a.status<>'rejected' then raise exception 'Only a rejected application can be started again.'; end if;
  if exists(select 1 from app_private.memberships where user_id=u) then raise exception 'An official membership already exists.'; end if;
  update app_private.applications set status='draft',confirmed=false,updated_at=now(),version=version+1 where id=a.id;
  perform app_private.note(2,a.id,p_action,'{}'); return jsonb_build_object('id',a.id);
 when 'record_walk_in' then
  perform app_private.require_permission('activities');
  select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
  if not found or not e.published or e.archived then raise exception 'Active event not found.'; end if;
  if now()<e.starts_at-interval '2 hours' or now()>e.ends_at+interval '1 day' then raise exception 'Walk-ins can be recorded only around the activity time.'; end if;
  v_user:=(p_data->>'user_id')::uuid;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') then raise exception 'Only active members can be recorded as walk-ins.'; end if;
  delete from app_private.waitlist where event_id=e.id and user_id=v_user;
  insert into app_private.registrations(event_id,user_id) values(e.id,v_user) on conflict(event_id,user_id) do nothing;
  insert into app_private.attendance(event_id,user_id,status,method,encoded_by,remark) values(e.id,v_user,'present','manual',u,'Walk-in')
   on conflict(event_id,user_id) do update set status='present',method='manual',encoded_by=u,remark='Walk-in',version=attendance.version+1 returning id into v_id;
  perform app_private.notify(v_user,4,v_id,'attendance','Recorded as a walk-in at '||e.title);
  perform app_private.note(8,v_id,p_action,p_data); return jsonb_build_object('id',v_id);
 when 'confirm_receipt' then
  select * into b from app_private.borrowings where id=(p_data->>'id')::uuid and user_id=u for update;
  if not found or b.status<>'released' then raise exception 'Only equipment handed over to you can be confirmed.'; end if;
  if b.received_at is not null then return jsonb_build_object('id',b.id); end if;
  update app_private.borrowings set received_at=now() where id=b.id;
  perform app_private.note(5,b.id,p_action,'{}'); return jsonb_build_object('id',b.id);
 end case;
end $$;

-- ---------------------------------------------------------------- API entry point
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_hardening;
revoke all on function app_private.club_command_before_hardening(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
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
  if exists(select 1 from app_private.documents where module=2 and record_id=(p_data->>'id')::uuid and verified_by is null) then
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
revoke all on function app_private.hardening_command(text,jsonb),app_private.membership_fee_gate(),app_private.membership_fee_assess(),app_private.membership_fee_paid() from public,anon,authenticated;

-- ---------------------------------------------------------------- waitlist promotion until the activity starts
create or replace function app_private.registration_command(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.events; v_id uuid; v_next app_private.waitlist; v_seats bigint;
begin
 perform app_private.require_member();
 select * into e from app_private.events where id=(p_data->>'event_id')::uuid for update;
 if not found or not e.published or e.archived then raise exception 'Registration is not open for this activity.'; end if;
 if p_action='register_event' then
  if now()<e.registration_opens then raise exception 'Registration has not opened yet.'; end if;
  if now()>e.registration_closes then raise exception 'Registration is closed.'; end if;
  select id into v_id from app_private.registrations where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','registered'); end if;
  select id into v_id from app_private.waitlist where event_id=e.id and user_id=u;
  if v_id is not null then return jsonb_build_object('id',v_id,'status','waitlisted'); end if;
  select count(*) into v_seats from app_private.registrations where event_id=e.id;
  if v_seats>=e.capacity then
   insert into app_private.waitlist(event_id,user_id) values(e.id,u) returning id into v_id;
   perform app_private.notify(u,3,e.id,'waitlist','Waitlisted: '||e.title);
   perform app_private.note(3,v_id,'join_waitlist',p_data);
   return jsonb_build_object('id',v_id,'status','waitlisted');
  end if;
  insert into app_private.registrations(event_id,user_id) values(e.id,u) returning id into v_id;
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
   values(u,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
  perform app_private.note(3,v_id,'register_event',p_data);
  return jsonb_build_object('id',v_id,'status','registered');
 elsif p_action='cancel_registration' then
  if now()>=e.starts_at then raise exception 'This activity has started. Ask an officer to update your record.'; end if;
  delete from app_private.waitlist where event_id=e.id and user_id=u returning id into v_id;
  if v_id is not null then perform app_private.note(3,v_id,'leave_waitlist',p_data); return jsonb_build_object('id',v_id,'status','cancelled'); end if;
  if exists(select 1 from app_private.attendance where event_id=e.id and user_id=u) then
   raise exception 'Attendance is already recorded for this activity.'; end if;
  delete from app_private.registrations where event_id=e.id and user_id=u returning id into v_id;
  if v_id is null then raise exception 'You are not registered for this activity.'; end if;
  update app_private.notifications set completed_at=now() where user_id=u and record_id=e.id and kind='event' and completed_at is null;
  perform app_private.note(3,v_id,'cancel_registration',p_data);
  -- The freed seat goes to the earliest waitlisted member until the activity starts.
  if now()<e.starts_at then
   select * into v_next from app_private.waitlist where event_id=e.id order by created_at limit 1 for update;
   if found then
    delete from app_private.waitlist where id=v_next.id;
    insert into app_private.registrations(event_id,user_id) values(e.id,v_next.user_id);
    perform app_private.notify(v_next.user_id,3,e.id,'promoted','A slot opened. You are now registered for '||e.title);
    insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
     values(v_next.user_id,3,e.id,'event','Upcoming activity: '||e.title,e.starts_at) on conflict do nothing;
    perform app_private.note(3,e.id,'waitlist_promotion',jsonb_build_object('user_id',v_next.user_id));
   end if;
  end if;
  return jsonb_build_object('id',v_id,'status','cancelled');
 end if;
 raise exception 'Unknown registration action.';
end $$;

create or replace function public.club_report_data(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb:='{}';begin
 perform app_private.require_permission('reports');
 if p_to<p_from then raise exception 'Invalid reporting period.';end if;
 if app_private.can('membership') then
 result:=result||jsonb_build_object('membership',jsonb_build_object('active',(select count(*) from app_private.memberships where status='Active'),'total',(select count(*) from app_private.memberships),'active_ratio',(select round(100.0*count(*) filter(where status='Active')/nullif(count(*),0),2) from app_private.memberships),'applications',(select count(*) from app_private.applications where submitted_at::date between p_from and p_to),'conversion_rate',(select round(100.0*count(*) filter(where status='approved')/nullif(count(*),0),2) from app_private.applications where submitted_at::date between p_from and p_to),'incomplete',(select count(*) from app_private.applications where status in ('incomplete','returned_for_correction'))));end if;
 if app_private.can('activities') then
 result:=result||jsonb_build_object('activities',(select coalesce(jsonb_agg(x),'[]') from(select e.id,e.title,(select count(*) from app_private.registrations where event_id=e.id) registrations,(select count(*) from app_private.attendance where event_id=e.id and status='present') present,(select count(*) from app_private.attendance where event_id=e.id and status='excused') excused,(select round(100.0*(select count(*) from app_private.attendance where event_id=e.id and status='present')/nullif(count(*)-(select count(*) from app_private.attendance where event_id=e.id and status='excused'),0),2) from app_private.registrations where event_id=e.id) attendance_rate from app_private.events e where e.starts_at::date between p_from and p_to)x));end if;
 if app_private.can('equipment') then
 result:=result||jsonb_build_object('equipment',jsonb_build_object('items',(select count(*) from app_private.equipment),'serviceable',(select count(*) from app_private.equipment where condition='serviceable'),'utilized_items',(select count(distinct equipment_id) from app_private.borrowings where released_at::date between p_from and p_to),'overdue',(select count(*) from app_private.borrowings where status='released' and ends_at<now()),'pending',(select count(*) from app_private.borrowings where status='pending')));end if;
 if app_private.can('training') then
 result:=result||jsonb_build_object('training',(select coalesce(jsonb_agg(x),'[]') from(select user_id,distance,target_face,category,scoring_format,maximum_score,training_date,count(*) sessions,round(avg(score),2) average_score from app_private.training_records where validation='validated' and training_date between p_from and p_to group by user_id,distance,target_face,category,scoring_format,maximum_score,training_date order by training_date)x));end if;
 if app_private.can('finance') then
 result:=result||jsonb_build_object('finance',jsonb_build_object('verified_payments',(select coalesce(sum(amount),0) from app_private.financial_records where kind='payment' and status='verified' and verified_at::date between p_from and p_to),'verified_expenses',(select coalesce(sum(amount),0) from app_private.financial_records where kind in ('expense','reimbursement') and status='verified' and verified_at::date between p_from and p_to),'outstanding_reimbursements',(select coalesce(sum(amount),0) from app_private.financial_records where kind='reimbursement' and status='pending_verification'),'collection_completion',(select round(100.0*count(*) filter(where payment_id is not null)/nullif(count(*),0),2) from app_private.fee_assessments where due_on between p_from and p_to)));end if;
 if app_private.can('interclub') then result:=result||jsonb_build_object('interclub',(select coalesce(jsonb_agg(x),'[]') from(select i.event_id,i.shared_title,i.status,(select count(*) from app_private.delegates where event_id=i.event_id) delegates,(select count(*) from app_private.interclub_results where event_id=i.event_id and verified_by is not null) verified_results from app_private.interclub_events i where shared_start::date between p_from and p_to)x));end if;
 return result||jsonb_build_object('from',p_from,'to',p_to,'generated_at',now(),'limitation','Attendance rate = present / (registered - excused). Recorded data only. Missing attendance, unvalidated training, and unverified finance are not assumed complete. Null rates have no denominator.');end
$$;

create or replace function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;g app_private.goals;data jsonb;actual numeric;goals jsonb:='[]';begin
 perform app_private.require_permission('reports');
 result:=public.club_report_data(p_from,p_to);
 for g in select * from app_private.goals where starts_on<=p_to and ends_on>=p_from loop
  data:=public.club_report_data(g.starts_on,g.ends_on);actual:=null;
  case g.metric
   when 'active_members' then actual:=(data->'membership'->>'active')::numeric;
   when 'applicant_conversion' then actual:=(data->'membership'->>'conversion_rate')::numeric;
   when 'attendance_rate' then select round(100.0*sum((v->>'present')::numeric)/nullif(sum((v->>'registrations')::numeric-coalesce((v->>'excused')::numeric,0)),0),2) into actual from jsonb_array_elements(coalesce(data->'activities','[]')) v;
   when 'equipment_readiness' then actual:=round(100.0*(data->'equipment'->>'serviceable')::numeric/nullif((data->'equipment'->>'items')::numeric,0),2);
   when 'training_average' then
    if app_private.can('training') then select round(avg(100.0*score/maximum_score),2) into actual from app_private.training_records where validation='validated' and training_date between g.starts_on and g.ends_on;end if;
   when 'collection_completion' then actual:=(data->'finance'->>'collection_completion')::numeric;
  end case;
  goals:=goals||jsonb_build_array(jsonb_build_object('id',g.id,'title',g.title,'metric',g.metric,'target',g.target,'actual',actual,'starts_on',g.starts_on,'ends_on',g.ends_on,'achievement_percent',round(100.0*actual/g.target,2),'basis',case when g.metric in ('active_members','equipment_readiness') then 'Current snapshot' when g.metric='training_average' then 'Validated score percentage; compare individual trends only within matching conditions' else 'Records in the goal period' end));
 end loop;
 if app_private.can('elections') then
  result:=result||jsonb_build_object('elections',(select coalesce(jsonb_agg(jsonb_build_object('id',e.id,'title',e.title,'summary',public.election_summary(e.id))),'[]') from app_private.elections e where e.voting_closes::date between p_from and p_to));
 end if;
 return result||jsonb_build_object('goals',goals);
end $$;
commit;
