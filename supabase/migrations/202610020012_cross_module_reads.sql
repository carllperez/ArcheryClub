begin;
drop policy event_read on app_private.events;
create policy event_read on app_private.events for select to authenticated using(
 app_private.can('activities') or (published and (app_private.member() or app_private.can('equipment') or app_private.can('training') or app_private.can('interclub')))
);
drop policy partner_read on app_private.partner_clubs;
create policy partner_read on app_private.partner_clubs for select to authenticated using(
 app_private.can('interclub') or exists(select 1 from app_private.event_access a where a.partner_id=partner_clubs.id and a.revoked_at is null
 and (app_private.event_staff(a.event_id) or (a.user_id=auth.uid() and app_private.active_user())))
);
commit;
