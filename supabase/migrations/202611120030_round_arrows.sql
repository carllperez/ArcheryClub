-- Rounds keep their arrows. A round can carry its ends and each arrow's score (X, 10..1, M), plus where it hit
-- when a scoring app exported positions. When arrows are sent, the server works out the score and maximum
-- from them, so the total can never disagree with the arrows. Rounds without arrows keep working as before.
begin;

alter table app_private.training_records add column arrows jsonb;

create function app_private.arrow_totals(p jsonb, out score integer, out arrows integer)
language plpgsql immutable set search_path='' as $$
declare e jsonb; a jsonb; v text;
begin
 score:=0; arrows:=0;
 if jsonb_typeof(p->'ends') is distinct from 'array' or jsonb_array_length(p->'ends') not between 1 and 60 then
  raise exception 'A round needs 1 to 60 ends.'; end if;
 for e in select value from jsonb_array_elements(p->'ends') loop
  if jsonb_typeof(e) is distinct from 'array' or jsonb_array_length(e) not between 1 and 24 then
   raise exception 'Each end needs 1 to 24 arrows.'; end if;
  for a in select value from jsonb_array_elements(e) loop
   v:=upper(coalesce(a->>'p',''));
   if v not in ('X','10','9','8','7','6','5','4','3','2','1','M') then raise exception 'Arrow scores are X, 10 to 1, or M.'; end if;
   if (a ? 'x' and jsonb_typeof(a->'x')<>'number') or (a ? 'y' and jsonb_typeof(a->'y')<>'number') then
    raise exception 'Arrow positions must be numbers.'; end if;
   score:=score+case v when 'X' then 10 when 'M' then 0 else v::integer end;
   arrows:=arrows+1;
  end loop;
 end loop;
end $$;
revoke all on function app_private.arrow_totals(jsonb) from public,anon,authenticated;

-- Save a round (coach: save_training, member: save_personal_training) with or without arrows; optionally send it
-- to the coach in the same step. Permissions are the training commands' own.
create function public.save_round(p_action text,p_data jsonb,p_submit boolean default false) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_score integer; v_count integer; v_data jsonb:=p_data-'arrows'; v_result jsonb; v_id uuid;
 v_arrows jsonb:=nullif(p_data->'arrows','null'::jsonb);
begin
 perform app_private.require_user();
 if p_action not in ('save_training','save_personal_training') then raise exception 'Unknown action.'; end if;
 if v_arrows is not null then
  select score,arrows into v_score,v_count from app_private.arrow_totals(v_arrows);
  v_data:=v_data||jsonb_build_object('score',v_score,'maximum_score',v_count*10);
 end if;
 v_result:=app_private.training_command(p_action,v_data);
 v_id:=(v_result->>'id')::uuid;
 if p_data ? 'arrows' then update app_private.training_records set arrows=v_arrows where id=v_id; end if;
 if p_submit then perform app_private.training_command('submit_training',jsonb_build_object('id',v_id)); end if;
 return v_result;
end $$;
revoke all on function public.save_round(text,jsonb,boolean) from public,anon;
grant execute on function public.save_round(text,jsonb,boolean) to authenticated;

-- The coach's CSV import, arrows included. All rows save or none do.
create function public.import_rounds(p_rows jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare entry jsonb; results jsonb:='[]';
begin
 perform app_private.require_user();
 perform app_private.require_permission('training');
 if jsonb_typeof(p_rows) is distinct from 'array' or jsonb_array_length(p_rows) not between 1 and 500 then
  raise exception 'Import 1 to 500 rounds at a time.'; end if;
 for entry in select value from jsonb_array_elements(p_rows) loop
  perform app_private.required(entry,'import_key',200);
  if exists(select 1 from app_private.training_records where import_key=entry->>'import_key') then
   raise exception 'This file was already imported (%). Nothing was saved.',entry->>'import_key'; end if;
  results:=results||jsonb_build_array(public.save_round('save_training',entry,false));
 end loop;
 return results;
end $$;
revoke all on function public.import_rounds(jsonb) from public,anon;
grant execute on function public.import_rounds(jsonb) to authenticated;

commit;
