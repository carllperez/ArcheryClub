-- Club funds: the President and the Treasurer see the same balance (22 Nov testing).
-- The President may read only expenses and reimbursements (policy financial_read, migration 005): members' payments,
-- with their references and receipts, stay with the Treasurer. The app added up the rows it could read, so the
-- President's Club funds showed P0 and an empty ledger while the Treasurer's showed P11,200.
-- Fix: the server sends both offices the verified totals (no names, references or receipts); nobody else gets them.
begin;

create or replace function app_private.fund_totals() returns jsonb language sql stable security definer set search_path='' as $$
 select case when app_private.can('finance') or app_private.can('president') then jsonb_build_object(
   'received',coalesce(sum(amount) filter (where kind='payment'),0),
   'spent',coalesce(sum(amount) filter (where kind in ('expense','reimbursement')),0),
   'payments',count(*) filter (where kind='payment'))
 end
 from app_private.financial_records where status='verified' $$;
revoke all on function app_private.fund_totals() from public,anon;
grant execute on function app_private.fund_totals() to authenticated;

-- Add the totals to the live workspace without restating it: replace its last entry, only if it occurs exactly once.
do $$
declare
 def text:=pg_get_functiondef('public.club_workspace()'::regprocedure);
 snippet text:=$s$'partner_invites',(select coalesce(jsonb_agg(v order by v.created_at desc),'[]') from app_private.partner_invites v));$s$;
begin
 if (length(def)-length(replace(def,snippet,'')))/length(snippet)<>1 then
  raise exception 'club_workspace() is not the version this migration expects; update the snippet.';
 end if;
 execute replace(def,snippet,$s$'partner_invites',(select coalesce(jsonb_agg(v order by v.created_at desc),'[]') from app_private.partner_invites v),
  'fund_totals',app_private.fund_totals());$s$);
end $$;

commit;
