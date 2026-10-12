begin;

-- Draft election configuration can be corrected and validated before publication.
-- Published rules remain locked; a later election is a separate record.
create function app_private.update_election(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare e app_private.elections; begin
 perform app_private.require_permission('elections');
 select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
 if not found or e.status<>'draft' then raise exception 'Only a draft election can be edited.';end if;
 perform app_private.check_version(e.version,p_data);
 update app_private.elections set
 title=app_private.required(p_data,'title',200),kind=p_data->>'kind',term_end=(p_data->>'term_end')::date,
 announces_at=(p_data->>'announces_at')::timestamptz,candidacy_closes=(p_data->>'candidacy_closes')::timestamptz,
 voting_opens=(p_data->>'voting_opens')::timestamptz,voting_closes=(p_data->>'voting_closes')::timestamptz,result_due=(p_data->>'result_due')::timestamptz,
 threshold=(p_data->>'threshold')::numeric,residency_terms=(p_data->>'residency_terms')::integer,
 rules=app_private.required(p_data,'rules'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),
 method=p_data->>'method',positions=array(select jsonb_array_elements_text(p_data->'positions')),version=version+1
 where id=e.id;
 perform app_private.note(9,e.id,'update_election',p_data);
 return jsonb_build_object('id',e.id);
end $$;
revoke all on function app_private.update_election(jsonb) from public,anon,authenticated;

-- Keep the existing command contract and privilege checks for every other action.
alter function public.club_command(text,jsonb) set schema app_private;
alter function app_private.club_command(text,jsonb) rename to club_command_before_completion;
revoke all on function app_private.club_command_before_completion(text,jsonb) from public,anon,authenticated;
create function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='update_election' then return app_private.update_election(p_data);end if;
 return app_private.club_command_before_completion(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;

-- Constraints complement the existing command's event lock and overlap checks.
create function app_private.guard_interclub_period() returns trigger
language plpgsql security definer set search_path='' as $$
declare e app_private.interclub_events;begin
 select * into e from app_private.interclub_events where event_id=new.event_id for update;
 if e.status not in ('preparation','active') then raise exception 'Assignments are locked after event completion.';end if;
 if new.starts_at<e.shared_start or new.ends_at>e.shared_end then raise exception 'Assignment must fit the shared event period.';end if;
 return new;
end $$;
create trigger interclub_assignment_period before insert or update on app_private.interclub_assignments
for each row execute function app_private.guard_interclub_period();

create function app_private.guard_interclub_stage() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if old.status='archived' or (new.status<>old.status and not (
  (old.status='preparation' and new.status='active') or
  (old.status='active' and new.status='completed') or
  (old.status='completed' and new.status='archived')
 )) then raise exception 'Invalid shared event transition; archived events stay locked.';end if;
 if exists(select 1 from app_private.interclub_assignments a where a.event_id=new.event_id
  and (a.starts_at<new.shared_start or a.ends_at>new.shared_end)) then
  raise exception 'The shared period must contain existing assignments.';
 end if;
 return new;
end $$;
create trigger interclub_event_stage before update on app_private.interclub_events
for each row execute function app_private.guard_interclub_stage();
revoke all on function app_private.guard_interclub_period(),app_private.guard_interclub_stage() from public,anon,authenticated;

-- A resubmitted task must reappear even if its earlier notice was acknowledged.
create or replace function app_private.notify_role(p_permission text,p_module integer,p_record uuid,p_message text) returns void
language sql security definer set search_path='' as $$
 insert into app_private.notifications(user_id,module,record_id,kind,message)
 select distinct a.user_id,p_module,p_record,'review',p_message from app_private.role_assignments a join app_private.roles r on r.id=a.role_id
 join app_private.profiles p on p.id=a.user_id where p.enabled and p_permission=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at
 on conflict(user_id,record_id,kind,message) do update set acknowledged_at=null,completed_at=null,notified_at=now()
$$;

create function app_private.sync_workflow_notices() returns trigger
language plpgsql security definer set search_path='' as $$
declare done boolean:=false;begin
 if tg_table_name in ('applications','renewals') then done:=new.status not in ('pending','under_review');
 elsif tg_table_name='borrowings' then
  done:=new.status<>'pending';
  if new.status='released' then
   insert into app_private.notifications(user_id,module,record_id,kind,message,due_at)
   values(new.user_id,5,new.id,'return','Return borrowed equipment',new.ends_at)
   on conflict(user_id,record_id,kind,message) do update set due_at=excluded.due_at,completed_at=null;
  elsif new.status='returned' then
   update app_private.notifications set completed_at=now() where record_id=new.id and kind='return' and completed_at is null;
  end if;
 elsif tg_table_name='training_records' then done:=new.validation<>'pending';
 elsif tg_table_name='financial_records' then done:=new.status<>'pending_verification';
 elsif tg_table_name='corrections' then done:=new.status<>'pending';
 elsif tg_table_name='fee_assessments' then
  insert into app_private.notifications(user_id,module,record_id,kind,message,due_at,completed_at)
  values(new.user_id,12,new.id,'fee','Fee assessment: '||new.purpose,new.due_on::timestamptz,case when new.payment_id is not null then now() end)
  on conflict(user_id,record_id,kind,message) do update set due_at=excluded.due_at,completed_at=excluded.completed_at;
 elsif tg_table_name='attendance' and new.status in ('present','excused') then
  update app_private.notifications set completed_at=now() where user_id=new.user_id and record_id=new.event_id and kind='event' and completed_at is null;
 end if;
 if done then update app_private.notifications set completed_at=now() where record_id=new.id and kind='review' and completed_at is null;end if;
 return new;
end $$;
do $$declare t text;begin
 foreach t in array array['applications','renewals','borrowings','training_records','financial_records','corrections','fee_assessments','attendance'] loop
  execute format('create trigger workflow_notices after insert or update on app_private.%I for each row execute function app_private.sync_workflow_notices()',t);
 end loop;
end $$;
revoke all on function app_private.sync_workflow_notices() from public,anon,authenticated;
commit;
