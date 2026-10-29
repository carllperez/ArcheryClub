begin;

alter table app_private.announcements
 add column version bigint not null default 1,
 add column updated_at timestamptz,
 add column updated_by uuid references app_private.profiles;

-- An announcement must not disclose an unpublished activity to every member.
create function app_private.guard_announcement_event() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.event_id is not null and not exists (
  select 1 from app_private.events where id=new.event_id and published and not archived
 ) then raise exception 'Choose a published, active activity or leave the activity empty.';end if;
 return new;
end $$;
create trigger announcement_event before insert or update on app_private.announcements
for each row execute function app_private.guard_announcement_event();
revoke all on function app_private.guard_announcement_event() from public,anon,authenticated;

create function app_private.update_announcement(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare a app_private.announcements;begin
 perform app_private.require_permission('activities');
 select * into a from app_private.announcements where id=(p_data->>'id')::uuid for update;
 if not found then raise exception 'Announcement not found.';end if;
 perform app_private.check_version(a.version,p_data);
 update app_private.announcements set title=app_private.required(p_data,'title',200),
 message=app_private.required(p_data,'message'),event_id=nullif(p_data->>'event_id','')::uuid,
 version=version+1,updated_at=now(),updated_by=auth.uid() where id=a.id;
 -- Reopen the existing notice, including for a correction that retains its title.
 update app_private.notifications set message='Updated announcement: '||(p_data->>'title'),
  acknowledged_at=null,completed_at=null,notified_at=now()
 where record_id=a.id and kind='announcement';
 insert into app_private.notifications(user_id,module,record_id,kind,message)
 select m.user_id,3,a.id,'announcement','Updated announcement: '||(p_data->>'title')
 from app_private.memberships m where m.status='Active' and not exists (
  select 1 from app_private.notifications n where n.user_id=m.user_id and n.record_id=a.id and n.kind='announcement'
 );
 perform app_private.note(8,a.id,'update_announcement',jsonb_build_object('before',to_jsonb(a),'after',p_data));
 return jsonb_build_object('id',a.id);
end $$;
revoke all on function app_private.update_announcement(jsonb) from public,anon,authenticated;

create or replace function public.club_command(p_action text,p_data jsonb default '{}') returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_user();
 if p_action='update_election' then return app_private.update_election(p_data);end if;
 if p_action='update_announcement' then return app_private.update_announcement(p_data);end if;
 return app_private.club_command_before_completion(p_action,p_data);
end $$;
revoke all on function public.club_command(text,jsonb) from public,anon;
grant execute on function public.club_command(text,jsonb) to authenticated;
commit;
