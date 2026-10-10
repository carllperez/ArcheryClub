begin;
create table app_private.equipment (
 id uuid primary key default gen_random_uuid(),label text unique not null,category text not null,specifications text not null,
 handedness text not null check(handedness in ('left','right','ambidextrous','not_applicable')),draw_weight numeric check(draw_weight>=0),bow_setup text not null default '',
 condition text not null check(condition in ('serviceable','maintenance','damaged','retired')),acquired_on date,notes text not null default '',version bigint not null default 1
);
create table app_private.borrowings (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.memberships(user_id),equipment_id uuid not null references app_private.equipment,
 event_id uuid references app_private.events,starts_at timestamptz not null,ends_at timestamptz not null,handedness text not null,maximum_draw_weight numeric check(maximum_draw_weight>=0),bow_setup text not null default '',purpose text not null,
 status text not null default 'pending' check(status in ('pending','approved','released','returned','rejected','returned_for_correction')),
 reviewed_by uuid references app_private.profiles,remark text not null default '',released_at timestamptz,returned_at timestamptz,version bigint not null default 1,check(ends_at>starts_at)
);
create index borrowing_period on app_private.borrowings(equipment_id,starts_at,ends_at);
create table app_private.equipment_history(id uuid primary key default gen_random_uuid(),equipment_id uuid not null references app_private.equipment,borrowing_id uuid references app_private.borrowings,condition text not null,notes text not null,actor uuid not null references app_private.profiles,created_at timestamptz not null default now());
create table app_private.training_records (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.memberships(user_id),event_id uuid references app_private.events,
 training_date date not null,distance numeric not null check(distance>0),target_face text not null,category text not null,scoring_format text not null,
 score numeric not null check(score>=0),maximum_score numeric not null check(maximum_score>0),grouping text not null default '',level text not null default '',observations text not null default '',feedback text not null default '',
 source text not null check(source in ('personal','coach','csv')),validation text not null check(validation in ('personal','pending','validated','returned_for_correction')),
 encoded_by uuid not null references app_private.profiles,encoded_at timestamptz not null default now(),validated_by uuid references app_private.profiles,validated_at timestamptz,
 import_key text unique,version bigint not null default 1,check(score<=maximum_score)
);
create table app_private.financial_records (
 id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.profiles,
 kind text not null check(kind in ('payment','expense','reimbursement')),purpose text not null,amount numeric(12,2) not null check(amount>0),
 reference text not null default '',status text not null default 'draft' check(status in ('draft','pending_verification','verified','incomplete','rejected','returned_for_correction')),
 remark text not null default '',reviewed_by uuid references app_private.profiles,created_at timestamptz not null default now(),verified_at timestamptz,version bigint not null default 1
);
create table app_private.financial_approvals (
 record_id uuid references app_private.financial_records,office text check(office in ('president','finance')),approved_by uuid not null references app_private.profiles,approved_at timestamptz not null default now(),remark text not null,
 primary key(record_id,office)
);
create table app_private.fee_assessments(id uuid primary key default gen_random_uuid(),user_id uuid not null references app_private.profiles,purpose text not null,amount numeric(12,2) not null check(amount>0),due_on date not null,payment_id uuid unique references app_private.financial_records,created_by uuid not null references app_private.profiles);

create function app_private.equipment_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;e app_private.equipment;b app_private.borrowings;v_status text;begin
 case p_action
 when 'save_equipment' then
  perform app_private.require_permission('equipment');
  if nullif(p_data->>'id','') is null then
   insert into app_private.equipment(label,category,specifications,handedness,draw_weight,bow_setup,condition,acquired_on,notes)
   values(app_private.required(p_data,'label',160),app_private.required(p_data,'category',100),app_private.required(p_data,'specifications'),p_data->>'handedness',nullif(p_data->>'draw_weight','')::numeric,coalesce(p_data->>'bow_setup',''),p_data->>'condition',nullif(p_data->>'acquired_on','')::date,coalesce(p_data->>'notes','')) returning id into v_id;
  else
   select * into e from app_private.equipment where id=(p_data->>'id')::uuid for update;if not found then raise exception 'Equipment not found.';end if;perform app_private.check_version(e.version,p_data);
   update app_private.equipment set label=app_private.required(p_data,'label',160),category=app_private.required(p_data,'category',100),specifications=app_private.required(p_data,'specifications'),handedness=p_data->>'handedness',draw_weight=nullif(p_data->>'draw_weight','')::numeric,bow_setup=coalesce(p_data->>'bow_setup',''),condition=p_data->>'condition',notes=coalesce(p_data->>'notes',''),version=version+1 where id=e.id;v_id:=e.id;
  end if;
  insert into app_private.equipment_history(equipment_id,condition,notes,actor) values(v_id,p_data->>'condition',coalesce(p_data->>'notes',''),u);
 when 'request_borrowing' then
  perform app_private.require_member();
  select * into e from app_private.equipment where id=(p_data->>'equipment_id')::uuid;
  if not found or e.condition<>'serviceable' then raise exception 'Select serviceable equipment.';end if;
  if (p_data->>'starts_at')::timestamptz<now() then raise exception 'Choose a future borrowing period.';end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.borrowings(user_id,equipment_id,event_id,starts_at,ends_at,handedness,maximum_draw_weight,bow_setup,purpose)
   values(u,e.id,nullif(p_data->>'event_id','')::uuid,(p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,p_data->>'handedness',nullif(p_data->>'maximum_draw_weight','')::numeric,coalesce(p_data->>'bow_setup',''),app_private.required(p_data,'purpose')) returning id into v_id;
  else
   select * into b from app_private.borrowings where id=(p_data->>'id')::uuid and user_id=u for update;
   if not found or b.status<>'returned_for_correction' then raise exception 'Editable borrowing request not found.';end if;perform app_private.check_version(b.version,p_data);
   update app_private.borrowings set equipment_id=e.id,starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,handedness=p_data->>'handedness',maximum_draw_weight=nullif(p_data->>'maximum_draw_weight','')::numeric,bow_setup=coalesce(p_data->>'bow_setup',''),purpose=app_private.required(p_data,'purpose'),status='pending',version=version+1 where id=b.id;v_id:=b.id;
  end if;
  perform app_private.notify_role('equipment',10,v_id,'Borrowing request requires review');
 when 'review_borrowing' then
  perform app_private.require_permission('equipment');
  select * into b from app_private.borrowings where id=(p_data->>'id')::uuid for update;if not found then raise exception 'Borrowing request not found.';end if;
  perform app_private.check_version(b.version,p_data);v_status:=p_data->>'status';
  if not ((b.status='pending' and v_status in ('approved','rejected','returned_for_correction')) or (b.status='approved' and v_status in ('released','rejected')) or (b.status='released' and v_status='returned')) then raise exception 'Invalid borrowing transition.';end if;
  perform app_private.required(p_data,'remark');
  select * into e from app_private.equipment where id=b.equipment_id for update;
  if v_status in ('approved','released') then
   if e.condition<>'serviceable' then raise exception 'Equipment is not serviceable.';end if;
   if exists(select 1 from app_private.borrowings x where x.id<>b.id and x.equipment_id=e.id and x.status in ('approved','released') and (x.status='released' and x.ends_at<now() or tstzrange(x.starts_at,x.ends_at,'[)') && tstzrange(b.starts_at,b.ends_at,'[)'))) then raise exception 'Equipment has a conflicting reservation or overdue return.';end if;
   if not coalesce((p_data->>'compatibility_confirmed')::boolean,false) then raise exception 'Officer must confirm safety and compatibility.';end if;
   if (e.handedness not in ('ambidextrous','not_applicable') and e.handedness<>b.handedness) or (b.maximum_draw_weight is not null and e.draw_weight>b.maximum_draw_weight) or (b.bow_setup<>'' and b.bow_setup<>e.bow_setup) then raise exception 'Equipment is incompatible with this request. Select an alternative.';end if;
  end if;
  update app_private.borrowings set status=v_status,reviewed_by=u,remark=p_data->>'remark',released_at=case when v_status='released' then now() else released_at end,returned_at=case when v_status='returned' then now() else returned_at end,version=version+1 where id=b.id;
  if v_status='returned' then
   update app_private.equipment set condition=p_data->>'condition',version=version+1 where id=e.id;
   insert into app_private.equipment_history(equipment_id,borrowing_id,condition,notes,actor) values(e.id,b.id,p_data->>'condition',p_data->>'remark',u);
  end if;
  v_id:=b.id;perform app_private.notify(b.user_id,5,b.id,'borrowing',v_status||': '||(p_data->>'remark'));
 else raise exception 'Unknown equipment action.';end case;
 perform app_private.note(10,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;
create function public.equipment_availability(p_start timestamptz,p_end timestamptz,p_handedness text default '',p_max_weight numeric default null,p_setup text default '') returns jsonb language plpgsql security definer set search_path='' as $$
 declare result jsonb;begin
 if not (app_private.member() or app_private.can('equipment')) then raise exception 'Permission denied.' using errcode='42501';end if;
 if p_end<=p_start then raise exception 'Invalid borrowing period.';end if;
 select coalesce(jsonb_agg(t),'[]') into result from (
 select e.*,exists(select 1 from app_private.borrowings b where b.equipment_id=e.id and b.status in ('approved','released') and ((b.status='released' and b.ends_at<now()) or tstzrange(b.starts_at,b.ends_at,'[)')&&tstzrange(p_start,p_end,'[)'))) as reserved,
 (e.condition='serviceable' and (p_handedness='' or e.handedness in (p_handedness,'ambidextrous','not_applicable')) and (p_max_weight is null or e.draw_weight is null or e.draw_weight<=p_max_weight) and (p_setup='' or e.bow_setup=p_setup)) as compatible
 from app_private.equipment e) t;return result;end
$$;

create function app_private.training_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;v_user uuid;t app_private.training_records;c app_private.corrections;entry jsonb;results jsonb:='[]';begin
 case p_action
 when 'save_training','save_personal_training' then
  if p_action='save_training' then perform app_private.require_permission('training');v_user:=(p_data->>'user_id')::uuid;else perform app_private.require_member();v_user:=u;end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.training_records(user_id,event_id,training_date,distance,target_face,category,scoring_format,score,maximum_score,grouping,level,observations,feedback,source,validation,encoded_by,validated_by,validated_at,import_key)
   values(v_user,nullif(p_data->>'event_id','')::uuid,(p_data->>'training_date')::date,(p_data->>'distance')::numeric,app_private.required(p_data,'target_face',100),app_private.required(p_data,'category',100),app_private.required(p_data,'scoring_format',100),(p_data->>'score')::numeric,(p_data->>'maximum_score')::numeric,coalesce(p_data->>'grouping',''),coalesce(p_data->>'level',''),coalesce(p_data->>'observations',''),case when p_action='save_training' then coalesce(p_data->>'feedback','') else '' end,case when p_action='save_training' then 'coach' else 'personal' end,case when p_action='save_training' then 'validated' else 'personal' end,u,case when p_action='save_training' then u end,case when p_action='save_training' then now() end,case when p_action='save_training' then nullif(p_data->>'import_key','') end) returning id into v_id;
  else
   select * into t from app_private.training_records where id=(p_data->>'id')::uuid for update;
   if not found or (p_action='save_personal_training' and (t.user_id<>u or t.validation not in ('personal','returned_for_correction'))) then raise exception 'Editable training record not found.';end if;
   perform app_private.check_version(t.version,p_data);
   update app_private.training_records set training_date=(p_data->>'training_date')::date,distance=(p_data->>'distance')::numeric,target_face=app_private.required(p_data,'target_face',100),category=app_private.required(p_data,'category',100),scoring_format=app_private.required(p_data,'scoring_format',100),score=(p_data->>'score')::numeric,maximum_score=(p_data->>'maximum_score')::numeric,grouping=coalesce(p_data->>'grouping',''),level=coalesce(p_data->>'level',''),observations=coalesce(p_data->>'observations',''),feedback=case when p_action='save_training' then coalesce(p_data->>'feedback','') else feedback end,version=version+1 where id=t.id;v_id:=t.id;
  end if;
 when 'submit_training' then
  update app_private.training_records set validation='pending',version=version+1 where id=(p_data->>'id')::uuid and user_id=u and validation in ('personal','returned_for_correction') returning id into v_id;
  if not found then raise exception 'Personal training record not found.';end if;
  perform app_private.notify_role('training',11,v_id,'Personal training submitted for review');
 when 'validate_training' then
  perform app_private.require_permission('training');select * into t from app_private.training_records where id=(p_data->>'id')::uuid for update;
  if not found or t.validation<>'pending' then raise exception 'Pending training record not found.';end if;perform app_private.check_version(t.version,p_data);
  if p_data->>'validation' not in ('validated','returned_for_correction') then raise exception 'Invalid validation decision.';end if;
  update app_private.training_records set validation=p_data->>'validation',feedback=app_private.required(p_data,'feedback'),validated_by=u,validated_at=now(),version=version+1 where id=t.id;v_id:=t.id;
  perform app_private.notify(t.user_id,4,t.id,'training',p_data->>'validation');
 when 'import_training' then
  perform app_private.require_permission('training');
  if jsonb_typeof(p_data->'rows') is distinct from 'array' or jsonb_array_length(p_data->'rows') not between 1 and 500 then raise exception 'Import 1–500 score rows.';end if;
  for entry in select value from jsonb_array_elements(p_data->'rows') loop
   perform app_private.required(entry,'import_key',200);
   results:=results||jsonb_build_array(app_private.training_command('save_training',entry));
  end loop;return results;
 when 'request_training_correction' then
  select * into t from app_private.training_records where id=(p_data->>'record_id')::uuid and user_id=u and validation='validated';
  if not found then raise exception 'Validated training record not found.';end if;
  insert into app_private.corrections(user_id,module,record_id,request) values(u,11,t.id,app_private.required(p_data,'request')) returning id into v_id;
  perform app_private.notify_role('training',11,v_id,'Training correction requested');
 when 'review_training_correction' then
  perform app_private.require_permission('training');select * into c from app_private.corrections where id=(p_data->>'id')::uuid and module=11 for update;
  if not found or c.status<>'pending' then raise exception 'Pending correction not found.';end if;
  if p_data->>'status' not in ('approved','rejected') then raise exception 'Invalid correction decision.';end if;
  if p_data->>'status'='approved' then
   if p_data->'corrected_record' is null then raise exception 'Provide corrected scorecard.';end if;
   perform app_private.training_command('save_training',(p_data->'corrected_record')||jsonb_build_object('id',c.record_id));
  end if;
  update app_private.corrections set status=p_data->>'status',decision=app_private.required(p_data,'decision'),reviewed_by=u where id=c.id;v_id:=c.id;
  perform app_private.notify(c.user_id,4,c.id,'correction',p_data->>'decision');
 else raise exception 'Unknown training action.';end case;
 perform app_private.note(11,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;

create function app_private.finance_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user();v_id uuid;f app_private.financial_records;v_office text;begin
 case p_action
 when 'save_financial_request' then
  if p_data->>'kind'<>'payment' then perform app_private.require_member();end if;
  if p_data->>'kind'='payment' and not exists(select 1 from app_private.applications where user_id=u) and not exists(select 1 from app_private.memberships where user_id=u) then raise exception 'Submit an application before submitting payment records.';end if;
  if nullif(p_data->>'id','') is null then
   insert into app_private.financial_records(user_id,kind,purpose,amount,reference) values(u,p_data->>'kind',app_private.required(p_data,'purpose'),(p_data->>'amount')::numeric,coalesce(p_data->>'reference','')) returning id into v_id;
  else
   select * into f from app_private.financial_records where id=(p_data->>'id')::uuid and user_id=u for update;
   if not found or f.status not in ('draft','incomplete','returned_for_correction') then raise exception 'Editable financial record not found.';end if;perform app_private.check_version(f.version,p_data);
   delete from app_private.financial_approvals where record_id=f.id;
   update app_private.financial_records set purpose=app_private.required(p_data,'purpose'),amount=(p_data->>'amount')::numeric,reference=coalesce(p_data->>'reference',''),status='draft',version=version+1 where id=f.id;v_id:=f.id;
  end if;
 when 'submit_financial_request' then
  select * into f from app_private.financial_records where id=(p_data->>'id')::uuid and user_id=u for update;
  if not found or f.status not in ('draft','incomplete','returned_for_correction') then raise exception 'Editable financial request not found.';end if;perform app_private.check_version(f.version,p_data);
  if f.kind='payment' and length(btrim(f.reference))=0 then raise exception 'Payment reference is required.';end if;
  if not exists(select 1 from app_private.documents where record_id=f.id and module=12 and owner_id=u) then raise exception 'Supporting proof or receipt is required.';end if;
  update app_private.financial_records set status='pending_verification',version=version+1 where id=f.id;v_id:=f.id;
  perform app_private.notify_role('finance',12,f.id,'Financial submission requires review');
 when 'approve_funds' then
  v_office:=p_data->>'office';if v_office not in ('president','finance') then raise exception 'Invalid approval office.';end if;perform app_private.require_permission(v_office);
  select * into f from app_private.financial_records where id=(p_data->>'id')::uuid for update;
  if not found or f.kind='payment' or f.status<>'pending_verification' then raise exception 'Pending expense or reimbursement required.';end if;
  if f.user_id=u then raise exception 'You cannot approve your own expense.' using errcode='42501';end if;
  if exists(select 1 from app_private.financial_approvals where record_id=f.id and approved_by=u and financial_approvals.office<>v_office) then raise exception 'President and Treasurer approvals must be separate people.';end if;
  insert into app_private.financial_approvals(record_id,office,approved_by,remark) values(f.id,v_office,u,app_private.required(p_data,'remark')) on conflict(record_id,office) do nothing;v_id:=f.id;
 when 'verify_financial_request' then
  perform app_private.require_permission('finance');select * into f from app_private.financial_records where id=(p_data->>'id')::uuid for update;
  if not found or f.status<>'pending_verification' then raise exception 'Pending financial record not found.';end if;perform app_private.check_version(f.version,p_data);
  if f.user_id=u then raise exception 'Another finance officer must verify your submission.' using errcode='42501';end if;
  if p_data->>'status' not in ('verified','incomplete','rejected','returned_for_correction') then raise exception 'Invalid financial decision.';end if;
  if p_data->>'status'='verified' and f.kind<>'payment' and (select count(*) from app_private.financial_approvals where record_id=f.id)<>2 then raise exception 'President and Treasurer prior approvals are required.';end if;
  update app_private.financial_records set status=p_data->>'status',remark=app_private.required(p_data,'remark'),reviewed_by=u,verified_at=case when p_data->>'status'='verified' then now() end,version=version+1 where id=f.id;v_id:=f.id;
  perform app_private.notify(f.user_id,12,f.id,'finance',p_data->>'status'||': '||(p_data->>'remark'));
 when 'assess_fee' then
  perform app_private.require_permission('finance');insert into app_private.fee_assessments(user_id,purpose,amount,due_on,created_by) values((p_data->>'user_id')::uuid,app_private.required(p_data,'purpose'),(p_data->>'amount')::numeric,(p_data->>'due_on')::date,u) returning id into v_id;
 when 'link_fee_payment' then
  perform app_private.require_permission('finance');
  select * into f from app_private.financial_records where id=(p_data->>'payment_id')::uuid and kind='payment' and status='verified';
  if not found then raise exception 'Verified payment not found.';end if;
  update app_private.fee_assessments set payment_id=f.id where id=(p_data->>'id')::uuid and user_id=f.user_id and amount=f.amount returning id into v_id;
  if not found then raise exception 'Payment must match the assessed person and amount.';end if;
 else raise exception 'Unknown finance action.';end case;
 perform app_private.note(12,v_id,p_action,p_data);return jsonb_build_object('id',v_id);end
$$;
do $$declare t text;begin foreach t in array array['equipment','borrowings','equipment_history','training_records','financial_records','financial_approvals','fee_assessments'] loop
 execute format('alter table app_private.%I enable row level security',t);execute format('revoke all on app_private.%I from public,anon,authenticated',t);execute format('grant select on app_private.%I to authenticated',t);
end loop;end $$;
create policy equipment_read on app_private.equipment for select to authenticated using(app_private.member() or app_private.can('equipment'));
create policy borrowing_read on app_private.borrowings for select to authenticated using(app_private.can('equipment') or (app_private.active_user() and user_id=auth.uid()));
create policy equipment_history_read on app_private.equipment_history for select to authenticated using(app_private.can('equipment'));
create policy training_read on app_private.training_records for select to authenticated using(app_private.can('training') or (app_private.active_user() and user_id=auth.uid()));
create policy financial_read on app_private.financial_records for select to authenticated using(app_private.can('finance') or (app_private.can('president') and kind in ('expense','reimbursement')) or (app_private.active_user() and user_id=auth.uid()));
create policy approval_read on app_private.financial_approvals for select to authenticated using(exists(select 1 from app_private.financial_records f where f.id=record_id));
create policy fee_read on app_private.fee_assessments for select to authenticated using(app_private.can('finance') or (app_private.active_user() and user_id=auth.uid()));
revoke all on function app_private.equipment_command(text,jsonb),app_private.training_command(text,jsonb),app_private.finance_command(text,jsonb) from public,anon,authenticated;
revoke all on function public.equipment_availability(timestamptz,timestamptz,text,numeric,text) from public,anon;
grant execute on function public.equipment_availability(timestamptz,timestamptz,text,numeric,text) to authenticated;
commit;
