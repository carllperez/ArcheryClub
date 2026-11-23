-- 061: a stuck election can be rescheduled or cancelled (23 Nov testing). All three live elections were stuck in
-- "Candidacy closed": a position with no candidate (nobody may run once candidacy closes, and voting needs a confirmed
-- candidate per position) or an empty voter list (their residency rule left every member off), with no way out.
--  * reschedule_election: an announced election gets a new timetable from now (candidacy open again for N days, then
--    voting for M days) and, if the officer chooses, a new residency rule. A reason is required and kept.
--  * cancel_election: an election that hasn't counted a ballot is archived with a reason, so it stops showing as current.

create function public.reschedule_election(p_id uuid,p_candidacy_days int,p_voting_days int,p_residency int,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.elections; v_cc timestamptz; v_vc timestamptz; m uuid; n int:=0;
begin
 if not app_private.can('elections') then raise exception 'Permission denied: only an election officer can reschedule.' using errcode='42501'; end if;
 select * into e from app_private.elections where id=p_id and club_id is not distinct from app_private.current_club() for update;
 if not found then raise exception 'Election not found.'; end if;
 if e.status<>'announced' then raise exception 'Only an election in its candidacy stage can be rescheduled. A draft is edited instead.'; end if;
 if length(trim(coalesce(p_reason,'')))<3 then raise exception 'Say why the election is rescheduled.'; end if;
 if coalesce(p_candidacy_days,0) not between 1 and 30 or coalesce(p_voting_days,0) not between 1 and 30 then raise exception 'Pick 1 to 30 days for candidacy and for voting.'; end if;
 if p_residency is not null and p_residency not between 0 and 8 then raise exception 'Residency is 0 to 8 completed terms.'; end if;
 v_cc:=date_trunc('minute',now())+make_interval(days=>p_candidacy_days); v_vc:=v_cc+make_interval(days=>p_voting_days);
 update app_private.elections set candidacy_closes=v_cc, voting_opens=v_cc, voting_closes=v_vc, result_due=v_vc+interval '2 days',
  residency_terms=coalesce(p_residency,residency_terms), version=version+1 where id=e.id;
 for m in select ms.user_id from app_private.memberships ms join app_private.profiles p on p.id=ms.user_id where ms.status='Active' and p.club_id is not distinct from e.club_id loop
  insert into app_private.notifications(user_id,module,record_id,kind,message,club_id)
  values(m,9,e.id,'election',e.title||': candidacy is open again until '||to_char(v_cc at time zone 'Asia/Manila','Mon DD, HH12:MI AM')||'. You can run or nominate someone.',e.club_id);
  n:=n+1;
 end loop;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,9,e.id,'reschedule_election',jsonb_build_object('reason',trim(p_reason),'candidacy_closes',v_cc,'voting_closes',v_vc,
   'residency_before',e.residency_terms,'residency_after',coalesce(p_residency,e.residency_terms)),e.club_id);
 return jsonb_build_object('candidacy_closes',v_cc,'voting_closes',v_vc,'notified',n);
end $$;

create function public.cancel_election(p_id uuid,p_reason text) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.elections;
begin
 if not app_private.can('elections') then raise exception 'Permission denied: only an election officer can cancel an election.' using errcode='42501'; end if;
 select * into e from app_private.elections where id=p_id and club_id is not distinct from app_private.current_club() for update;
 if not found then raise exception 'Election not found.'; end if;
 if e.status not in ('draft','announced','voting') then raise exception 'This election is already %; it can''t be cancelled.',e.status; end if;
 if exists(select 1 from app_private.voter_participation where election_id=e.id) then raise exception 'Ballots have been cast. Close voting and confirm the results instead.'; end if;
 if length(trim(coalesce(p_reason,'')))<3 then raise exception 'Say why the election is cancelled.'; end if;
 update app_private.elections set status='archived', confirmation='Cancelled: '||trim(p_reason), version=version+1 where id=e.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,9,e.id,'cancel_election',jsonb_build_object('reason',trim(p_reason),'was',e.status),e.club_id);
 return jsonb_build_object('status','archived');
end $$;

revoke all on function public.reschedule_election(uuid,int,int,int,text) from public,anon;
grant execute on function public.reschedule_election(uuid,int,int,int,text) to authenticated;
revoke all on function public.cancel_election(uuid,text) from public,anon;
grant execute on function public.cancel_election(uuid,text) to authenticated;
grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
