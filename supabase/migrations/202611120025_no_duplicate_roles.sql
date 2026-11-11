-- A person holds a role once. Assigning a role someone already holds (and that is not about to
-- end) is refused; renewing is allowed in the last 30 days of a term or after it ends.
begin;

create or replace function app_private.no_duplicate_role() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_end timestamptz; v_role text;
begin
 select ra.ends_at into v_end from app_private.role_assignments ra
  where ra.user_id=new.user_id and ra.role_id=new.role_id and ra.retired_at is null and ra.ends_at>now()+interval '30 days'
  order by ra.ends_at desc limit 1;
 if found then
  select name into v_role from app_private.roles where id=new.role_id;
  raise exception 'This person is already % until %. Renew the term in its last 30 days instead.', v_role, to_char(v_end,'Mon FMDD, YYYY');
 end if;
 return new;
end $$;
revoke all on function app_private.no_duplicate_role() from public,anon,authenticated;

create trigger no_duplicate_role before insert on app_private.role_assignments for each row execute function app_private.no_duplicate_role();

commit;
