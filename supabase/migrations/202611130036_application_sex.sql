-- Sex on the application and profile (archery events are split into men's and women's divisions),
-- and Alumni as a year level (the app's list). Same save path as migration 035.
begin;

alter table app_private.applications add column sex text not null default '' check(sex in ('','Male','Female'));
alter table app_private.profiles add column sex text not null default '' check(sex in ('','Male','Female'));

create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_phone(v_ec_phone) then raise exception 'Enter a valid emergency contact number.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

create or replace function app_private.application_to_profile() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if new.status='approved' and old.status is distinct from 'approved' then
  update app_private.profiles set
   college=case when college='' then new.college else college end,
   sex=case when sex='' then new.sex else sex end,
   program=case when program='' then new.program else program end,
   year_level=case when year_level='' then new.year_level else year_level end,
   emergency_contact_name=case when emergency_contact_name='' then new.emergency_contact_name else emergency_contact_name end,
   emergency_contact_phone=case when emergency_contact_phone='' then new.emergency_contact_phone else emergency_contact_phone end,
   updated_at=now()
  where id=new.user_id;
 end if;
 return new;
end $$;

commit;
