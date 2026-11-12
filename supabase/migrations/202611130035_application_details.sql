-- The application asks for college, program, year level and an emergency contact.
-- They're saved with the application (the existing save/submit runs first, unchanged), are required to send it,
-- show to the membership officer, and copy into the member's profile when the application is approved.
begin;

alter table app_private.applications
 add column college text not null default '' check(length(college)<=60),
 add column program text not null default '' check(length(program)<=120),
 add column year_level text not null default '' check(length(year_level)<=40),
 add column emergency_contact_name text not null default '' check(length(emergency_contact_name)<=160),
 add column emergency_contact_phone text not null default '' check(length(emergency_contact_phone)<=40);
alter table app_private.profiles add column college text not null default '' check(length(college)<=60);

create function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='') then
  raise exception 'Add your college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;
revoke all on function public.save_application_details(text,jsonb) from public,anon;
grant execute on function public.save_application_details(text,jsonb) to authenticated;

-- Approval carries the answers into the profile (only where the profile is still empty).
create function app_private.application_to_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.status='approved' and old.status is distinct from 'approved' then
  update app_private.profiles set
   college=case when college='' then new.college else college end,
   program=case when program='' then new.program else program end,
   year_level=case when year_level='' then new.year_level else year_level end,
   emergency_contact_name=case when emergency_contact_name='' then new.emergency_contact_name else emergency_contact_name end,
   emergency_contact_phone=case when emergency_contact_phone='' then new.emergency_contact_phone else emergency_contact_phone end,
   updated_at=now()
  where id=new.user_id;
 end if;
 return new;
end $$;
create trigger application_to_profile after update of status on app_private.applications for each row execute function app_private.application_to_profile();

commit;
