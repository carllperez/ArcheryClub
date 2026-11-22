-- Students from other schools can apply for membership (23 Nov). Each application now records the applicant's school;
-- existing applications are DLSU (the default). DLSU keeps its 8-digit ID number; other schools' IDs vary, so those
-- accept 4 to 20 letters, digits or dashes. Everything else is the same as migration 038.
begin;

alter table app_private.applications add column school text not null default 'De La Salle University'
 check(length(school) between 1 and 120);

create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
 v_phone text:=trim(coalesce(p_data->>'phone','')); v_id text:=trim(coalesce(p_data->>'student_number',''));
 v_school text:=coalesce(nullif(trim(coalesce(p_data->>'school','')),''),'De La Salle University');
 v_dlsu boolean;
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_school)>120 then raise exception 'Keep the school name under 120 characters.'; end if;
 v_dlsu:=lower(v_school) in ('de la salle university','dlsu','dlsu manila');
 if v_dlsu then v_school:='De La Salle University'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_dlsu and v_id<>'' and v_id !~ '^[0-9]{8}$' then raise exception 'Your DLSU ID number is 8 digits.'; end if;
 if not v_dlsu and v_id<>'' and v_id !~ '^[A-Za-z0-9-]{4,20}$' then raise exception 'Your student ID number is 4 to 20 letters, numbers or dashes.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_phone='' or v_id='') then
  raise exception 'Add your mobile number and % before sending.',case when v_dlsu then 'DLSU ID number' else 'student ID number' end; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,school=v_school,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

commit;
