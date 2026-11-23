-- 060: an election can be run from start to finish in one sitting (23 Nov feedback: "The election process is complicated
-- and the instructions are a bit unclear. I wasn't able to complete it in a single session").
-- What stopped it:
--  * every stage waited for a date: announcing waited for "announces at", voting for "voting opens", closing for
--    "voting closes", so an officer who set a normal timetable had to come back days later;
--  * voters were added one by one, each with a typed reason.
-- Now:
--  * advance_election moves the election to its next stage now, pulling that stage's date forward (and keeping the
--    timetable's order), then makes the same set_election_status change as before, so every existing check still
--    applies (rules validated, a voter roll, a confirmed candidate per position) and the audit trail is the same.
--    Closing voting early while some voters haven't voted needs a reason, which is kept in the audit log;
--  * add_all_eligible_voters puts every Active member who meets the election's residency rule on the roll at once,
--    and says how many were left off by that rule.
-- Unchanged on purpose: results are still confirmed by a different election officer from the one who closed voting.

create function public.advance_election(p_id uuid,p_reason text default '') returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.elections; v_next text; v_early boolean:=false; v_left int:=0;
begin
 if not app_private.can('elections') then raise exception 'Permission denied: only an election officer can move the election on.' using errcode='42501'; end if;
 select * into e from app_private.elections where id=p_id and club_id is not distinct from app_private.current_club() for update;
 if not found then raise exception 'Election not found.'; end if;
 if not e.rules_validated then raise exception 'Confirm the election rules first (Edit details and dates).'; end if;
 case e.status
 when 'draft' then
  v_next:='announced';
  if e.candidacy_closes<=now() then raise exception 'The candidacy deadline has already passed. Edit the dates first.'; end if;
  update app_private.elections set announces_at=least(announces_at,now()) where id=e.id;
 when 'announced' then
  v_next:='voting'; v_early:=now()<e.candidacy_closes;
  update app_private.elections set candidacy_closes=least(candidacy_closes,now()),
   voting_opens=least(voting_opens,now()),
   voting_closes=greatest(voting_closes,now()+interval '1 hour'),
   result_due=greatest(result_due,voting_closes,now()+interval '1 hour') where id=e.id;
 when 'voting' then
  v_next:='closed'; v_early:=now()<e.voting_closes;
  select count(*) into v_left from app_private.eligible_voters v where v.election_id=e.id
   and not exists(select 1 from app_private.voter_participation p where p.election_id=e.id and p.user_id=v.user_id);
  if v_early and v_left>0 and length(trim(coalesce(p_reason,'')))<3 then
   raise exception '% voter(s) haven''t voted yet. Say why voting closes early.',v_left; end if;
  update app_private.elections set voting_closes=least(voting_closes,now()) where id=e.id;
 else raise exception 'Nothing to move on: this election is %.',e.status;
 end case;
 select * into e from app_private.elections where id=p_id;
 perform public.club_command('set_election_status',jsonb_build_object('id',e.id,'version',e.version,'status',v_next));
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,9,e.id,'advance_election',jsonb_build_object('to',v_next,'early',v_early,'reason',trim(coalesce(p_reason,'')),'not_voted',v_left),e.club_id);
 return jsonb_build_object('status',v_next,'early',v_early);
end $$;

create function public.add_all_eligible_voters(p_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); e app_private.elections; m uuid; v_added int:=0; v_short int:=0; v_already int:=0;
begin
 if not app_private.can('elections') then raise exception 'Permission denied: only an election officer can build the voter list.' using errcode='42501'; end if;
 select * into e from app_private.elections where id=p_id and club_id is not distinct from app_private.current_club() for update;
 if not found then raise exception 'Election not found.'; end if;
 if e.status not in ('draft','announced') then raise exception 'The voter list is locked once voting opens.'; end if;
 for m in select ms.user_id from app_private.memberships ms join app_private.profiles p on p.id=ms.user_id
          where ms.status='Active' and p.club_id is not distinct from e.club_id loop
  if exists(select 1 from app_private.eligible_voters where election_id=e.id and user_id=m) then v_already:=v_already+1;
  elsif app_private.residency(m,e.residency_terms) then
   insert into app_private.eligible_voters(election_id,user_id,confirmed_by,reason,club_id)
   values(e.id,m,u,'Active member meeting the residency rule (added with everyone)',e.club_id);
   v_added:=v_added+1;
  else v_short:=v_short+1; end if;
 end loop;
 insert into app_private.audit_log(actor,module,record_id,action,detail,club_id)
  values(u,9,e.id,'add_all_eligible_voters',jsonb_build_object('added',v_added,'short_of_residency',v_short),e.club_id);
 return jsonb_build_object('added',v_added,'already',v_already,'short_of_residency',v_short,'residency_terms',e.residency_terms);
end $$;

revoke all on function public.advance_election(uuid,text) from public,anon;
grant execute on function public.advance_election(uuid,text) to authenticated;
revoke all on function public.add_all_eligible_voters(uuid) from public,anon;
grant execute on function public.add_all_eligible_voters(uuid) to authenticated;
grant execute on all functions in schema app_private, public to club_api;
notify pgrst, 'reload schema';
