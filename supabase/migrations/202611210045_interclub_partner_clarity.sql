-- Interclub run, 21 Nov testing.
--  * A concern's reply reached nobody: the partner rep's concern simply vanished once the officer resolved it.
--    Resolving now sends the rep a notice with the reply (the app also shows answered concerns on the event page).
--  * "Responsibility" was required when giving an archer a target, which makes no sense for an archer. It is now
--    optional. Only that one expression in interclub_command changes: the live definition is read, checked to contain
--    exactly the expected text, and re-created with the replacement, so nothing else in the function is retyped.
--  * The invite code list said "Used by Club member": officers can't read a partner rep's profile. The invite now
--    keeps the name the rep gave when claiming it.
begin;

-- 1. The rep hears back.
create or replace function app_private.concern_reply_notice() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.status='resolved' and old.status is distinct from 'resolved' then
  perform app_private.notify(new.submitted_by,15,new.event_id,'interclub',
   'DLSU replied to your concern about '||coalesce((select shared_title from app_private.interclub_events where event_id=new.event_id),'the event')
   ||': '||new.resolution);
 end if;
 return new;
end $$;
revoke all on function app_private.concern_reply_notice() from public,anon,authenticated;
drop trigger if exists concern_reply_notice on app_private.interclub_concerns;
create trigger concern_reply_notice after update on app_private.interclub_concerns
 for each row execute function app_private.concern_reply_notice();

-- 2. Responsibility optional when assigning a target.
do $do$
declare d text; v_old text:='app_private.required(p_data,''responsibility'')'; v_new text:='coalesce(p_data->>''responsibility'','''')';
begin
 d:=pg_get_functiondef('app_private.interclub_command(text,jsonb)'::regprocedure);
 if (length(d)-length(replace(d,v_old,'')))/length(v_old)<>1 then
  raise exception 'interclub_command is not the expected version (responsibility check not found exactly once); nothing changed.';
 end if;
 execute replace(d,v_old,v_new);
end $do$;

-- 3. The rep's name on the invite they used.
alter table app_private.partner_invites add column if not exists claimed_name text not null default '';
create or replace function app_private.invite_claimed_name() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.claimed_by is not null and (tg_op='INSERT' or old.claimed_by is distinct from new.claimed_by) then
  new.claimed_name:=coalesce((select full_name from app_private.profiles where id=new.claimed_by),'');
 end if;
 return new;
end $$;
revoke all on function app_private.invite_claimed_name() from public,anon,authenticated;
drop trigger if exists invite_claimed_name on app_private.partner_invites;
create trigger invite_claimed_name before insert or update on app_private.partner_invites
 for each row execute function app_private.invite_claimed_name();
update app_private.partner_invites v set claimed_name=p.full_name from app_private.profiles p
 where p.id=v.claimed_by and v.claimed_name='';

commit;
