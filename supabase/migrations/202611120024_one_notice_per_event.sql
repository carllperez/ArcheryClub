-- One notice per event, in plain words.
-- Approving an application with a fee used to send three notices ("approved: ",
-- "Approved. Pay the membership fee...", "Fee assessment: Membership fee"). The fee notice is
-- the one worth keeping: it carries the due date and completes itself once the fee is paid.
-- Officer decisions also arrived as "<status>: <remark>", which read as "approved:" when the
-- remark was empty.
begin;

create or replace function app_private.peso_text(p numeric) returns text language sql immutable set search_path='' as $$
 select '₱'||case when p=trunc(p) then to_char(p,'FM999,999,990') else to_char(p,'FM999,999,990.00') end
$$;

create or replace function app_private.tidy_notice() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_status text; v_remark text; f app_private.fee_assessments;
begin
 if new.kind in ('screening','finance') and position(': ' in new.message)>0 then
  v_status:=split_part(new.message,': ',1); v_remark:=trim(substr(new.message,length(v_status)+3));
 end if;
 if new.kind='screening' then
  -- Approved with a fee: the fee notice says it all.
  if v_status='approved' and exists(select 1 from app_private.memberships where user_id=new.user_id and status='Pending payment') and v_remark='' then return null; end if;
  new.message:=case v_status
   when 'approved' then 'Your membership application was approved.'
   when 'returned_for_correction' then 'Changes requested on your application'
   when 'rejected' then 'Your membership application was not approved'
   when 'under_review' then 'Your application is being reviewed.'
   else new.message end
   ||case when v_status is not null and v_remark<>'' then ': '||v_remark else (case when v_status in ('returned_for_correction','rejected') then '.' else '' end) end;
 elsif new.kind='finance' and v_status is not null then
  new.message:=case v_status
   when 'verified' then 'Your payment was verified'
   when 'returned_for_correction' then 'Your payment was sent back'
   when 'incomplete' then 'Your payment is incomplete'
   when 'rejected' then 'Your payment was not accepted'
   else v_status end
   ||case when v_remark<>'' and v_status<>'verified' then ': '||v_remark else '.' end;
 elsif new.kind='membership' and new.message like 'Approved. Pay the membership fee%' then
  return null;
 elsif new.kind='fee' then
  select * into f from app_private.fee_assessments where id=new.record_id;
  -- the text must not depend on payment_id: paying re-sends the same notice to mark it complete
  if found and f.purpose='Membership fee' then
   new.message:='Approved! Pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.';
  elsif found then
   new.message:='Fee due: '||f.purpose||' · '||app_private.peso_text(f.amount)||' by '||to_char(f.due_on,'Dy, Mon FMDD');
  end if;
 end if;
 return new;
end $$;
revoke all on function app_private.tidy_notice(),app_private.peso_text(numeric) from public,anon,authenticated;

create trigger tidy_notice before insert on app_private.notifications for each row execute function app_private.tidy_notice();

-- Clean up notices already sent.
delete from app_private.notifications n where n.kind='membership' and n.message like 'Approved. Pay the membership fee%';
delete from app_private.notifications n where n.kind='screening' and n.message='approved: '
 and exists(select 1 from app_private.memberships m where m.user_id=n.user_id);
update app_private.notifications n set message='Approved! Pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.'
 from app_private.fee_assessments f where n.kind='fee' and n.record_id=f.id and f.purpose='Membership fee' and n.message='Fee assessment: Membership fee';

commit;
