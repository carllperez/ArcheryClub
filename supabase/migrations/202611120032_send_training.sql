-- Send a whole training to the coach in one step: every personal or returned round the member owns among the
-- given ids goes to pending, all or none. The coach still validates each round.
begin;

create function public.submit_training_session(p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v_id uuid; n integer:=0;
begin
 perform app_private.require_user();
 if coalesce(array_length(p_ids,1),0) not between 1 and 50 then raise exception 'Send 1 to 50 rounds at a time.'; end if;
 foreach v_id in array p_ids loop
  perform app_private.training_command('submit_training',jsonb_build_object('id',v_id));
  n:=n+1;
 end loop;
 return jsonb_build_object('sent',n);
end $$;
revoke all on function public.submit_training_session(uuid[]) from public,anon;
grant execute on function public.submit_training_session(uuid[]) to authenticated;

commit;
