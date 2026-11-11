import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PGlite} from '@electric-sql/pglite';
import {readFile,readdir} from 'node:fs/promises';
const db=new PGlite();
await db.exec(`create role anon; create role authenticated; create role service_role; create schema auth; create schema storage;
create table auth.users(id uuid primary key,email text,email_confirmed_at timestamptz);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to authenticated; grant execute on function auth.uid() to authenticated;
create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text,owner_id text,metadata jsonb);
alter table storage.objects enable row level security; grant usage on schema storage to authenticated; grant select,insert,update,delete on storage.objects to authenticated;`);
for(const f of (await readdir(new URL('../supabase/migrations/',import.meta.url))).filter(x=>x.endsWith('.sql')).sort()){
 await db.exec(await readFile(new URL('../supabase/migrations/'+f,import.meta.url),'utf8'));
}
const ids={admin:'00000000-0000-4000-8000-000000000001',applicant:'00000000-0000-4000-8000-000000000002',other:'00000000-0000-4000-8000-000000000003',officer:'00000000-0000-4000-8000-000000000004',successor:'00000000-0000-4000-8000-000000000005'};
for(const [name,id] of Object.entries(ids)) await db.query('insert into auth.users values($1,$2,now());',[id,name+'@example.test']);
async function as(name,fn){await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[ids[name]??'']);await db.exec('set role '+(name?'authenticated':'anon'));try{return await fn();}finally{await db.exec('reset role');}}
async function command(name,action,data={}){return as(name,async()=> (await db.query('select public.club_command($1,$2::jsonb) as result',[action,JSON.stringify(data)])).rows[0].result);}
for(const name of Object.keys(ids)) await as(name,()=>db.exec('select public.initialize_account()'));
await db.query("insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason) values($1,'administrator','Administrator',now()+interval '1 year','Test bootstrap'),($2,'membership_officer','Membership officer',now()+interval '1 year','Fixture')",[ids.admin,ids.officer]);
const denied=async(fn,pattern=/permission|denied|locked|changed|required|invalid|cannot|verify|not found|keep|verified/i)=>assert.rejects(fn,pattern);
test('Version conflicts return a non-retryable HTTP conflict instead of serialization failure',async()=>{
 for(const version of [undefined,1,3])await assert.rejects(
  ()=>db.query('select app_private.check_version($1,$2::jsonb)',[2,JSON.stringify({version})]),
  error=>error.code==='PT409'&&error.message.includes('Refresh')
 );
 await db.query('select app_private.check_version($1,$2::jsonb)',[2,JSON.stringify({version:2})]);
});
test('M14 anonymous access and metadata cannot grant authority',async()=>{
 await denied(()=>command(null,'save_profile',{full_name:'Intruder'}));
 await denied(()=>command('applicant','assign_role',{user_id:ids.applicant,role_id:'administrator',position:'Admin',ends_at:'2030-01-01',reason:'spoof'}));
 await as('applicant',()=>denied(()=>db.exec("update app_private.profiles set enabled=true")));
});
test('M14 settings update targets the singleton and preserves configurable requirements',async()=>{
 const original=(await db.query('select * from app_private.settings')).rows[0];
 try{
  await denied(()=>command('applicant','configure_club',original));
  await command('admin','configure_club',{...original,application_requirements:['QA test form'],renewal_requirements:['QA renewal proof']});
  const rows=(await db.query('select * from app_private.settings')).rows;
  assert.equal(rows.length,1);assert.deepEqual(rows[0].application_requirements,['QA test form']);
  const source=(await db.query("select pg_get_functiondef('app_private.admin_command(text,jsonb)'::regprocedure) as source")).rows[0].source;
  assert.match(source,/update app_private.settings[\s\S]*?where id\s*=\s*true\s*;/i,'Hosted safe-update enforcement requires a WHERE predicate');
 }finally{await command('admin','configure_club',original);}
});
test('M2→M7→M1 ownership, validation, correction, approval, persistent identity',async()=>{
 await denied(()=>command('applicant','submit_application',{full_name:'',confirmed:false}),/valid|confirm/);
 let a=await command('applicant','save_application',{full_name:'Alex Archer',phone:'09123456789',student_number:'TEST-1'});
 assert.equal(a.status,'draft');
 await as('other',async()=>assert.equal((await db.query('select * from app_private.applications')).rows.length,0));
 a=await command('applicant','submit_application',{version:a.version,full_name:'Alex Archer',confirmed:true});
 await denied(()=>command('applicant','save_application',{full_name:'Rewrite'}));
 await denied(()=>command('applicant','review_application',{id:a.id,version:a.version,status:'approved'}));
 await command('officer','review_application',{id:a.id,version:a.version,status:'returned_for_correction',remark:'Please add student number'});
 a=(await db.query('select * from app_private.applications where id=$1',[a.id])).rows[0];
 a=await command('applicant','submit_application',{version:a.version,full_name:'Alex Archer',student_number:'TEST-1',confirmed:true});
 await denied(()=>command('officer','review_application',{id:a.id,version:a.version-1,status:'approved',category:'Regular member',requirements_verified:true}));
 await command('officer','review_application',{id:a.id,version:a.version,status:'approved',category:'Regular member',requirements_verified:true});
 assert.equal((await db.query('select user_id from app_private.memberships')).rows[0].user_id,ids.applicant);
 await denied(()=>command('officer','review_application',{id:a.id,version:a.version,status:'approved'}));
});
test('M1 renewal drafts submit once, lock for review, and require officer approval',async()=>{
 let r=await command('applicant','save_renewal',{information:'Next term renewal'});
 r=await command('applicant','request_renewal',{version:r.version,information:'Updated information'});
 await denied(()=>command('applicant','request_renewal',{information:'Duplicate'}));
 assert.equal((await db.query('select count(*)::int as n from app_private.renewals')).rows[0].n,1);
 let stored=(await db.query('select * from app_private.renewals where id=$1',[r.id])).rows[0];
 assert.equal(stored.status,'pending');
 await command('officer','review_renewal',{id:r.id,version:stored.version,status:'approved',remark:'Verified'});
});
test('M14 turnover revokes authority and keeps historical records',async()=>{
 const old=(await db.query("select id from app_private.role_assignments where user_id=$1",[ids.officer])).rows[0].id;
 await command('admin','assign_role',{user_id:ids.successor,role_id:'membership_officer',position:'Membership officer',ends_at:'2035-01-01',reason:'New term'});
 await command('admin','retire_role',{id:old,reason:'Term ended'});
 await as('officer',async()=>assert.equal((await db.query('select * from app_private.applications')).rows.length,0));
 await as('successor',async()=>assert.equal((await db.query('select * from app_private.applications')).rows.length,1));
 const admin=(await db.query("select id from app_private.role_assignments where user_id=$1",[ids.admin])).rows[0].id;
 await denied(()=>command('admin','retire_role',{id:admin,reason:'Last admin'}));
 await command('admin','set_account_status',{user_id:ids.other,enabled:false,reason:'Test disabled'});
 await denied(()=>command('other','save_profile',{full_name:'Disabled'}));
});

const future=hours=>new Date(Date.now()+hours*3600000).toISOString();
async function grant(person,role){
 // a person holds a role once (migration 025), so fixtures only add roles they lack
 if((await db.query('select 1 from app_private.role_assignments where user_id=$1 and role_id=$2 and retired_at is null and ends_at>now()',[ids[person],role])).rows.length)return;
 await command('admin','assign_role',{user_id:ids[person],role_id:role,position:role,ends_at:'2035-01-01',reason:'Integration fixture'});}
async function row(table,id){return (await db.query(`select * from app_private.${table} where id=$1`,[id])).rows[0];}
async function rpc(person,name,data,signature){return as(person,async()=> (await db.query(`select public.${name}(${signature}) as result`,data)).rows[0].result);}
async function upload(person,module,record,requirement='Proof',mime='application/pdf'){
 const path=`${ids[person]}/${module}/${record}/${crypto.randomUUID()}.pdf`;
 await as(person,()=>db.query('insert into storage.objects(bucket_id,name,owner_id,metadata) values($1,$2,$3,$4)', ['club-documents',path,ids[person],{mimetype:mime,size:100}]));
 await rpc(person,'register_document',[{module,record_id:record,requirement,name:'Proof',path}],'$1::jsonb');return path;
}
let eventId;
test('M8 announcement edits notify members, reject stale writes and protect unpublished events',async()=>{
 await grant('officer','activity_officer');
 const a=await command('officer','publish_announcement',{title:'Training notice',message:'Original venue'});
 await denied(()=>command('applicant','update_announcement',{id:a.id,version:1,title:'Spoof',message:'Spoof'}));
 await db.query("update app_private.notifications set acknowledged_at=now() where record_id=$1",[a.id]);
 await command('officer','update_announcement',{id:a.id,version:1,title:'Training notice',message:'Corrected venue'});
 await denied(()=>command('officer','update_announcement',{id:a.id,version:1,title:'Stale',message:'Overwrite'}));
 assert.equal((await row('announcements',a.id)).version,2);
 await as('applicant',async()=>{
  assert.equal((await db.query('select message from app_private.announcements where id=$1',[a.id])).rows[0].message,'Corrected venue');
  const n=(await db.query('select * from app_private.notifications where record_id=$1',[a.id])).rows;
  assert.equal(n.length,1);assert.equal(n[0].acknowledged_at,null);
 });
 const e=await command('officer','save_event',{title:'Draft event',description:'Private draft',venue:'Range',starts_at:future(24),ends_at:future(25),registration_opens:future(-2),registration_closes:future(12),capacity:2});
 await denied(()=>command('officer','publish_announcement',{event_id:e.id,title:'Leaked draft',message:'Private'}),/published/);
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','update_announcement',{id:a.id,version:2,title:'Training notice',message:'Published venue',event_id:e.id});
 await denied(()=>command('officer','update_announcement',{id:a.id,version:3,title:'',message:'Invalid'}),/valid/);
 assert.equal((await row('announcements',a.id)).version,3);
});
test('M3/M8 published registration, capacity, QR, manual correction and ownership',async()=>{
 await grant('officer','activity_officer');
 const event={title:'Practice',description:'Club practice',venue:'Range',starts_at:future(24),ends_at:future(26),registration_opens:future(-24),registration_closes:future(12),capacity:1};
 const e=await command('officer','save_event',event);eventId=e.id;
 await denied(()=>command('applicant','register_event',{event_id:e.id}),/not open/);
 await command('officer','publish_event',{id:e.id,version:1});
 const first=await command('applicant','register_event',{event_id:e.id});
 assert.equal((await command('applicant','register_event',{event_id:e.id})).id,first.id);
 await db.query("insert into app_private.memberships(user_id,category,status,approved_by) values($1,'Regular member','Active',$2)",[ids.successor,ids.officer]);
 assert.equal((await command('successor','register_event',{event_id:e.id})).status,'waitlisted');
 const w=await command('officer','open_attendance',{event_id:e.id,opens_at:future(-1),closes_at:future(1)});
 const token=(await row('attendance_windows',w.id)).token;
 await denied(()=>command('successor','check_in',{token}),/Register/);
 await command('applicant','check_in',{token});await command('applicant','check_in',{token});
 const attendance=(await db.query('select * from app_private.attendance where event_id=$1',[e.id])).rows[0];
 assert.equal((await db.query('select count(*)::int n from app_private.attendance')).rows[0].n,1);
 const c=await command('applicant','request_attendance_correction',{record_id:attendance.id,request:'Marked present by mistake'});
 await command('officer','review_attendance_correction',{id:c.id,status:'approved',attendance_status:'excused',decision:'Verified excusal'});
 assert.equal((await row('attendance',attendance.id)).status,'excused');
 await as('successor',async()=>assert.equal((await db.query('select * from app_private.attendance')).rows.length,0));
});
test('M5/M10 incompatible and overlapping equipment requests cannot be approved',async()=>{
 await grant('officer','equipment_officer');
 const eq=await command('officer','save_equipment',{label:'Recurve 01',category:'Bow',specifications:'Training',handedness:'right',draw_weight:24,bow_setup:'recurve',condition:'serviceable'});
 const req={equipment_id:eq.id,starts_at:future(48),ends_at:future(50),handedness:'right',maximum_draw_weight:28,bow_setup:'recurve',purpose:'Practice'};
 const a=await command('applicant','request_borrowing',req),b=await command('successor','request_borrowing',req);
 const review={version:1,status:'approved',compatibility_confirmed:true,remark:'Fit and safety checked'};
 await denied(()=>command('applicant','review_borrowing',{id:a.id,...review}));
 await command('officer','review_borrowing',{id:a.id,...review});
 await denied(()=>command('officer','review_borrowing',{id:b.id,...review}),/conflict/);
 await command('officer','review_borrowing',{id:a.id,version:2,status:'released',compatibility_confirmed:true,remark:'Released'});
 await command('officer','review_borrowing',{id:a.id,version:3,status:'returned',condition:'damaged',remark:'Crack found'});
 await denied(()=>command('officer','review_borrowing',{id:b.id,...review}),/serviceable/);
 const available=await rpc('applicant','equipment_availability',[req.starts_at,req.ends_at,'right',28,'recurve'],'$1,$2,$3,$4,$5');
 assert.equal(available[0].compatible,false);assert.equal((await row('borrowings',a.id)).status,'returned');
});
test('M4/M11 personal scores require validation; CSV is atomic and videos require trusted verification',async()=>{
 await grant('officer','training_officer');
 const score={training_date:'2026-10-02',distance:18,target_face:'40cm',category:'Recurve',scoring_format:'30 arrows',score:240,maximum_score:300};
 const t=await command('applicant','save_personal_training',score);
 assert.equal((await row('training_records',t.id)).validation,'personal');
 await denied(()=>upload('applicant',11,t.id,'Video','video/mp4'),/duration/);
 await command('applicant','submit_training',{id:t.id});
 await denied(()=>command('applicant','save_personal_training',{id:t.id,version:2,...score}));
 await command('officer','validate_training',{id:t.id,version:2,validation:'validated',feedback:'Good grouping'});
 await denied(()=>command('applicant','validate_training',{id:t.id,version:3,validation:'validated',feedback:'Spoof'}));
 await denied(()=>command('officer','import_training',{rows:[{...score,user_id:ids.applicant,import_key:'atomic-1'},{...score,score:301,user_id:ids.applicant,import_key:'atomic-2'}]}),/constraint/);
 assert.equal((await db.query("select count(*)::int n from app_private.training_records where import_key like 'atomic-%'")).rows[0].n,0);
 await command('officer','import_training',{rows:[{...score,user_id:ids.applicant,import_key:'valid-1'}]});
 await denied(()=>command('officer','import_training',{rows:[{...score,user_id:ids.applicant,import_key:'valid-1'}]}),/unique/);
});
test('M12 receipts, separate prior approvals, ownership and verified ledger',async()=>{
 await grant('officer','treasurer');await grant('successor','president');await grant('officer','reports_officer');
 const f=await command('applicant','save_financial_request',{kind:'reimbursement',purpose:'Targets',amount:'120.25'});
 await denied(()=>command('applicant','submit_financial_request',{id:f.id,version:1}),/proof|receipt/);
 const path=await upload('applicant',12,f.id);
 await as('successor',async()=>assert.equal((await db.query('select * from storage.objects where name=$1',[path])).rows.length,1));
 await command('applicant','submit_financial_request',{id:f.id,version:1});
 await denied(()=>command('officer','verify_financial_request',{id:f.id,version:2,status:'verified',remark:'Check'}),/approvals/);
 await command('successor','approve_funds',{id:f.id,office:'president',remark:'Approved'});
 await command('officer','approve_funds',{id:f.id,office:'finance',remark:'Budget approved'});
 await command('officer','verify_financial_request',{id:f.id,version:2,status:'verified',remark:'Receipt reconciled'});
 const report=await rpc('officer','club_report',['2000-01-01','2099-12-31'],'$1,$2');
 assert.equal(Number(report.finance.verified_expenses),120.25);assert.equal(report.membership,undefined);
 await denied(()=>rpc('applicant','club_report',['2000-01-01','2099-12-31'],'$1,$2'));
});
test('M1/M2 document ownership, required renewals and version omission cannot bypass locks',async()=>{
 await db.exec("update app_private.settings set renewal_requirements=array['Renewal proof']");
 let r=await command('applicant','save_renewal',{information:'Renewal with proof'});
 await denied(()=>command('applicant','request_renewal',{information:'Missing',version:r.version}),/Missing requirement/);
 await denied(()=>upload('successor',1,r.id),/row-level security|uploads/);
 const path=await upload('applicant',1,r.id,'Renewal proof');
 await as('officer',async()=>assert.equal((await db.query('select * from storage.objects where name=$1',[path])).rows.length,0));
 await command('applicant','request_renewal',{information:'Complete',version:r.version});
 await denied(()=>upload('applicant',1,r.id),/row-level security|uploads/);
 await db.exec("update app_private.settings set renewal_requirements='{}'");
});
test('M15 event grants isolate internal records and own delegation edits',async()=>{
 await grant('officer','interclub_officer');
 await command('admin','set_account_status',{user_id:ids.other,enabled:true,reason:'Partner fixture'});
 const p=await command('officer','save_partner',{name:'Partner One',official_channel:'partner@example.test'});
 await command('officer','share_interclub_event',{event_id:eventId,shared_details:'Shared practice',requirements:'Club approval',registration_closes:future(10),rules:'Validated event rules'});
 await command('officer','invite_partner',{event_id:eventId,email:'other@example.test',partner_id:p.id,role:'partner'});
 const d=await command('other','save_delegate',{event_id:eventId,partner_id:p.id,full_name:'Partner Archer',affiliation:'Partner One',category:'Recurve',requirements:'Confirmed'});
 const documentPath=await upload('other',15,d.id);
 await denied(()=>command('other','review_delegate',{event_id:eventId,id:d.id,version:1,status:'eligible',remark:'Self review'}));
 await command('officer','review_delegate',{event_id:eventId,id:d.id,version:1,status:'eligible',remark:'Checked'});
 const ws=await rpc('other','club_workspace',[],'');
 assert.equal(ws.applications.length,0);assert.equal(ws.financial_records.length,0);assert.equal(ws.training_records.length,0);
 assert.equal(ws.shared_participants.length,1);assert.equal(ws.shared_participants[0].requirements,undefined);
 const access=ws.event_access[0];await command('officer','revoke_event_access',{event_id:eventId,id:access.id});
 assert.equal((await rpc('other','club_workspace',[],'')).interclub_events.length,0);
 await as('other',async()=>assert.equal((await db.query('select * from storage.objects where name=$1',[documentPath])).rows.length,0));
 await as('other',()=>db.query('delete from storage.objects where name=$1',[documentPath]));
 assert.equal((await db.query('select count(*)::int n from storage.objects where name=$1',[documentPath])).rows[0].n,1);
});
test('M9 ballots are anonymous, duplicate/ineligible votes denied, external tallies never leak',async()=>{
 await grant('officer','election_officer');
 const e=await command('officer','create_election',{title:'Test election',kind:'special',term_end:'2030-01-01',announces_at:future(-8),candidacy_closes:future(-6),voting_opens:future(-4),voting_closes:future(2),result_due:future(3),threshold:60,residency_terms:2,rules:'Test rules',rules_validated:true,method:'digital',positions:['President']});
 // Trusted fixtures position the time-sensitive election at the voting window.
 await db.query("update app_private.elections set status='voting' where id=$1",[e.id]);
 const candidate=(await db.query("insert into app_private.candidates(election_id,user_id,position,statement,confirmed,nominated_by) values($1,$2,'President','Platform',true,$3) returning id",[e.id,ids.applicant,ids.officer])).rows[0].id;
 await db.query("insert into app_private.eligible_voters values($1,$2,$3,'Verified residency')",[e.id,ids.applicant,ids.officer]);
 const ballot={election_id:e.id,choices:{President:candidate}};
 await denied(()=>command('successor','cast_ballot',ballot),/eligible voter/);
 await command('applicant','cast_ballot',ballot);
 await denied(()=>command('applicant','cast_ballot',ballot),/unique/);
 await as('officer',()=>denied(()=>db.exec('select * from app_private.ballots')));
 assert.equal((await db.query("select count(*)::int n from app_private.audit_log where action='cast_ballot'")).rows[0].n,0);
 await denied(()=>rpc('applicant','election_summary',[e.id],'$1'),/restricted/);
 await db.query("update app_private.elections set external_results='[{\"secret_tally\":10}]' where id=$1",[e.id]);
 const ws=await rpc('applicant','club_workspace',[],'');assert.equal(ws.elections[0].external_results,undefined);
 await as('applicant',()=>denied(()=>db.exec('select external_results from app_private.elections')));
 await db.query("insert into app_private.eligible_voters values($1,$2,$3,'Verified residency')",[e.id,ids.successor,ids.officer]);
 await db.query("update app_private.elections set voting_closes=now()-interval '1 hour' where id=$1",[e.id]);
 await command('officer','set_election_status',{id:e.id,version:1,status:'closed'});
 await denied(()=>command('officer','confirm_results',{id:e.id,version:2,confirmation:'Self-confirmed'}),/Another COMELEC/);
 await grant('successor','election_officer');
 await command('successor','confirm_results',{id:e.id,version:2,confirmation:'Turnout checked'});
 assert.equal((await row('elections',e.id)).status,'requires_revote');
 assert.equal((await rpc('applicant','election_summary',[e.id],'$1')).rate,50);
});
test('M13 goals preserve missing denominators and exclude unpermitted domains',async()=>{
 await command('officer','save_goal',{metric:'collection_completion',title:'Collection goal',target:90,starts_on:'2000-01-01',ends_on:'2099-12-31'});
 const report=await rpc('officer','club_report',['2000-01-01','2099-12-31'],'$1,$2');
 assert.equal(report.goals[0].actual,null);assert.equal(report.goals[0].achievement_percent,null);
 assert.equal(report.membership,undefined);assert.equal(report.elections.length,1);
});
test('M14 administrator account creation is audited and cannot grant roles through signup',async()=>{
 await denied(()=>rpc('applicant','request_account_creation',['new@example.test','New User','Fixture'],'$1,$2,$3'));
 await denied(()=>rpc('admin','request_account_creation',['bad email','New User','Fixture'],'$1,$2,$3'),/valid email/);
 const request=await rpc('admin','request_account_creation',['new@example.test','New User','Fixture'],'$1,$2,$3');
 const user='00000000-0000-4000-8000-000000000006';
 await db.query('insert into auth.users values($1,$2,null)',[user,'new@example.test']);
 await denied(()=>rpc('applicant','complete_account_creation',[request,user],'$1,$2'));
 await db.exec('set role service_role');
 try{await db.query('select public.complete_account_creation($1,$2)',[request,user]);}finally{await db.exec('reset role');}
 assert.equal((await db.query('select count(*)::int n from app_private.role_assignments where user_id=$1',[user])).rows[0].n,0);
 assert.equal((await db.query('select count(*)::int n from app_private.memberships where user_id=$1',[user])).rows[0].n,0);
 await as(null,()=>denied(()=>db.exec('select public.initialize_account()')));
});

test('M9 draft validation, nomination, residency, external results and archive use protected commands',async()=>{
 const config={title:'External election lifecycle',kind:'special',term_end:'2030-01-01',announces_at:future(-1),candidacy_closes:future(1),voting_opens:future(2),voting_closes:future(3),result_due:future(4),threshold:60,residency_terms:2,rules:'Awaiting club validation',rules_validated:false,method:'paper',positions:['President']};
 const e=await command('officer','create_election',config);
 await denied(()=>command('officer','set_election_status',{id:e.id,version:1,status:'announced'}),/Validate/);
 await denied(()=>command('applicant','update_election',{...config,id:e.id,version:1,rules_validated:true}));
 await command('officer','update_election',{...config,id:e.id,version:1,rules:'Approved fixture rules',rules_validated:true});
 await denied(()=>command('officer','update_election',{...config,id:e.id,version:1}),/changed/);
 await command('officer','set_election_status',{id:e.id,version:2,status:'announced'});
 await denied(()=>command('officer','update_election',{...config,id:e.id,version:3}),/draft/);
 const c=await command('applicant','nominate_candidate',{election_id:e.id,user_id:ids.successor,position:'President',statement:'Candidate platform'});
 await command('officer','confirm_candidate',{id:c.id,confirmed:true,remark:'Eligibility checked'});
 await denied(()=>command('officer','confirm_voter',{election_id:e.id,user_id:ids.applicant,reason:'Not resident yet'}),/residency/);
 for(const [sequence,start,end] of [[1,'2020-01-01','2020-04-30'],[2,'2020-05-01','2020-08-31']]) {
  const t=await command('officer','save_term',{name:'Fixture term '+sequence,sequence,starts_on:start,ends_on:end});
  await command('officer','record_residency',{user_id:ids.applicant,term_id:t.id,completed:true});
 }
 await command('officer','confirm_voter',{election_id:e.id,user_id:ids.applicant,reason:'Two consecutive completed terms reviewed'});
 // Advance only the fixture clock window; transitions themselves use the real API.
 await db.query("update app_private.elections set candidacy_closes=now()-interval '2 minutes',voting_opens=now()-interval '1 minute' where id=$1",[e.id]);
 await denied(()=>command('applicant','file_candidacy',{election_id:e.id,position:'President',statement:'Too late'}),/not open/);
 await command('officer','set_election_status',{id:e.id,version:3,status:'voting'});
 await db.query("update app_private.elections set voting_closes=now()-interval '1 second' where id=$1",[e.id]);
 await command('officer','set_election_status',{id:e.id,version:4,status:'closed'});
 await denied(()=>command('officer','record_external_results',{id:e.id,version:5,turnout:2,results:[{id:c.id,votes:2}],verification:'Excess turnout'}),/Turnout/);
 await command('officer','record_external_results',{id:e.id,version:5,turnout:1,results:[{id:c.id,votes:1}],verification:'Paper count independently checked'});
 await denied(()=>rpc('applicant','election_summary',[e.id],'$1'),/restricted/);
 await denied(()=>command('officer','confirm_results',{id:e.id,version:6,confirmation:'Self-confirmed'}),/Another COMELEC/);
 await command('successor','confirm_results',{id:e.id,version:6,confirmation:'Verified official result'});
 assert.equal((await rpc('applicant','election_summary',[e.id],'$1')).rate,100);
 const rolesBefore=(await db.query('select count(*)::int n from app_private.role_assignments')).rows[0].n;
 await command('officer','record_leadership',{election_id:e.id,user_id:ids.successor,position:'President',kind:'transition',starts_on:'2030-01-01',ends_on:'2030-12-31',documentation:'Election result confirmed; access assignment remains separate'});
 assert.equal((await db.query('select count(*)::int n from app_private.role_assignments')).rows[0].n,rolesBefore);
 await command('officer','set_election_status',{id:e.id,version:7,status:'archived'});
 assert.equal((await rpc('applicant','election_summary',[e.id],'$1')).status,'archived');
});

test('M15 two partner boundaries, scheduling, substitution, verified results and immutable archive',async()=>{
 const activity={title:'Interclub lifecycle fixture',description:'Synthetic fixture',venue:'Test range',starts_at:future(24),ends_at:future(28),registration_opens:future(-1),registration_closes:future(12),capacity:20};
 const e=await command('officer','save_event',activity);
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','share_interclub_event',{event_id:e.id,shared_details:'Shared fixture',requirements:'Reviewed club approval',registration_closes:future(12),rules:'Fixture rules'});
 const p1=await command('officer','save_partner',{name:'Boundary Partner A',official_channel:'a@example.test'});
 const p2=await command('officer','save_partner',{name:'Boundary Partner B',official_channel:'b@example.test'});
 await command('officer','invite_partner',{event_id:e.id,email:'other@example.test',partner_id:p1.id,role:'partner'});
 await command('officer','invite_partner',{event_id:e.id,email:'successor@example.test',partner_id:p2.id,role:'partner'});
 const delegate={event_id:e.id,partner_id:p1.id,full_name:'Archer A',affiliation:'Partner A',category:'Recurve',requirements:'Provided'};
 const a=await command('other','save_delegate',delegate);
 const b=await command('successor','save_delegate',{...delegate,partner_id:p2.id,full_name:'Archer B',affiliation:'Partner B'});
 await denied(()=>command('other','save_delegate',{...delegate,id:b.id,partner_id:p2.id,version:1}),/Permission/);
 await command('officer','review_delegate',{event_id:e.id,id:a.id,version:1,status:'eligible',remark:'Reviewed'});
 await command('officer','review_delegate',{event_id:e.id,id:b.id,version:1,status:'eligible',remark:'Reviewed'});
 const assignment={event_id:e.id,delegate_id:a.id,starts_at:future(25),ends_at:future(26),target_group:'Target 1',responsibility:'Round 1'};
 await denied(()=>command('officer','assign_delegate',{...assignment,starts_at:future(23)}),/shared event period/);
 await command('officer','assign_delegate',assignment);
 await denied(()=>command('officer','assign_delegate',{...assignment,delegate_id:b.id}),/conflict/);
 const replacement=await command('officer','substitute_delegate',{event_id:e.id,id:a.id,version:2,full_name:'Substitute A',requirements:'Replacement checked',remark:'Approved substitution'});
 assert.equal((await db.query('select delegate_id from app_private.interclub_assignments where event_id=$1',[e.id])).rows[0].delegate_id,replacement.id);
 const concern=await command('other','raise_concern',{event_id:e.id,concern:'Confirm substitute schedule'});
 await command('officer','resolve_concern',{event_id:e.id,id:concern.id,resolution:'Schedule transferred to approved substitute'});
 const update={event_id:e.id,shared_details:'Shared fixture',shared_venue:activity.venue,shared_start:activity.starts_at,shared_end:activity.ends_at};
 await denied(()=>command('officer','update_shared_event',{...update,version:1,status:'completed'}),/transition/);
 await command('officer','update_shared_event',{...update,version:1,status:'active'});
 await command('officer','review_delegate',{event_id:e.id,id:replacement.id,version:1,status:'checked_in',remark:'Authorized check-in'});
 const result=await command('officer','record_result',{event_id:e.id,delegate_id:replacement.id,result:'Verified scoring file reference: fixture result'});
 assert.equal((await rpc('other','club_workspace',[],'')).interclub_results.filter(r=>r.event_id===e.id).length,0);
 await denied(()=>command('officer','verify_result',{event_id:e.id,id:result.id}),/Another officer/);
 await grant('successor','interclub_officer');
 await command('successor','verify_result',{event_id:e.id,id:result.id});
 assert.equal((await rpc('other','club_workspace',[],'')).interclub_results.filter(r=>r.event_id===e.id).length,1);
 await command('officer','update_shared_event',{...update,version:2,status:'completed'});
 await denied(()=>command('officer','update_shared_event',{...update,version:3,status:'preparation'}),/transition/);
 await command('officer','archive_interclub',{event_id:e.id,summary:'Fixture complete',procedures:'Fixture procedures',agreements:'Fixture agreement',approval_references:'Fixture reference only',minutes:'Fixture minutes',issues:'Resolved schedule question',evaluation:'Test passed',recommendations:'Retain history'});
 await denied(()=>command('officer','record_result',{event_id:e.id,delegate_id:replacement.id,result:'Rewrite archive'}),/Editable/);
 const archives=(await rpc('officer','club_workspace',[],'')).interclub_archives;
 assert.equal(archives.find(r=>r.event_id===e.id).snapshot.results[0].id,result.id);
 assert.equal((await rpc('other','club_workspace',[],'')).interclub_archives.length,0);
});

test('M6/M12 review notices track resubmissions and fee completion independently of acknowledgement',async()=>{
 const f=await command('applicant','save_financial_request',{kind:'payment',purpose:'Membership fee test',amount:'100.25',reference:'SYNTHETIC-REFERENCE'});
 await upload('applicant',12,f.id);
 await command('applicant','submit_financial_request',{id:f.id,version:1});
 const getNotice=async()=> (await db.query("select * from app_private.notifications where record_id=$1 and user_id=$2 and kind='review'",[f.id,ids.officer])).rows[0];
 const notice=await getNotice();
 await command('officer','acknowledge_notification',{id:notice.id});
 assert.notEqual((await getNotice()).acknowledged_at,null);
 assert.equal((await getNotice()).completed_at,null);
 await command('officer','verify_financial_request',{id:f.id,version:2,status:'returned_for_correction',remark:'Correct the payment reference'});
 assert.notEqual((await getNotice()).completed_at,null);
 await command('applicant','save_financial_request',{id:f.id,version:3,kind:'payment',purpose:'Membership fee test',amount:'100.25',reference:'CORRECTED-SYNTHETIC'});
 await command('applicant','submit_financial_request',{id:f.id,version:4});
 assert.equal((await getNotice()).completed_at,null);assert.equal((await getNotice()).acknowledged_at,null);
 assert.equal((await getNotice()).id,notice.id);
 await command('officer','verify_financial_request',{id:f.id,version:5,status:'verified',remark:'Proof checked'});
 const fee=await command('officer','assess_fee',{user_id:ids.applicant,purpose:'Membership fee test',amount:'100.25',due_on:'2030-01-01'});
 const feeNotice=(await rpc('applicant','club_workspace',[],'')).notifications.find(n=>n.record_id===fee.id);
 assert.ok(feeNotice.due_at);assert.equal(feeNotice.completed_at,null);
 await denied(()=>command('applicant','link_fee_payment',{id:fee.id,payment_id:f.id}));
 await command('officer','link_fee_payment',{id:fee.id,payment_id:f.id});
 assert.notEqual((await rpc('applicant','club_workspace',[],'')).notifications.find(n=>n.record_id===fee.id).completed_at,null);
});
test('M1 full profile editing validates contact, emergency contact and archery details',async()=>{
 await command('applicant','save_profile',{full_name:'Alex Archer',phone:'0917 123 4567',emergency_contact_name:'Pat Archer',emergency_contact_phone:'09181234567',
  program:'BS Information Technology',year_level:'3rd year',dominant_hand:'right',bow_type:'recurve',draw_weight:'26',arrow_length:'28',experience_level:'intermediate',bio:'Prefers 18 m indoor practice.'});
 const p=await row('profiles',ids.applicant);
 assert.equal(p.bow_type,'recurve');assert.equal(Number(p.draw_weight),26);assert.equal(p.emergency_contact_name,'Pat Archer');
 await denied(()=>command('applicant','save_profile',{full_name:'Alex Archer',phone:'call me'}),/valid mobile/);
 await denied(()=>command('applicant','save_profile',{full_name:'Alex Archer',emergency_contact_name:'Pat'}),/both/);
 await assert.rejects(()=>command('applicant','save_profile',{full_name:'Alex Archer',bow_type:'crossbow'}));
 await assert.rejects(()=>command('applicant','save_profile',{full_name:'Alex Archer',draw_weight:'120'}));
 await denied(()=>command('applicant','save_profile',{full_name:''}),/valid full_name/);
 assert.equal((await row('profiles',ids.applicant)).bow_type,'recurve','rejected saves leave the profile unchanged');
});
test('M3/M8 waitlist, cancellation, automatic promotion and closed registration',async()=>{
 const e=await command('officer','save_event',{title:'Waitlist practice',description:'Capacity one',venue:'Range',starts_at:future(30),ends_at:future(32),registration_opens:future(-2),registration_closes:future(20),capacity:1});
 await command('officer','publish_event',{id:e.id,version:1});
 assert.equal((await command('applicant','register_event',{event_id:e.id})).status,'registered');
 const w=await command('successor','register_event',{event_id:e.id});
 assert.equal(w.status,'waitlisted');
 assert.equal((await command('successor','register_event',{event_id:e.id})).id,w.id,'joining twice is idempotent');
 const stats=(await rpc('successor','club_workspace',[],'')).event_stats.find(s=>s.event_id===e.id);
 assert.equal(Number(stats.registered),1);assert.equal(Number(stats.waitlisted),1);assert.equal(Number(stats.waitlist_position),1);
 const officerView=await rpc('officer','club_workspace',[],'');
 assert.equal(officerView.waitlist.filter(x=>x.event_id===e.id).length,1);
 const memberView=await rpc('applicant','club_workspace',[],'');
 assert.equal(memberView.waitlist.length,0,'members cannot see who else is waitlisted');
 await denied(()=>command('other','register_event',{event_id:e.id}),/membership/);
 await command('applicant','cancel_registration',{event_id:e.id});
 const seats=(await db.query('select user_id from app_private.registrations where event_id=$1',[e.id])).rows;
 assert.deepEqual(seats.map(r=>r.user_id),[ids.successor],'earliest waitlisted member is promoted');
 assert.equal((await db.query("select count(*)::int n from app_private.notifications where user_id=$1 and record_id=$2 and kind='promoted'",[ids.successor,e.id])).rows[0].n,1);
 assert.equal((await command('applicant','register_event',{event_id:e.id})).status,'waitlisted','a cancelled member can rejoin at the back of the line');
 await command('applicant','cancel_registration',{event_id:e.id});
 await denied(()=>command('applicant','cancel_registration',{event_id:e.id}),/not registered/);
 const closed=await command('officer','save_event',{title:'Closed practice',description:'Deadline passed',venue:'Range',starts_at:future(10),ends_at:future(12),registration_opens:future(-5),registration_closes:future(-1),capacity:5});
 await command('officer','publish_event',{id:closed.id,version:1});
 await denied(()=>command('applicant','register_event',{event_id:closed.id}),/closed/);
});
test('M3 cancellation is refused once attendance is recorded',async()=>{
 const e=await command('officer','save_event',{title:'Attended practice',description:'Attendance first',venue:'Range',starts_at:future(30),ends_at:future(32),registration_opens:future(-2),registration_closes:future(20),capacity:3});
 await command('officer','publish_event',{id:e.id,version:1});
 await command('applicant','register_event',{event_id:e.id});
 await command('officer','record_attendance',{event_id:e.id,user_id:ids.applicant,status:'excused',remark:'Pre-approved excuse'});
 await denied(()=>command('applicant','cancel_registration',{event_id:e.id}),/Attendance/);
});
test('M5/M10 member cancels an unreleased request; live equipment status follows the lifecycle',async()=>{
 const eq=await command('officer','save_equipment',{label:'Recurve 02',category:'Bow',specifications:'Training riser',handedness:'right',draw_weight:22,bow_setup:'recurve',condition:'serviceable'});
 const state=async()=>(await rpc('applicant','club_workspace',[],'')).equipment_status.find(s=>s.equipment_id===eq.id).state;
 assert.equal(await state(),'available');
 const req={equipment_id:eq.id,starts_at:future(1),ends_at:future(5),handedness:'right',maximum_draw_weight:28,bow_setup:'recurve',purpose:'Practice'};
 const a=await command('applicant','request_borrowing',req);
 await denied(()=>command('successor','cancel_borrowing',{id:a.id,version:1}),/not found/);
 await command('applicant','cancel_borrowing',{id:a.id,version:1});
 assert.equal((await row('borrowings',a.id)).status,'cancelled');
 await denied(()=>command('officer','review_borrowing',{id:a.id,version:2,status:'approved',compatibility_confirmed:true,remark:'Late'}),/transition/);
 const b=await command('applicant','request_borrowing',req);
 await command('officer','review_borrowing',{id:b.id,version:1,status:'approved',compatibility_confirmed:true,remark:'Fit checked'});
 assert.equal(await state(),'reserved');
 await command('officer','review_borrowing',{id:b.id,version:2,status:'released',compatibility_confirmed:true,remark:'Handed over'});
 assert.equal(await state(),'in_use');
 await denied(()=>command('applicant','cancel_borrowing',{id:b.id,version:3}),/released/);
 await command('officer','review_borrowing',{id:b.id,version:3,status:'returned',condition:'serviceable',remark:'Returned complete'});
 assert.equal(await state(),'available');
});
test('M5 borrowing periods longer than 14 days are refused',async()=>{
 const eq=(await db.query("select id from app_private.equipment where label='Recurve 02'")).rows[0];
 await denied(()=>command('applicant','request_borrowing',{equipment_id:eq.id,starts_at:future(2),ends_at:future(2+24*15),handedness:'right',bow_setup:'recurve',purpose:'Semester'}),/14 days/);
});
test('M1 verified names: members request corrections that another membership officer decides',async()=>{
 await grant('officer','membership_officer');
 const current=(await row('profiles',ids.applicant)).full_name;
 await denied(()=>command('applicant','save_profile',{full_name:'Renamed Archer',phone:''}),/verified/);
 await command('applicant','save_profile',{full_name:current,phone:'0917 123 4567'});
 await denied(()=>command('applicant','request_name_correction',{requested_name:current,reason:'Same'}),/same/);
 await denied(()=>command('applicant','request_name_correction',{requested_name:'Alexandra Archer'}),/valid reason/);
 const r=await command('applicant','request_name_correction',{requested_name:'Alexandra Archer',reason:'Misspelled first name on application'});
 await denied(()=>command('applicant','request_name_correction',{requested_name:'Other',reason:'Again'}),/pending/);
 await denied(()=>command('applicant','review_name_correction',{id:r.id,status:'approved',decision:'Self'}),/Permission/);
 assert.equal((await rpc('other','club_workspace',[],'')).name_corrections.length,0,'others cannot read the request');
 await denied(()=>command('officer','review_name_correction',{id:r.id,status:'approved'}),/valid decision/);
 await command('officer','review_name_correction',{id:r.id,status:'approved',decision:'Checked against student ID'});
 assert.equal((await row('profiles',ids.applicant)).full_name,'Alexandra Archer');
 assert.equal((await row('name_corrections',r.id)).status,'approved');
 await denied(()=>command('officer','review_name_correction',{id:r.id,status:'rejected',decision:'Late'}),/not found/);
 assert.equal((await db.query("select count(*)::int n from app_private.audit_log where record_id=$1 and action='review_name_correction'",[r.id])).rows[0].n,1);
});
test('M1 applicants without a membership still edit their own name',async()=>{
 await command('other','save_profile',{full_name:'Fresh Applicant'});
 assert.equal((await row('profiles',ids.other)).full_name,'Fresh Applicant');
 await denied(()=>command('other','request_name_correction',{requested_name:'X',reason:'Y'}),/Applicants/);
});
test('M7/M12 membership fee gates activation; documents are verified one by one; rejected applicants may reapply',async()=>{
 await grant('officer','membership_officer');await grant('officer','treasurer');
 await denied(()=>command('applicant','set_membership_fee',{amount:500}),/Permission/);
 await command('admin','set_membership_fee',{amount:500});
 let a=await command('other','save_application',{full_name:'Fresh Applicant',phone:'09170000000',student_number:'TEST-9'});
 await upload('other',2,a.id);
 a=await command('other','submit_application',{version:a.version,full_name:'Fresh Applicant',student_number:'TEST-9',confirmed:true});
 await denied(()=>command('officer','review_application',{id:a.id,version:a.version,status:'approved',category:'Regular member',requirements_verified:true}),/Verify every uploaded document/);
 const doc=(await db.query('select id from app_private.documents where module=2 and record_id=$1',[a.id])).rows[0];
 await denied(()=>command('other','verify_document',{id:doc.id}),/Permission/);
 await command('officer','verify_document',{id:doc.id});
 await command('officer','review_application',{id:a.id,version:a.version,status:'approved',category:'Regular member',requirements_verified:true});
 assert.equal((await db.query('select status from app_private.memberships where user_id=$1',[ids.other])).rows[0].status,'Pending payment');
 await denied(()=>command('other','register_event',{event_id:eventId}),/membership/);
 const fee=(await db.query("select * from app_private.fee_assessments where user_id=$1 and purpose='Membership fee'",[ids.other])).rows[0];
 assert.equal(Number(fee.amount),500);
 const notes=async()=>(await db.query("select kind,message,completed_at from app_private.notifications where user_id=$1 and kind in ('screening','membership','fee') order by notified_at",[ids.other])).rows;
 const approvalNotes=await notes();
 assert.equal(approvalNotes.length,1,'one notice for approval with a fee: '+JSON.stringify(approvalNotes));
 assert.match(approvalNotes[0].message,/^Your application was approved\. Next, pay the ₱500 membership fee by .+ to activate your membership\.$/);
 await denied(()=>command('other','save_financial_request',{kind:'payment',purpose:'Membership fee',amount:'500'}),/reference number/);
 const pay=await command('other','save_financial_request',{kind:'payment',purpose:'Membership fee',amount:'500',reference:'9046 2925 14078'});
 assert.equal((await row('financial_records',pay.id)).reference,'9046292514078','spaces from the receipt are removed');
 await denied(()=>command('applicant','save_financial_request',{kind:'payment',purpose:'Membership fee',amount:'500',reference:'9046292514078'}),/already on another payment/);
 await upload('other',12,pay.id);
 await command('other','submit_financial_request',{id:pay.id,version:1});
 await command('officer','verify_financial_request',{id:pay.id,version:2,status:'verified',remark:'Receipt matches'});
 await command('officer','link_fee_payment',{id:fee.id,payment_id:pay.id});
 assert.equal((await db.query('select status from app_private.memberships where user_id=$1',[ids.other])).rows[0].status,'Active');
 const feeNote=(await notes()).filter(n=>n.kind==='fee');
 assert.equal(feeNote.length,1,'paying completes the same notice instead of adding one');
 assert.ok(feeNote[0].completed_at,'the fee notice is completed once paid');
 await command('admin','set_membership_fee',{amount:0});
 // reapply after rejection (fresh fixture user)
 const rid='00000000-0000-4000-8000-0000000000aa';ids.reapplicant=rid;
 await db.query("insert into auth.users values($1,'reapply@example.test',now())",[rid]);
 await as('reapplicant',()=>db.exec('select public.initialize_account()'));
 let r=await command('reapplicant','save_application',{full_name:'Second Try',student_number:'TEST-10'});
 r=await command('reapplicant','submit_application',{version:r.version,full_name:'Second Try',student_number:'TEST-10',confirmed:true});
 await command('officer','review_application',{id:r.id,version:r.version,status:'rejected',remark:'Incomplete eligibility'});
 await denied(()=>command('reapplicant','save_application',{full_name:'Locked'}),/locked/);
 await command('reapplicant','restart_application',{});
 assert.equal((await row('applications',r.id)).status,'draft');
 await command('reapplicant','save_application',{version:(await row('applications',r.id)).version,full_name:'Second Try',student_number:'TEST-10'});
});
test('M12 bulk fee assessment reaches every active member once',async()=>{
 const due=new Date(Date.now()+20*86400000).toISOString().slice(0,10);
 await denied(()=>command('applicant','assess_fee_bulk',{purpose:'Tournament fund',amount:200,due_on:due}),/Permission/);
 const first=await command('officer','assess_fee_bulk',{purpose:'Tournament fund',amount:200,due_on:due});
 const active=(await db.query("select count(*)::int n from app_private.memberships where status='Active'")).rows[0].n;
 assert.equal(first.assessed,active);
 assert.equal((await command('officer','assess_fee_bulk',{purpose:'Tournament fund',amount:200,due_on:due})).assessed,0,'running it twice adds nothing');
 await denied(()=>command('officer','assess_fee_bulk',{purpose:'Old',amount:200,due_on:'2000-01-01'}),/due date/);
});
test('M12 self-approval and same-person approvals are refused',async()=>{
 const f=await command('officer','save_financial_request',{kind:'expense',purpose:'Range tape',amount:'50'}).catch(()=>null);
 if(f){await upload('officer',12,f.id);await command('officer','submit_financial_request',{id:f.id,version:1});
  await denied(()=>command('officer','approve_funds',{id:f.id,office:'finance',remark:'Mine'}),/own/);
  await denied(()=>command('officer','verify_financial_request',{id:f.id,version:2,status:'verified',remark:'Mine'}),/Another finance officer/);}
 const g=await command('applicant','save_financial_request',{kind:'reimbursement',purpose:'Arrow rest',amount:'80'});
 await upload('applicant',12,g.id);await command('applicant','submit_financial_request',{id:g.id,version:1});
 await grant('officer','president');
 await command('officer','approve_funds',{id:g.id,office:'president',remark:'OK'});
 await denied(()=>command('officer','approve_funds',{id:g.id,office:'finance',remark:'Also me'}),/separate people/);
});
test('M3/M8 waitlist promotes until the start; walk-ins; cancelling an activity notifies and releases equipment',async()=>{
 // register while open, then move the deadline into the past to test promotion after it
 const ev=await command('officer','save_event',{title:'Late cancel practice',description:'Capacity one',venue:'Range',starts_at:future(30),ends_at:future(32),registration_opens:future(-3),registration_closes:future(10),capacity:1});
 await command('officer','publish_event',{id:ev.id,version:1});
 await command('applicant','register_event',{event_id:ev.id});
 assert.equal((await command('successor','register_event',{event_id:ev.id})).status,'waitlisted');
 await db.query("update app_private.events set registration_closes=now()-interval '1 minute' where id=$1",[ev.id]);
 await command('applicant','cancel_registration',{event_id:ev.id});
 assert.equal((await db.query('select user_id from app_private.registrations where event_id=$1',[ev.id])).rows[0]?.user_id,ids.successor,'promotion continues after the deadline until the start');
 // walk-in
 const now=await command('officer','save_event',{title:'Open range now',description:'Walk-ins welcome',venue:'Range',starts_at:future(-1),ends_at:future(2),registration_opens:future(-5),registration_closes:future(-1),capacity:1});
 await command('officer','publish_event',{id:now.id,version:1});
 await denied(()=>command('officer','record_walk_in',{event_id:now.id,user_id:ids.reapplicant}),/active members/);
 await command('officer','record_walk_in',{event_id:now.id,user_id:ids.applicant});
 assert.equal((await db.query('select status,remark from app_private.attendance where event_id=$1 and user_id=$2',[now.id,ids.applicant])).rows[0].remark,'Walk-in');
 await denied(()=>command('applicant','record_walk_in',{event_id:now.id,user_id:ids.applicant}),/Permission/);
 // cancellation
 const eq=(await db.query("select id from app_private.equipment where label='Recurve 02'")).rows[0];
 const b=await command('successor','request_borrowing',{equipment_id:eq.id,event_id:ev.id,starts_at:future(29),ends_at:future(33),handedness:'right',bow_setup:'recurve',purpose:'For the practice'});
 const cur=await row('events',ev.id);
 await command('officer','archive_event',{id:ev.id,version:Number(cur.version)});
 assert.equal((await row('borrowings',b.id)).status,'cancelled');
 assert.equal((await db.query("select count(*)::int n from app_private.notifications where record_id=$1 and kind='event_cancelled'",[ev.id])).rows[0].n,1);
});
test('M5/M10 overdue blocks new requests, double booking refused at request, receipt confirmed, damage alerts finance',async()=>{
 const eq=await command('officer','save_equipment',{label:'Recurve 03',category:'Bow',specifications:'Club bow',handedness:'right',draw_weight:20,bow_setup:'recurve',condition:'serviceable'});
 const eq2=await command('officer','save_equipment',{label:'Recurve 04',category:'Bow',specifications:'Club bow',handedness:'right',draw_weight:20,bow_setup:'recurve',condition:'serviceable'});
 const req={equipment_id:eq.id,starts_at:future(1),ends_at:future(3),handedness:'right',bow_setup:'recurve',purpose:'Practice'};
 const a=await command('applicant','request_borrowing',req);
 await command('officer','review_borrowing',{id:a.id,version:1,status:'approved',compatibility_confirmed:true,remark:'OK'});
 await denied(()=>command('successor','request_borrowing',req),/already booked/);
 await command('officer','review_borrowing',{id:a.id,version:2,status:'released',compatibility_confirmed:true,remark:'Handed over'});
 await denied(()=>command('successor','confirm_receipt',{id:a.id}),/handed over to you/);
 await command('applicant','confirm_receipt',{id:a.id});
 assert.ok((await row('borrowings',a.id)).received_at);
 await db.query("update app_private.borrowings set starts_at=now()-interval '5 hours', ends_at=now()-interval '1 hour' where id=$1",[a.id]);
 await denied(()=>command('applicant','request_borrowing',{...req,equipment_id:eq2.id,starts_at:future(5),ends_at:future(7)}),/overdue/);
 await command('officer','review_borrowing',{id:a.id,version:3,status:'returned',condition:'damaged',remark:'Limb cracked'});
 assert.equal((await db.query("select count(*)::int n from app_private.notifications where record_id=$1 and message like 'Equipment returned damaged%'",[a.id])).rows[0].n>0,true);
 await command('applicant','request_borrowing',{...req,equipment_id:eq2.id,starts_at:future(5),ends_at:future(7)});
});
test('M13 attendance rate excludes excused absences everywhere',async()=>{
 const ev=await command('officer','save_event',{title:'Rate check',description:'Three members',venue:'Range',starts_at:future(20),ends_at:future(22),registration_opens:future(-2),registration_closes:future(10),capacity:5});
 await command('officer','publish_event',{id:ev.id,version:1});
 for(const p of ['applicant','successor','other'])await command(p,'register_event',{event_id:ev.id});
 await command('officer','record_attendance',{event_id:ev.id,user_id:ids.applicant,status:'present',remark:'Here'});
 await command('officer','record_attendance',{event_id:ev.id,user_id:ids.successor,status:'excused',remark:'Exam'});
 await command('officer','record_attendance',{event_id:ev.id,user_id:ids.other,status:'absent',remark:'No show'});
 const rep=await rpc('officer','club_report',['2000-01-01','2099-12-31'],'$1,$2');
 const act=rep.activities.find(x=>x.id===ev.id);
 assert.equal(Number(act.attendance_rate),50);assert.equal(Number(act.excused),1);
 const stats=(await rpc('officer','club_workspace',[],'')).event_stats.find(s=>s.event_id===ev.id);
 assert.equal(Number(stats.excused),1);
});
test('Registration story: sign-up → application → correction with a replaced file → approval → activity registration',async()=>{
 const before=(await db.query('select application_requirements from app_private.settings')).rows[0].application_requirements;
 await db.query("update app_private.settings set application_requirements=array['School ID']");
 try{
  // sign-up: an unconfirmed address cannot enter; a confirmed one gets a bare profile and no authority
  const nid='00000000-0000-4000-8000-0000000000bb';ids.newcomer=nid;
  await db.query("insert into auth.users values($1,'newcomer@example.test',null)",[nid]);
  await denied(()=>as('newcomer',()=>db.exec('select public.initialize_account()')),/Verify your email/);
  await db.query('update auth.users set email_confirmed_at=now() where id=$1',[nid]);
  await as('newcomer',()=>db.exec('select public.initialize_account()'));
  await denied(()=>command('newcomer','register_event',{event_id:eventId}),/membership/);
  // application: details, then the required file, then submit
  let a=await command('newcomer','save_application',{full_name:'New Comer',phone:'09171234567',student_number:'12345678',confirmed:false});
  assert.equal((await db.query('select full_name from app_private.profiles where id=$1',[nid])).rows[0].full_name,'New Comer','officers see the applicant by name before approval');
  await denied(()=>command('newcomer','submit_application',{version:a.version,full_name:'New Comer',confirmed:true}),/Missing requirement: School ID/);
  await upload('newcomer',2,a.id,'School ID');
  await denied(()=>command('newcomer','submit_application',{version:a.version,full_name:'New Comer'}),/Confirm/);
  a=await command('newcomer','submit_application',{version:a.version,full_name:'New Comer',phone:'09171234567',student_number:'12345678',confirmed:true});
  assert.equal(a.status,'pending');
  await denied(()=>upload('newcomer',2,a.id,'School ID'),/row-level security|locked/);
  await denied(()=>command('newcomer','save_application',{version:a.version,full_name:'Changed'}),/locked/);
  // officer returns it; the applicant replaces the blurry file and submits again
  await command('officer','review_application',{id:a.id,version:a.version,status:'returned_for_correction',remark:'School ID photo is blurry'});
  a=await row('applications',a.id);
  assert.equal(a.status,'returned_for_correction');
  await upload('newcomer',2,a.id,'School ID');
  const docs=(await db.query('select id,superseded_by from app_private.documents where module=2 and record_id=$1 order by created_at,id',[a.id])).rows;
  assert.equal(docs.length,2);
  assert.equal(docs.filter(d=>d.superseded_by===null).length,1,'only the newest file is current');
  a=await command('newcomer','submit_application',{version:Number(a.version),full_name:'New Comer',phone:'09171234567',student_number:'12345678',confirmed:true});
  // approval waits for the current file only, never for the replaced one
  await denied(()=>command('officer','review_application',{id:a.id,version:a.version,status:'approved',category:'Regular member',requirements_verified:true}),/Verify every uploaded document/);
  const current=docs.find(d=>d.superseded_by===null);
  await command('officer','verify_document',{id:current.id});
  await command('officer','review_application',{id:a.id,version:a.version,status:'approved',category:'Regular member',requirements_verified:true});
  const m=(await db.query('select status from app_private.memberships where user_id=$1',[nid])).rows[0];
  assert.equal(m.status,'Active','no fee configured, so approval activates');
  assert.equal((await db.query('select full_name from app_private.profiles where id=$1',[nid])).rows[0].full_name,'New Comer');
  // activity registration: first slot, then the waitlist
  const ev=await command('officer','save_event',{title:'First practice',description:'One slot',venue:'Range',starts_at:future(40),ends_at:future(42),registration_opens:future(-1),registration_closes:future(30),capacity:1});
  await command('officer','publish_event',{id:ev.id,version:1});
  const first=await command('newcomer','register_event',{event_id:ev.id});
  const again=await command('newcomer','register_event',{event_id:ev.id});
  assert.deepEqual(again,first,'registering twice (e.g. a retried tap) keeps the one slot');
  assert.equal((await db.query('select count(*)::int n from app_private.registrations where event_id=$1',[ev.id])).rows[0].n,1);
  assert.equal((await command('applicant','register_event',{event_id:ev.id})).status,'waitlisted');
  await command('newcomer','cancel_registration',{event_id:ev.id});
  assert.equal((await db.query('select user_id from app_private.registrations where event_id=$1',[ev.id])).rows[0]?.user_id,ids.applicant,'the waitlisted member takes the slot');
 }finally{await db.query('update app_private.settings set application_requirements=$1',[before]);}
});
test('Restarting after a rejection clears earlier document verifications',async()=>{
 const id='00000000-0000-4000-8000-0000000000cc';ids.retry=id;
 await db.query("insert into auth.users values($1,'retry@example.test',now())",[id]);
 await as('retry',()=>db.exec('select public.initialize_account()'));
 let a=await command('retry','save_application',{full_name:'Re Try'});
 await upload('retry',2,a.id,'School ID');
 a=await command('retry','submit_application',{version:a.version,full_name:'Re Try',confirmed:true});
 const d=(await db.query('select id from app_private.documents where record_id=$1',[a.id])).rows[0];
 await command('officer','verify_document',{id:d.id});
 await command('officer','review_application',{id:a.id,version:a.version,status:'rejected',remark:'Not enrolled this term'});
 await command('retry','restart_application',{});
 assert.equal((await db.query('select verified_by from app_private.documents where id=$1',[d.id])).rows[0].verified_by,null);
});

test('Treasurer sets where to pay; applicants can read it, members cannot change it',async()=>{
 const details={gcash_name:'DLSU Archery Club',gcash_number:'0917 000 0000',bank_name:'',bank_account_name:'',bank_account_number:''};
 await denied(()=>rpc('applicant','set_payment_details',[JSON.stringify(details)],'$1::jsonb'),/Permission/);
 await denied(()=>rpc('officer','set_payment_details',[JSON.stringify({gcash_name:'x'})],'$1::jsonb'),/at least a GCash number/);
 const saved=await rpc('officer','set_payment_details',[JSON.stringify(details)],'$1::jsonb');
 assert.equal(saved.gcash_number,'0917 000 0000');
 const seen=await as('applicant',async()=>(await db.query('select payment_details from app_private.settings')).rows[0].payment_details);
 assert.equal(seen.gcash_name,'DLSU Archery Club','the applicant\'s pay step can show it');
});

test('A role is held once; renewing is allowed only near the end of the term',async()=>{
 await grant('successor','reports_officer');
 await denied(()=>command('admin','assign_role',{user_id:ids.successor,role_id:'reports_officer',position:'Again',ends_at:'2036-01-01',reason:'Duplicate'}),/already Reports officer until/);
 await db.query("update app_private.role_assignments set ends_at=now()+interval '10 days' where user_id=$1 and role_id='reports_officer'",[ids.successor]);
 await command('admin','assign_role',{user_id:ids.successor,role_id:'reports_officer',position:'Reports officer',ends_at:'2036-01-01',reason:'Renewed term'});
});

test('Each club picks the desks a role opens; the last Administrator cannot lose Administration',async()=>{
 const save=(who,id,name,perms,isNew)=>rpc(who,'save_role',[id,name,perms,isNew],'$1,$2,$3::text[],$4');
 const can=async(person,p)=>{await db.query("select set_config('request.jwt.claim.sub',$1,false)",[ids[person]]);return (await db.query('select app_private.can($1) as ok',[p])).rows[0].ok;};
 await grant('successor','equipment_officer');
 assert.equal(await can('successor','training'),false);
 await denied(()=>save('applicant','equipment_officer','Armorer',['equipment','training'],false),/Permission/);
 await save('admin','equipment_officer','Armorer',['equipment','training','activities','training'],false);
 assert.equal(await can('successor','training'),true,'holders get the new desk right away');
 assert.deepEqual((await db.query("select permissions from app_private.roles where id='equipment_officer'")).rows[0].permissions,['activities','equipment','training']);
 await denied(()=>save('admin','range_marshal','armorer',['equipment'],true),/already exists/);
 await denied(()=>save('admin','range_marshal','Range marshal',['catering'],true),/Unknown desk/);
 await save('admin','range_marshal','Range marshal',['activities'],true);
 await denied(()=>save('admin','administrator','Administrator',['reports'],false),/keep Administration/);
 await rpc('admin','reset_role',['equipment_officer'],'$1');
 assert.deepEqual((await db.query("select name,permissions from app_private.roles where id='equipment_officer'")).rows[0].permissions,['equipment']);
 assert.equal(await can('successor','training'),false);
 await denied(()=>rpc('admin','reset_role',['range_marshal'],'$1'),/no default/);
});

test('The Equipment desk attaches item photos; active members can see them, others cannot add them',async()=>{
 await grant('officer','equipment_officer');
 const eq=await command('officer','save_equipment',{label:'Photo Bow 01',category:'Bow',specifications:'Photo test',handedness:'right',draw_weight:20,bow_setup:'recurve',condition:'serviceable'});
 const path=await upload('officer',10,eq.id,'Photo','image/jpeg');
 // pick an active member who has no equipment role
 const plain=[];
 for(const n of ['other','applicant']){
  const active=(await db.query("select 1 from app_private.memberships where user_id=$1 and status='Active'",[ids[n]])).rows.length;
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[ids[n]]);
  const equip=(await db.query("select app_private.can('equipment') as ok")).rows[0].ok;
  plain.push({n,active,equip});
 }
 const reader=plain.find(p=>p.active&&!p.equip);
 assert.ok(reader,'fixture: an active member without an equipment role');
 await assert.rejects(()=>upload(reader.n,10,eq.id,'Photo','image/jpeg'),'a member cannot add photos');
 const seen=await as(reader.n,async()=>(await db.query('select path from app_private.documents where module=10 and record_id=$1',[eq.id])).rows);
 assert.equal(seen[0]?.path,path,'an active member sees the photo');
});
