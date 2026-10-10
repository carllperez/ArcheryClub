begin;
alter function public.club_report(date,date) rename to club_report_data;
revoke all on function public.club_report_data(date,date) from public,anon,authenticated;
create function public.club_report(p_from date,p_to date) returns jsonb language plpgsql security definer set search_path='' as $$
declare result jsonb;g app_private.goals;data jsonb;actual numeric;goals jsonb:='[]';begin
 perform app_private.require_permission('reports');
 result:=public.club_report_data(p_from,p_to);
 for g in select * from app_private.goals where starts_on<=p_to and ends_on>=p_from loop
  data:=public.club_report_data(g.starts_on,g.ends_on);actual:=null;
  case g.metric
   when 'active_members' then actual:=(data->'membership'->>'active')::numeric;
   when 'applicant_conversion' then actual:=(data->'membership'->>'conversion_rate')::numeric;
   when 'attendance_rate' then select round(100.0*sum((v->>'present')::numeric)/nullif(sum((v->>'registrations')::numeric),0),2) into actual from jsonb_array_elements(coalesce(data->'activities','[]')) v;
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
revoke all on function public.club_report(date,date) from public,anon;
grant execute on function public.club_report(date,date) to authenticated;
commit;
