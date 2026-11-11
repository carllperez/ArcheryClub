-- A payment must carry the reference number printed on its receipt (GCash "Ref No.", bank
-- transaction number, OR number). The treasurer checks it against the club's account, and
-- one receipt can only pay once. Expenses and reimbursements keep the reference optional.
begin;

create or replace function app_private.payment_reference_check() returns trigger
language plpgsql security definer set search_path='' as $$
declare v_ref text;
begin
 if new.kind<>'payment' then return new; end if;
 v_ref:=upper(regexp_replace(coalesce(new.reference,''),'[\s]','','g'));
 if v_ref !~ '^[A-Z0-9-]{6,30}$' then
  raise exception 'Enter the reference number from your receipt (6 to 30 letters or numbers, e.g. the GCash Ref No.).';
 end if;
 if exists(select 1 from app_private.financial_records r where r.kind='payment' and r.id<>new.id and r.status<>'rejected'
   and upper(regexp_replace(r.reference,'[\s]','','g'))=v_ref) then
  raise exception 'This reference number is already on another payment. Check the number on your receipt.';
 end if;
 new.reference:=v_ref;
 return new;
end $$;
revoke all on function app_private.payment_reference_check() from public,anon,authenticated;

create trigger payment_reference_check before insert or update of reference, kind on app_private.financial_records
 for each row execute function app_private.payment_reference_check();

commit;
