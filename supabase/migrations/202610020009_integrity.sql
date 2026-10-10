begin;
alter table app_private.profiles add constraint profile_lengths check(length(full_name)<=160 and length(phone)<=40);
alter table app_private.applications add constraint application_lengths check(length(full_name)<=160 and length(phone)<=40 and length(student_number)<=80 and length(experience)<=4000);
alter table app_private.settings add constraint categories_not_empty check(cardinality(membership_categories)>0);
alter table app_private.elections add constraint elected_positions check(cardinality(positions)>0 and positions <@ array['President','Vice President']);
alter table app_private.interclub_events add constraint shared_period check(shared_end>shared_start);

-- Only a trusted video verifier can write the duration. Authenticated clients cannot forge it.
create function public.record_video_verification(p_path text,p_owner uuid,p_duration_ms integer) returns void language plpgsql security definer set search_path='' as $$
begin
 if not exists(select 1 from storage.objects where bucket_id='club-documents' and name=p_path and owner_id=p_owner::text and metadata->>'mimetype'='video/mp4')
 or split_part(p_path,'/',1)<>p_owner::text or split_part(p_path,'/',2)<>'11' then raise exception 'Training video not found.';end if;
 insert into app_private.video_verifications(path,duration_ms) values(p_path,p_duration_ms)
 on conflict(path) do update set duration_ms=excluded.duration_ms,verified_at=now();
end $$;
revoke all on function public.record_video_verification(text,uuid,integer) from public,anon,authenticated;
grant execute on function public.record_video_verification(text,uuid,integer) to service_role;
commit;
