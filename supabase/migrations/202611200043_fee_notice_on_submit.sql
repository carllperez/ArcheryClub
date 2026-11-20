-- The "pay your fee" notice closes as soon as the payment is sent for checking (20 Nov testing).
-- Before, it stayed open until the Treasurer verified, so members who had already paid kept seeing "pay the fee".
--  * Sent for checking: the open fee notice for the same purpose is marked done.
--  * Sent back, incomplete or not accepted: the fee is still unpaid, so the notice comes back (alongside the
--    "Your payment was sent back" notice the payer already gets), unless another payment for it is still being checked.
--  * Verified: unchanged; linking the payment to the fee completes the notice as before.
-- A separate trigger, so sync_workflow_notices stays as it is.
begin;

create or replace function app_private.fee_notice_follows_payment() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if new.kind<>'payment' or new.status is not distinct from (case when tg_op='UPDATE' then old.status end) then return new; end if;
 if new.status='pending_verification' then
  update app_private.notifications n set completed_at=now()
   from app_private.fee_assessments f
   where n.kind='fee' and n.record_id=f.id and n.completed_at is null
     and f.user_id=new.user_id and f.purpose=new.purpose and f.payment_id is null;
 elsif new.status in ('returned_for_correction','incomplete','rejected') then
  update app_private.notifications n set completed_at=null, acknowledged_at=null, notified_at=now()
   from app_private.fee_assessments f
   where n.kind='fee' and n.record_id=f.id and n.completed_at is not null
     and f.user_id=new.user_id and f.purpose=new.purpose and f.payment_id is null
     and not exists(select 1 from app_private.financial_records r where r.user_id=new.user_id and r.kind='payment'
                    and r.purpose=new.purpose and r.id<>new.id and r.status in ('pending_verification','verified'));
 end if;
 return new;
end $$;
revoke all on function app_private.fee_notice_follows_payment() from public,anon,authenticated;
drop trigger if exists fee_notice_follows_payment on app_private.financial_records;
create trigger fee_notice_follows_payment after insert or update on app_private.financial_records
 for each row execute function app_private.fee_notice_follows_payment();

-- Payments already waiting to be checked: close their fee notices now.
update app_private.notifications n set completed_at=now()
 from app_private.fee_assessments f
 where n.kind='fee' and n.record_id=f.id and n.completed_at is null and f.payment_id is null
   and exists(select 1 from app_private.financial_records r where r.user_id=f.user_id and r.kind='payment'
              and r.purpose=f.purpose and r.status='pending_verification');

commit;
