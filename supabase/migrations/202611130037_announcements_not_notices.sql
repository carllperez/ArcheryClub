-- Announcements have their own unread/read (migration 034), so they no longer also land in each member's Notices.
-- An edited announcement turns unread again instead. Notices stay for personal alerts (application, payments, borrowing). Existing announcement notices are closed so the
-- Profile count only shows personal ones; the announcements themselves are untouched.
begin;

create function app_private.skip_announcement_notices() returns trigger language plpgsql set search_path='' as $$
begin
 if new.kind='announcement' then return null; end if;
 return new;
end $$;
create trigger skip_announcement_notices before insert on app_private.notifications for each row execute function app_private.skip_announcement_notices();

update app_private.notifications set completed_at=now() where kind='announcement' and completed_at is null;

-- An edited announcement (new title or message) shows as unread again, which is what its edit notice used to do.
create function app_private.unread_after_edit() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.title is distinct from old.title or new.message is distinct from old.message then
  delete from app_private.announcement_reads where announcement_id=new.id; end if;
 return new;
end $$;
create trigger unread_after_edit after update on app_private.announcements for each row execute function app_private.unread_after_edit();

commit;
