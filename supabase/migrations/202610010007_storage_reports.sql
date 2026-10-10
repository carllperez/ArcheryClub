begin;
create table app_private.goals(id uuid primary key default gen_random_uuid(),metric text not null,title text not null,target numeric not null check(target>0),starts_on date not null,ends_on date not null,created_by uuid not null references app_private.profiles,check(ends_on>=starts_on));
create table app_private.video_verifications(path text primary key,duration_ms integer not null check(duration_ms between 1 and 10000),verified_at timestamptz not null default now());
alter table app_private.goals enable row level security;alter table app_private.video_verifications enable row level security;
revoke all on app_private.goals,app_private.video_verifications from public,anon,authenticated;
grant select on app_private.goals to authenticated;
create policy goal_read on app_private.goals for select to authenticated using(app_private.can('reports'));

create function app_private.document_access(p_module integer,p_record uuid,p_write boolean default false) returns boolean language plpgsql stable security definer set search_path='' as $$
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
 else return false;end case;end
$$;
create function app_private.storage_write(p_name text) returns boolean language plpgsql stable security definer set search_path='' as $$
 begin
 if split_part(p_name,'/',1)<>auth.uid()::text then return false;end if;
 return app_private.document_access(split_part(p_name,'/',2)::integer,split_part(p_name,'/',3)::uuid,true);
 exception when invalid_text_representation then return false;end
$$;
insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('club-documents','club-documents',false,20971520,array['application/pdf','image/jpeg','image/png','text/csv','video/mp4']);
-- A revoked representative must not regain an attached file through the orphan fallback.
-- Check attachment existence independently of the caller's filtered document rows.
create function app_private.registered_path(p_name text) returns boolean language sql stable security definer set search_path='' as $$
 select exists(select 1 from app_private.documents where path=p_name)
$$;
revoke all on function app_private.registered_path(text) from public,anon,authenticated;
grant execute on function app_private.registered_path(text) to authenticated;
create policy archery_upload on storage.objects for insert to authenticated with check(bucket_id='club-documents' and app_private.storage_write(name));
create policy archery_download on storage.objects for select to authenticated using(bucket_id='club-documents' and app_private.active_user() and (
 exists(select 1 from app_private.documents d where d.path=storage.objects.name and app_private.document_access(d.module,d.record_id,false))
 or (owner_id=auth.uid()::text and not app_private.registered_path(storage.objects.name))));
create policy archery_orphan_cleanup on storage.objects for delete to authenticated using(bucket_id='club-documents' and app_private.active_user() and owner_id=auth.uid()::text and not app_private.registered_path(storage.objects.name));
create policy document_read on app_private.documents for select to authenticated using(app_private.document_access(module,record_id,false));
create function public.register_document(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();s storage.objects;v_id uuid;m integer:=(p_data->>'module')::integer;r uuid:=(p_data->>'record_id')::uuid;begin
 if not app_private.document_access(m,r,true) then raise exception 'This record does not accept your uploads.' using errcode='42501';end if;
 select * into s from storage.objects where bucket_id='club-documents' and name=p_data->>'path' and owner_id=u::text;
 if not found or split_part(s.name,'/',1)<>u::text or split_part(s.name,'/',2)<>m::text or split_part(s.name,'/',3)<>r::text then raise exception 'Uploaded file does not belong to this record.';end if;
 if s.metadata->>'mimetype'='video/mp4' and (m<>11 or not exists(select 1 from app_private.video_verifications where path=s.name)) then raise exception 'Training video must pass the server duration check (10 seconds maximum).';end if;
 insert into app_private.documents(owner_id,module,record_id,requirement,name,path,mime,size_bytes)
 values(u,m,r,app_private.required(p_data,'requirement',160),app_private.required(p_data,'name',200),s.name,s.metadata->>'mimetype',(s.metadata->>'size')::bigint)
 returning id into v_id;perform app_private.note(m,r,'attach_document',jsonb_build_object('document_id',v_id));return jsonb_build_object('id',v_id);end
$$;

create function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb:='{}';begin
 perform app_private.require_permission('reports');
 if p_to<p_from then raise exception 'Invalid reporting period.';end if;
 if app_private.can('membership') then
 result:=result||jsonb_build_object('membership',jsonb_build_object('active',(select count(*) from app_private.memberships where status='Active'),'total',(select count(*) from app_private.memberships),'active_ratio',(select round(100.0*count(*) filter(where status='Active')/nullif(count(*),0),2) from app_private.memberships),'applications',(select count(*) from app_private.applications where submitted_at::date between p_from and p_to),'conversion_rate',(select round(100.0*count(*) filter(where status='approved')/nullif(count(*),0),2) from app_private.applications where submitted_at::date between p_from and p_to),'incomplete',(select count(*) from app_private.applications where status in ('incomplete','returned_for_correction'))));end if;
 if app_private.can('activities') then
 result:=result||jsonb_build_object('activities',(select coalesce(jsonb_agg(x),'[]') from(select e.id,e.title,(select count(*) from app_private.registrations where event_id=e.id) registrations,(select count(*) from app_private.attendance where event_id=e.id and status='present') present,(select round(100.0*(select count(*) from app_private.attendance where event_id=e.id and status='present')/nullif(count(*),0),2) from app_private.registrations where event_id=e.id) attendance_rate from app_private.events e where e.starts_at::date between p_from and p_to)x));end if;
 if app_private.can('equipment') then
 result:=result||jsonb_build_object('equipment',jsonb_build_object('items',(select count(*) from app_private.equipment),'serviceable',(select count(*) from app_private.equipment where condition='serviceable'),'utilized_items',(select count(distinct equipment_id) from app_private.borrowings where released_at::date between p_from and p_to),'overdue',(select count(*) from app_private.borrowings where status='released' and ends_at<now()),'pending',(select count(*) from app_private.borrowings where status='pending')));end if;
 if app_private.can('training') then
 result:=result||jsonb_build_object('training',(select coalesce(jsonb_agg(x),'[]') from(select user_id,distance,target_face,category,scoring_format,maximum_score,training_date,count(*) sessions,round(avg(score),2) average_score from app_private.training_records where validation='validated' and training_date between p_from and p_to group by user_id,distance,target_face,category,scoring_format,maximum_score,training_date order by training_date)x));end if;
 if app_private.can('finance') then
 result:=result||jsonb_build_object('finance',jsonb_build_object('verified_payments',(select coalesce(sum(amount),0) from app_private.financial_records where kind='payment' and status='verified' and verified_at::date between p_from and p_to),'verified_expenses',(select coalesce(sum(amount),0) from app_private.financial_records where kind in ('expense','reimbursement') and status='verified' and verified_at::date between p_from and p_to),'outstanding_reimbursements',(select coalesce(sum(amount),0) from app_private.financial_records where kind='reimbursement' and status='pending_verification'),'collection_completion',(select round(100.0*count(*) filter(where payment_id is not null)/nullif(count(*),0),2) from app_private.fee_assessments where due_on between p_from and p_to)));end if;
 if app_private.can('interclub') then result:=result||jsonb_build_object('interclub',(select coalesce(jsonb_agg(x),'[]') from(select i.event_id,i.shared_title,i.status,(select count(*) from app_private.delegates where event_id=i.event_id) delegates,(select count(*) from app_private.interclub_results where event_id=i.event_id and verified_by is not null) verified_results from app_private.interclub_events i where shared_start::date between p_from and p_to)x));end if;
 return result||jsonb_build_object('from',p_from,'to',p_to,'generated_at',now(),'limitation','Recorded data only. Missing attendance, unvalidated training, and unverified finance are not assumed complete. Null rates have no denominator.');end
$$;
revoke all on function app_private.document_access(integer,uuid,boolean),app_private.storage_write(text) from public,anon,authenticated;
grant execute on function app_private.document_access(integer,uuid,boolean),app_private.storage_write(text) to authenticated;
revoke all on function public.register_document(jsonb),public.club_report(date,date) from public,anon;
grant execute on function public.register_document(jsonb),public.club_report(date,date) to authenticated;
commit;
