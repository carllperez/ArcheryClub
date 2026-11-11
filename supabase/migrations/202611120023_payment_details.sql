-- Where to pay. The Treasurer records the club's GCash and bank details once; every
-- applicant's pay step shows them. Any GCash or bank transfer is accepted as long as the
-- receipt carries a reference number (migration 022).
begin;

alter table app_private.settings add column payment_details jsonb not null default '{}'::jsonb;

create function public.set_payment_details(p_details jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare v jsonb:='{}'::jsonb; k text;
begin
 perform app_private.require_user();
 if not (app_private.can('finance') or app_private.can('administration')) then raise exception 'Permission denied: finance' using errcode='42501'; end if;
 foreach k in array array['gcash_name','gcash_number','bank_name','bank_account_name','bank_account_number'] loop
  if length(trim(coalesce(p_details->>k,'')))>120 then raise exception 'Keep each payment detail under 120 characters.'; end if;
  v:=v||jsonb_build_object(k,trim(coalesce(p_details->>k,'')));
 end loop;
 if (v->>'gcash_number')='' and (v->>'bank_account_number')='' then raise exception 'Enter at least a GCash number or a bank account number.'; end if;
 update app_private.settings set payment_details=v,updated_at=now() where id;
 perform app_private.note(12,null,'set_payment_details',v);
 return v;
end $$;
revoke all on function public.set_payment_details(jsonb) from public,anon;
grant execute on function public.set_payment_details(jsonb) to authenticated;

commit;
