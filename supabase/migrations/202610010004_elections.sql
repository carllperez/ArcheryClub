begin;
create table app_private.academic_terms(id uuid primary key default gen_random_uuid(),name text unique not null,sequence integer unique not null,starts_on date not null,ends_on date not null,check(ends_on>starts_on));
create table app_private.member_terms(user_id uuid references app_private.memberships(user_id),term_id uuid references app_private.academic_terms,completed boolean not null default false,primary key(user_id,term_id));
create table app_private.elections (
 id uuid primary key default gen_random_uuid(),title text not null,kind text not null check(kind in ('annual','special')),
 term_end date not null,announces_at timestamptz not null,candidacy_closes timestamptz not null,voting_opens timestamptz not null,voting_closes timestamptz not null,result_due timestamptz not null,
 threshold numeric not null default 60 check(threshold between 0 and 100),residency_terms integer not null default 2 check(residency_terms>=0),
 rules text not null,rules_validated boolean not null default false,method text not null check(method in ('digital','paper','external')),
 positions text[] not null default array['President','Vice President'], status text not null default 'draft' check(status in ('draft','announced','voting','closed','requires_revote','confirmed','archived')),
 external_turnout integer check(external_turnout>=0),external_results jsonb,confirmation text,confirmed_by uuid references app_private.profiles,
 created_by uuid not null references app_private.profiles,version bigint not null default 1,
 check(candidacy_closes>announces_at),check(voting_opens>=candidacy_closes),check(voting_closes>voting_opens),check(result_due>=voting_closes)
);
create table app_private.candidates (
 id uuid primary key default gen_random_uuid(),election_id uuid not null references app_private.elections,
 user_id uuid not null references app_private.memberships(user_id),position text not null,statement text not null,
 nominated_by uuid not null references app_private.profiles,confirmed boolean not null default false,reviewed_by uuid references app_private.profiles,remark text not null default '',
 unique(election_id,user_id,position)
);
create table app_private.eligible_voters (
 election_id uuid references app_private.elections,user_id uuid references app_private.memberships(user_id),confirmed_by uuid not null references app_private.profiles,reason text not null,
 primary key(election_id,user_id)
);
-- Choices never contain user ID, cast timestamp, device ID, token or participation foreign key.
create table app_private.ballots(id uuid primary key default gen_random_uuid(),election_id uuid not null references app_private.elections,choices jsonb not null);
create table app_private.voter_participation(election_id uuid,user_id uuid,primary key(election_id,user_id),foreign key(election_id,user_id) references app_private.eligible_voters);
create table app_private.leadership_records (
 id uuid primary key default gen_random_uuid(),election_id uuid references app_private.elections,user_id uuid references app_private.profiles,
 position text not null,kind text not null check(kind in ('appointment','deliberation','vacancy','succession','transition')),
 starts_on date,ends_on date,documentation text not null,created_by uuid not null references app_private.profiles,created_at timestamptz not null default now()
);
create function app_private.residency(p_user uuid,p_count integer) returns boolean language sql stable security definer set search_path='' as $$
 select p_count=0 or (select count(*) from (
  select t.sequence,row_number() over(order by t.sequence desc) rn from app_private.member_terms mt join app_private.academic_terms t on t.id=mt.term_id
  where mt.user_id=p_user and mt.completed and t.ends_on<current_date order by t.sequence desc limit p_count
 ) q where q.sequence+q.rn=(select max(t.sequence)+1 from app_private.member_terms mt join app_private.academic_terms t on t.id=mt.term_id where mt.user_id=p_user and mt.completed and t.ends_on<current_date))=p_count
$$;
create function app_private.election_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); e app_private.elections; v_id uuid; v_user uuid; n integer; total integer; position_name text; choice text; tally jsonb; begin
 if p_action not in ('file_candidacy','nominate_candidate','cast_ballot') then perform app_private.require_permission('elections'); end if;
 case p_action
 when 'save_term' then
  insert into app_private.academic_terms(name,sequence,starts_on,ends_on) values(app_private.required(p_data,'name',160),(p_data->>'sequence')::integer,(p_data->>'starts_on')::date,(p_data->>'ends_on')::date) returning id into v_id;
 when 'record_residency' then
  insert into app_private.member_terms(user_id,term_id,completed) values((p_data->>'user_id')::uuid,(p_data->>'term_id')::uuid,(p_data->>'completed')::boolean)
  on conflict(user_id,term_id) do update set completed=excluded.completed;v_id:=(p_data->>'user_id')::uuid;
 when 'create_election' then
  insert into app_private.elections(title,kind,term_end,announces_at,candidacy_closes,voting_opens,voting_closes,result_due,threshold,residency_terms,rules,rules_validated,method,positions,created_by)
  values(app_private.required(p_data,'title',200),p_data->>'kind',(p_data->>'term_end')::date,(p_data->>'announces_at')::timestamptz,(p_data->>'candidacy_closes')::timestamptz,(p_data->>'voting_opens')::timestamptz,(p_data->>'voting_closes')::timestamptz,(p_data->>'result_due')::timestamptz,coalesce((p_data->>'threshold')::numeric,60),coalesce((p_data->>'residency_terms')::integer,2),app_private.required(p_data,'rules'),coalesce((p_data->>'rules_validated')::boolean,false),p_data->>'method',array(select jsonb_array_elements_text(coalesce(p_data->'positions','["President","Vice President"]'))),u) returning id into v_id;
 when 'file_candidacy','nominate_candidate' then
  perform app_private.require_member();
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status<>'announced' or now()<e.announces_at or now()>e.candidacy_closes then raise exception 'Candidacy filing is not open.'; end if;
  if not (p_data->>'position'=any(e.positions)) then raise exception 'Select a configured elected position.'; end if;
  v_user:=case when p_action='nominate_candidate' then (p_data->>'user_id')::uuid else u end;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') then raise exception 'An active member is required for candidacy.';end if;
  insert into app_private.candidates(election_id,user_id,position,statement,nominated_by) values(e.id,v_user,p_data->>'position',app_private.required(p_data,'statement'),u) returning id into v_id;
  perform app_private.notify(v_user,9,v_id,'nomination','Candidacy recorded for '||e.title||'; eligibility is subject to officer review.');
 when 'confirm_candidate' then
  select e1.* into e from app_private.elections e1 join app_private.candidates c on c.election_id=e1.id where c.id=(p_data->>'id')::uuid for update of e1;
  if not found or e.status not in ('draft','announced') then raise exception 'Candidate review is closed.'; end if;
  update app_private.candidates set confirmed=(p_data->>'confirmed')::boolean,reviewed_by=u,remark=app_private.required(p_data,'remark') where id=(p_data->>'id')::uuid returning id into v_id;
 when 'confirm_voter' then
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status not in ('draft','announced') then raise exception 'Voter roll is locked.'; end if;
  v_user:=(p_data->>'user_id')::uuid;
  if not exists(select 1 from app_private.memberships where user_id=v_user and status='Active') or not app_private.residency(v_user,e.residency_terms) then raise exception 'Active membership and consecutive completed residency terms are required.'; end if;
  insert into app_private.eligible_voters values(e.id,v_user,u,app_private.required(p_data,'reason')) on conflict(election_id,user_id) do update set confirmed_by=u,reason=excluded.reason; v_id:=e.id;
 when 'cast_ballot' then
  perform app_private.require_member();
  select * into e from app_private.elections where id=(p_data->>'election_id')::uuid for update;
  if not found or e.status<>'voting' or e.method<>'digital' or now()<e.voting_opens or now()>e.voting_closes then raise exception 'Voting is not open.'; end if;
  if not exists(select 1 from app_private.eligible_voters where election_id=e.id and user_id=u) then raise exception 'You are not a confirmed eligible voter.' using errcode='42501'; end if;
  if jsonb_typeof(p_data->'choices') is distinct from 'object' or (select count(*) from jsonb_object_keys(p_data->'choices'))<>cardinality(e.positions) then raise exception 'Complete one choice per position.'; end if;
  foreach position_name in array e.positions loop
   choice:=p_data->'choices'->>position_name;
   if not exists(select 1 from app_private.candidates where id=choice::uuid and election_id=e.id and position=position_name and confirmed) then raise exception 'Invalid candidate choice.'; end if;
  end loop;
  insert into app_private.voter_participation values(e.id,u);
  insert into app_private.ballots(election_id,choices) values(e.id,p_data->'choices');
  -- Never log ballot payloads or return a ballot identifier.
  return jsonb_build_object('accepted',true);
 when 'set_election_status' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'Election not found.'; end if;perform app_private.check_version(e.version,p_data);
  if not e.rules_validated then raise exception 'Validate the election rules before proceeding.'; end if;
  if not ((e.status='draft' and p_data->>'status'='announced' and now()>=e.announces_at)
   or (e.status='announced' and p_data->>'status'='voting' and now() between e.voting_opens and e.voting_closes)
   or (e.status='voting' and p_data->>'status'='closed' and now()>=e.voting_closes)
   or (e.status in ('confirmed','requires_revote') and p_data->>'status'='archived')) then raise exception 'Invalid election transition or deadline.'; end if;
  if p_data->>'status'='voting' then
   if not exists(select 1 from app_private.eligible_voters where election_id=e.id) then raise exception 'Confirm the voter roll first.'; end if;
   foreach position_name in array e.positions loop
    if not exists(select 1 from app_private.candidates where election_id=e.id and position=position_name and confirmed) then raise exception 'Confirm candidates for each position first.'; end if;
   end loop;
  end if;
  update app_private.elections set status=p_data->>'status',version=version+1 where id=e.id;v_id:=e.id;
 when 'record_external_results' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found or e.method='digital' or e.status<>'closed' then raise exception 'Closed external/paper election required.'; end if;
  perform app_private.check_version(e.version,p_data);
  total:=(select count(*) from app_private.eligible_voters where election_id=e.id);n:=(p_data->>'turnout')::integer;
  if n<0 or n>total then raise exception 'Turnout exceeds eligible voters.'; end if;
  if jsonb_typeof(p_data->'results') is distinct from 'array' then raise exception 'Enter candidate tallies.';end if;
  if jsonb_array_length(p_data->'results')<>(select count(*) from app_private.candidates where election_id=e.id and confirmed)
   or (select count(distinct v->>'id') from jsonb_array_elements(p_data->'results') v)<>jsonb_array_length(p_data->'results') then raise exception 'Include each confirmed candidate once.';end if;
  for tally in select value from jsonb_array_elements(p_data->'results') loop
   if not exists(select 1 from app_private.candidates where id=(tally->>'id')::uuid and election_id=e.id and confirmed)
    or coalesce(tally->>'votes','')!~'^[0-9]+$' or (tally->>'votes')::numeric>n then raise exception 'Invalid candidate tally.';end if;
  end loop;
  if exists(select 1 from jsonb_array_elements(p_data->'results') v join app_private.candidates c on c.id=(v->>'id')::uuid group by c.position having sum((v->>'votes')::numeric)>n) then raise exception 'Position vote totals exceed turnout.';end if;
  update app_private.elections set external_turnout=n,external_results=p_data->'results',confirmation=app_private.required(p_data,'verification'),version=version+1 where id=e.id;v_id:=e.id;
 when 'confirm_results' then
  select * into e from app_private.elections where id=(p_data->>'id')::uuid for update;
  if not found or e.status<>'closed' then raise exception 'Close voting before confirming results.'; end if;
  perform app_private.check_version(e.version,p_data);
  total:=(select count(*) from app_private.eligible_voters where election_id=e.id);
  n:=case when e.method='digital' then (select count(*) from app_private.voter_participation where election_id=e.id) else e.external_turnout end;
  if n is null or total=0 then raise exception 'Verified turnout and voter roll required.'; end if;
  update app_private.elections set status=case when 100.0*n/total<e.threshold then 'requires_revote' else 'confirmed' end,confirmed_by=u,confirmation=app_private.required(p_data,'confirmation'),version=version+1 where id=e.id;v_id:=e.id;
 when 'record_leadership' then
  insert into app_private.leadership_records(election_id,user_id,position,kind,starts_on,ends_on,documentation,created_by)
  values(nullif(p_data->>'election_id','')::uuid,nullif(p_data->>'user_id','')::uuid,app_private.required(p_data,'position',160),p_data->>'kind',nullif(p_data->>'starts_on','')::date,nullif(p_data->>'ends_on','')::date,app_private.required(p_data,'documentation'),u) returning id into v_id;
 else raise exception 'Unknown election action.';
 end case;
 perform app_private.note(9,v_id,p_action,p_data); return jsonb_build_object('id',v_id);end
$$;
create function public.election_summary(p_id uuid) returns jsonb language plpgsql security definer set search_path='' as $$
 declare e app_private.elections;n integer;total integer;tallies jsonb;begin
 perform app_private.require_user();select * into e from app_private.elections where id=p_id;
 if not found or not (app_private.can('elections') or (app_private.member() and e.status in ('confirmed','requires_revote','archived'))) then raise exception 'Results are restricted until official confirmation.' using errcode='42501';end if;
 total:=(select count(*) from app_private.eligible_voters where election_id=p_id);
 n:=case when e.method='digital' then (select count(*) from app_private.voter_participation where election_id=p_id) else e.external_turnout end;
 if e.status in ('closed','confirmed','requires_revote','archived') then
  if e.method='digital' then select coalesce(jsonb_agg(t),'[]') into tallies from (select c.id,c.position,c.user_id,count(b.id) as votes from app_private.candidates c left join app_private.ballots b on b.election_id=c.election_id and b.choices->>c.position=c.id::text where c.election_id=p_id and c.confirmed group by c.id) t;
  else tallies:=e.external_results; end if;
 end if;
 return jsonb_build_object('eligible',total,'participation',n,'rate',round(100.0*n/nullif(total,0),2),'threshold',e.threshold,'status',e.status,'tallies',tallies);end
$$;
do $$declare t text;begin foreach t in array array['academic_terms','member_terms','elections','candidates','eligible_voters','ballots','voter_participation','leadership_records'] loop
 execute format('alter table app_private.%I enable row level security',t);
 execute format('revoke all on app_private.%I from public,anon,authenticated',t);
 if t<>'ballots' then execute format('grant select on app_private.%I to authenticated',t);end if;
end loop;end $$;
create policy terms_read on app_private.academic_terms for select to authenticated using(app_private.member() or app_private.can('elections'));
create policy member_terms_read on app_private.member_terms for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy election_read on app_private.elections for select to authenticated using(app_private.can('elections') or (app_private.member() and status<>'draft'));
create policy candidate_read on app_private.candidates for select to authenticated using(app_private.can('elections') or (app_private.member() and (confirmed or user_id=auth.uid() or nominated_by=auth.uid())));
create policy voters_read on app_private.eligible_voters for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy participation_read on app_private.voter_participation for select to authenticated using(app_private.can('elections') or (app_private.active_user() and user_id=auth.uid()));
create policy leadership_read on app_private.leadership_records for select to authenticated using(app_private.can('elections') or app_private.can('administration'));
revoke all on function app_private.election_command(text,jsonb),app_private.residency(uuid,integer) from public,anon,authenticated;
revoke all on function public.election_summary(uuid) from public,anon;grant execute on function public.election_summary(uuid) to authenticated;
commit;
