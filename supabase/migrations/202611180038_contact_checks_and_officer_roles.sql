-- Findings from group testing (18 Nov 2026):
--  * Mobile numbers are Philippine mobiles (09XXXXXXXXX or +639XXXXXXXXX) everywhere: the applicant's own number,
--    the emergency contact, and a member editing their profile. The application and profile both require one.
--  * The DLSU ID number is exactly 8 digits and is required to send an application.
--  * New accounts that haven't applied have no name, so admins saw "Club record" for all of them. Profiles now
--    keep the sign-in email (visible only to the owner, membership officers and administrators, as before).
--  * An officer role can only go to an Active member. The Administrator role (administration permission) is
--    exempt, because the system administrator can be the adviser or a developer. Existing assignments are untouched.
begin;

create or replace function app_private.valid_mobile(p text) returns boolean language sql immutable set search_path='' as $$
 select coalesce(p,'')='' or regexp_replace(p,'[[:space:]().-]','','g') ~ '^(09[0-9]{9}|\+?639[0-9]{9})$'
$$;

-- Emails for naming accounts that haven't applied yet.
alter table app_private.profiles add column if not exists email text not null default '';
update app_private.profiles p set email=lower(u.email) from auth.users u where u.id=p.id and p.email='';

create or replace function public.initialize_account() returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=auth.uid(); v_email text;
begin
 select lower(email) into v_email from auth.users where id=u and email_confirmed_at is not null;
 if u is null or v_email is null then raise exception 'Verify your email before continuing.' using errcode='42501'; end if;
 insert into app_private.profiles(id,email) values(u,v_email) on conflict(id) do update set email=excluded.email where app_private.profiles.email<>excluded.email;
 perform app_private.require_user(); perform app_private.note(14,u,'session_access');
 return jsonb_build_object('id',u);
end $$;

create or replace function public.complete_account_creation(p_request uuid,p_user uuid) returns void language plpgsql security definer set search_path='' as $$
declare r app_private.account_requests;
begin
 select * into r from app_private.account_requests where id=p_request and completed_at is null for update;
 if not found or not exists(select 1 from auth.users where id=p_user and lower(email)=r.email) then raise exception 'Account creation request does not match.'; end if;
 insert into app_private.profiles(id,full_name,email) values(p_user,r.full_name,r.email) on conflict do nothing;
 update app_private.account_requests set user_id=p_user,completed_at=now() where id=r.id;
 insert into app_private.audit_log(actor,module,record_id,action,detail) values(r.actor,14,p_user,'account_invited',jsonb_build_object('request_id',r.id));
end $$;

-- Application: same as migration 036, plus the mobile, emergency contact and ID number checks.
create or replace function public.save_application_details(p_action text,p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); result jsonb;
 v_college text:=trim(coalesce(p_data->>'college','')); v_program text:=trim(coalesce(p_data->>'program',''));
 v_year text:=trim(coalesce(p_data->>'year_level','')); v_ec_name text:=trim(coalesce(p_data->>'emergency_contact_name',''));
 v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone','')); v_sex text:=trim(coalesce(p_data->>'sex',''));
 v_phone text:=trim(coalesce(p_data->>'phone','')); v_id text:=trim(coalesce(p_data->>'student_number',''));
begin
 if p_action not in ('save_application','submit_application') then raise exception 'Unknown action.'; end if;
 if length(v_college)>60 or length(v_program)>120 or length(v_year)>40 or length(v_ec_name)>160 then raise exception 'One of your answers is too long.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_id<>'' and v_id !~ '^[0-9]{8}$' then raise exception 'Your DLSU ID number is 8 digits.'; end if;
 if (v_ec_name='') <> (v_ec_phone='') then raise exception 'Give both the emergency contact''s name and number, or leave both empty.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if v_sex not in ('','Male','Female') then raise exception 'Sex is Male or Female.'; end if;
 if p_action='submit_application' and (v_phone='' or v_id='') then raise exception 'Add your mobile number and DLSU ID number before sending.'; end if;
 if p_action='submit_application' and (v_college='' or v_program='' or v_year='' or v_sex='') then
  raise exception 'Add your sex, college, program and year level before sending.'; end if;
 result:=public.club_command(p_action,p_data);
 update app_private.applications set college=v_college,program=v_program,year_level=v_year,
  emergency_contact_name=v_ec_name,emergency_contact_phone=v_ec_phone,sex=v_sex where user_id=u returning to_jsonb(applications.*) into result;
 return result;
end $$;

-- Profile: same as live, but the mobile number is required and both numbers are Philippine mobiles.
create or replace function app_private.save_member_profile(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_phone text:=trim(coalesce(p_data->>'phone','')); v_ec_phone text:=trim(coalesce(p_data->>'emergency_contact_phone',''));
 v_name text; v_current text;
begin
 v_name:=app_private.required(p_data,'full_name',160);
 select full_name into v_current from app_private.profiles where id=u;
 if trim(v_name)<>trim(v_current) and exists(select 1 from app_private.memberships where user_id=u) then
  raise exception 'Your name was verified when your membership was approved. Request a name correction instead.'; end if;
 if v_phone='' then raise exception 'Enter your mobile number.'; end if;
 if not app_private.valid_mobile(v_phone) then raise exception 'Only Philippine mobile numbers are allowed: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if not app_private.valid_mobile(v_ec_phone) then raise exception 'The emergency contact''s number must be a Philippine mobile number: 09XX XXX XXXX or +63 9XX XXX XXXX.'; end if;
 if (v_ec_phone='') <> (trim(coalesce(p_data->>'emergency_contact_name',''))='') then raise exception 'Provide both the emergency contact name and number.'; end if;
 update app_private.profiles set full_name=v_name, phone=v_phone, emergency_contact_name=trim(coalesce(p_data->>'emergency_contact_name','')),
  emergency_contact_phone=v_ec_phone, program=left(trim(coalesce(p_data->>'program','')),120), year_level=left(trim(coalesce(p_data->>'year_level','')),40),
  dominant_hand=coalesce(p_data->>'dominant_hand',''), bow_type=coalesce(p_data->>'bow_type',''), draw_weight=nullif(p_data->>'draw_weight','')::numeric,
  arrow_length=nullif(p_data->>'arrow_length','')::numeric, experience_level=coalesce(p_data->>'experience_level',''), bio=trim(coalesce(p_data->>'bio','')), updated_at=now()
 where id=u;
 perform app_private.note(1,u,'save_profile',p_data);
 return jsonb_build_object('id',u);
end $$;

-- Roles: same as migration 016, plus the membership check on assign_role.
create or replace function app_private.admin_command(p_action text,p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
 declare u uuid:=app_private.require_user(); v_id uuid; begin
 perform app_private.require_permission('administration');
 -- Serialize access changes to protect against two concurrent last-admin retirements.
 perform pg_advisory_xact_lock(14001);
 case p_action
 when 'configure_club' then
  update app_private.settings set club_name=app_private.required(p_data,'club_name',160),
   application_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'application_requirements','[]'))),
   renewal_requirements=array(select jsonb_array_elements_text(coalesce(p_data->'renewal_requirements','[]'))),
   membership_categories=array(select jsonb_array_elements_text(coalesce(p_data->'membership_categories','["Regular member"]'))),
   retention_policy=app_private.required(p_data,'retention_policy'),rules_validated=coalesce((p_data->>'rules_validated')::boolean,false),updated_at=now() where id=true;
 when 'define_role' then
  insert into app_private.roles(id,name,permissions) values(app_private.required(p_data,'role_id',80),app_private.required(p_data,'name',160),array(select jsonb_array_elements_text(p_data->'permissions')))
  on conflict(id) do update set name=excluded.name,permissions=excluded.permissions;
 when 'assign_role' then
  if not exists(select 1 from app_private.roles r where r.id=p_data->>'role_id' and 'administration'=any(r.permissions))
     and not exists(select 1 from app_private.memberships m where m.user_id=(p_data->>'user_id')::uuid and m.status='Active') then
   raise exception 'Officer roles can only go to Active members. This account has no Active membership.' using errcode='42501';
  end if;
  insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason)
  values((p_data->>'user_id')::uuid,p_data->>'role_id',app_private.required(p_data,'position',160),coalesce((p_data->>'starts_at')::timestamptz,now()),(p_data->>'ends_at')::timestamptz,u,app_private.required(p_data,'reason')) returning id into v_id;
 when 'retire_role' then
  update app_private.role_assignments set retired_at=now(),reason=reason||E'\nRetired: '||app_private.required(p_data,'reason') where id=(p_data->>'id')::uuid returning id into v_id;
 when 'set_account_status' then
  v_id:=(p_data->>'user_id')::uuid; perform app_private.required(p_data,'reason');
  update app_private.profiles set enabled=(p_data->>'enabled')::boolean,updated_at=now() where id=v_id;
 else raise exception 'Unknown administrative action.';
 end case;
 if not exists(select 1 from app_private.role_assignments a join app_private.roles r on r.id=a.role_id join app_private.profiles p on p.id=a.user_id
 where p.enabled and 'administration'=any(r.permissions) and a.retired_at is null and now()>=a.starts_at and now()<a.ends_at) then raise exception 'Keep at least one active administrator.'; end if;
 perform app_private.note(14,v_id,p_action,p_data); return jsonb_build_object('id',v_id); end
$$;

commit;
