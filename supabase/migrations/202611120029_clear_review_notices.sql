-- Officer review notices say whose submission they are about. Before, the Treasurer saw identical
-- "Financial submission requires review" cards. (Handled ones already get completed_at from
-- sync_workflow_notices; the app now hides completed notices.)
begin;

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
 elsif new.kind='review' and new.module=12 and new.message='Financial submission requires review' then
  -- Name who sent what, so the Treasurer can tell submissions apart. Kept to fields that don't change on a
  -- resubmission, so a corrected submission reopens the same notice instead of adding another.
  select 'Check '||coalesce(nullif(p.full_name,''),'a member')||'''s '||case r.kind when 'payment' then 'payment' when 'reimbursement' then 'reimbursement request' else 'expense' end||' · '||r.purpose
    into new.message
    from app_private.financial_records r left join app_private.profiles p on p.id=r.user_id where r.id=new.record_id;
  new.message:=coalesce(new.message,'A financial submission needs checking');
 elsif new.kind='review' and new.module=10 and new.message='Borrowing request requires review' then
  select coalesce(nullif(p.full_name,''),'A member')||' asked to borrow '||e.label
    into new.message
    from app_private.borrowings b join app_private.equipment e on e.id=b.equipment_id left join app_private.profiles p on p.id=b.user_id where b.id=new.record_id;
  new.message:=coalesce(new.message,'A borrowing request needs review');
 elsif new.kind='fee' then
  select * into f from app_private.fee_assessments where id=new.record_id;
  -- the text must not depend on payment_id: paying re-sends the same notice to mark it complete
  if found and f.purpose='Membership fee' then
   new.message:='Your application was approved. Next, pay the '||app_private.peso_text(f.amount)||' membership fee by '||to_char(f.due_on,'Dy, Mon FMDD')||' to activate your membership.';
  elsif found then
   new.message:='Fee due: '||f.purpose||' · '||app_private.peso_text(f.amount)||' by '||to_char(f.due_on,'Dy, Mon FMDD');
  end if;
 end if;
 return new;
end $$;

update app_private.notifications n set message='Check '||coalesce(nullif(p.full_name,''),'a member')||'''s '||case r.kind when 'payment' then 'payment' when 'reimbursement' then 'reimbursement request' else 'expense' end||' · '||r.purpose
 from app_private.financial_records r left join app_private.profiles p on p.id=r.user_id
 where n.kind='review' and n.module=12 and n.record_id=r.id and n.message='Financial submission requires review'
   and not exists(select 1 from app_private.notifications d where d.user_id=n.user_id and d.record_id=n.record_id and d.kind='review' and d.id<>n.id);
update app_private.notifications n set message=coalesce(nullif(p.full_name,''),'A member')||' asked to borrow '||e.label
 from app_private.borrowings b join app_private.equipment e on e.id=b.equipment_id left join app_private.profiles p on p.id=b.user_id
 where n.kind='review' and n.module=10 and n.record_id=b.id and n.message='Borrowing request requires review'
   and not exists(select 1 from app_private.notifications d where d.user_id=n.user_id and d.record_id=n.record_id and d.kind='review' and d.id<>n.id);

commit;
