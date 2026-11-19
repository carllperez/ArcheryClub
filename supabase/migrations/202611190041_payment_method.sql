-- Payments say how they were paid (GCash, Maya or bank), and the reference number is checked to match (19 Nov).
--  * GCash: the receipt's Ref No. is 13 digits.
--  * Maya and bank transfers: 6 to 30 letters, numbers or dashes (formats differ by bank).
-- The check is a trigger, so it also covers older app versions that save through club_command directly.
-- It only runs when the reference or method changes, so verifying or returning an older payment is never blocked.
begin;

alter table app_private.financial_records add column if not exists payment_method text not null default ''
 check (payment_method in ('','gcash','maya','bank'));

create or replace function app_private.check_payment_reference() returns trigger language plpgsql set search_path='' as $$
begin
 if new.kind<>'payment' then return new; end if;
 if tg_op='UPDATE' and new.reference is not distinct from old.reference and new.payment_method is not distinct from old.payment_method then return new; end if;
 new.reference:=upper(regexp_replace(coalesce(new.reference,''),'[[:space:]]','','g'));
 if new.reference='' then return new; end if;
 if new.payment_method='gcash' and new.reference !~ '^[0-9]{13}$' then
  raise exception 'A GCash reference number is the 13-digit Ref No. on your receipt.'; end if;
 if new.reference !~ '^[A-Z0-9-]{6,30}$' then
  raise exception 'Use the reference number exactly as on the receipt: 6 to 30 letters or numbers.'; end if;
 return new;
end $$;
drop trigger if exists check_payment_reference on app_private.financial_records;
create trigger check_payment_reference before insert or update on app_private.financial_records
 for each row execute function app_private.check_payment_reference();

-- Saves a payment with its method: the usual save, then the method on the same record (the trigger checks both).
create or replace function public.save_payment(p_data jsonb) returns jsonb language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_user(); v_method text:=coalesce(p_data->>'payment_method',''); result jsonb; v_id uuid;
begin
 if v_method not in ('gcash','maya','bank') then raise exception 'Choose how you paid: GCash, Maya or bank transfer.'; end if;
 if v_method='gcash' and upper(regexp_replace(coalesce(p_data->>'reference',''),'[[:space:]]','','g')) !~ '^[0-9]{13}$' then
  raise exception 'A GCash reference number is the 13-digit Ref No. on your receipt.'; end if;
 result:=public.club_command('save_financial_request',p_data||jsonb_build_object('kind','payment'));
 v_id:=coalesce(nullif(result->>'id','')::uuid,nullif(p_data->>'id','')::uuid);
 update app_private.financial_records set payment_method=v_method where id=v_id and user_id=u;
 return result;
end $$;
revoke all on function public.save_payment(jsonb) from public,anon;
grant execute on function public.save_payment(jsonb) to authenticated;

commit;
