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
 await denied(()=>command('admin','assign_role',{user_id:ids.successor,role_id:'membership_officer',position:'Membership officer',ends_at:'2035-01-01',reason:'Not a member yet'}),/Active members/);
 await ensureMember('successor');
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
// Officer roles go only to Active members (migration 038), so role fixtures make the person a member first.
async function ensureMember(person){
 if((await db.query('select 1 from app_private.memberships where user_id=$1',[ids[person]])).rows.length)return;
 await db.query("insert into app_private.memberships(user_id,category,status,student_number,approved_by) values($1,'Regular member','Active','',$2)",[ids[person],ids.admin]);
 await db.query("update app_private.memberships set status='Active' where user_id=$1",[ids[person]]);
}
async function grant(person,role){
 // a person holds a role once (migration 025), so fixtures only add roles they lack
 if((await db.query('select 1 from app_private.role_assignments where user_id=$1 and role_id=$2 and retired_at is null and ends_at>now()',[ids[person],role])).rows.length)return;
 await ensureMember(person);
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
  // Announcements no longer create Notices; an edit makes the announcement unread again instead.
  const n=(await db.query('select * from app_private.notifications where record_id=$1 and completed_at is null',[a.id])).rows;
  assert.equal(n.length,0);
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
 await ensureMember('successor');
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
 assert.match(notice.message,/^Check .+'s payment · Membership fee test$/);
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
 await denied(()=>command('applicant','save_profile',{full_name:'Alex Archer',phone:'call me'}),/Philippine mobile/);
 await denied(()=>command('applicant','save_profile',{full_name:'Alex Archer',phone:'0917 123 4567',emergency_contact_name:'Pat'}),/both/);
 await assert.rejects(()=>command('applicant','save_profile',{full_name:'Alex Archer',phone:'0917 123 4567',bow_type:'crossbow'}));
 await assert.rejects(()=>command('applicant','save_profile',{full_name:'Alex Archer',phone:'0917 123 4567',draw_weight:'120'}));
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
 await command('other','save_profile',{full_name:'Fresh Applicant',phone:'+63 917 123 4567'});
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

test('Rounds keep their arrows; the server works the score out from them',async()=>{
 await grant('officer','training_officer');
 const end=(...ps)=>ps.map(p=>({p}));
 const arrows={ends:[end('9','6','6','6','5','4'),end('9','9','8','7','6','5'),end('6','6','5','5','4','M')]};
 // A member taps a round in and sends it to the coach in one step; the totals they send are ignored.
 const mine=await rpc('successor','save_round',['save_personal_training',JSON.stringify({training_date:'2026-11-05',distance:18,target_face:'WA 40 cm',category:'Recurve',scoring_format:'Practice',score:999,maximum_score:999,arrows}),true],'$1,$2::jsonb,$3');
 let r=await row('training_records',mine.id);
 assert.equal(Number(r.score),106);assert.equal(Number(r.maximum_score),180);assert.equal(r.validation,'pending');
 assert.equal(r.arrows.ends[2][5].p,'M');
 await denied(()=>rpc('successor','save_round',['save_personal_training',JSON.stringify({training_date:'2026-11-05',distance:18,target_face:'x',category:'Recurve',scoring_format:'P',arrows:{ends:[[{p:'11'}]]}}),false],'$1,$2::jsonb,$3'),/Arrow scores/);
 // The coach imports a scoring-app session with positions.
 const rows=[{import_key:'arrows:test:1',user_id:ids.successor,training_date:'2026-11-06',distance:18,target_face:'WA Full (40cm)',category:'Recurve',scoring_format:'Practice · 3 arrows',
  arrows:{ends:[[{p:'X',x:0.01,y:0.02},{p:'9',x:0.1,y:0.05},{p:'M',x:0.3,y:1.07}]]}}];
 const out=await rpc('officer','import_rounds',[JSON.stringify(rows)],'$1::jsonb');
 r=await row('training_records',out[0].id);
 assert.equal(Number(r.score),19);assert.equal(Number(r.maximum_score),30);assert.equal(r.validation,'validated');assert.equal(r.arrows.ends[0][0].x,0.01);
 await denied(()=>rpc('officer','import_rounds',[JSON.stringify(rows)],'$1::jsonb'),/already imported/);
 await denied(()=>rpc('successor','import_rounds',[JSON.stringify(rows)],'$1::jsonb'),/Permission/);
});

test('Delete only what can safely go; official records stay',async()=>{
 const del=(who,kind,id,reason='')=>rpc(who,'delete_record',[kind,id,reason],'$1,$2::uuid,$3');
 // a member's own personal round
 const mine=await rpc('successor','save_round',['save_personal_training',JSON.stringify({training_date:'2026-11-07',distance:18,target_face:'WA 40 cm',category:'Recurve',scoring_format:'Practice',arrows:{ends:[[{p:'9'}]]}}),false],'$1,$2::jsonb,$3');
 await denied(()=>del('applicant','training_round',mine.id),/Only your own/);
 await del('successor','training_round',mine.id);
 assert.equal(await row('training_records',mine.id),undefined);
 assert.ok((await db.query("select 1 from app_private.audit_log where action='delete_training_round' and record_id=$1",[mine.id])).rows.length);
 // a validated round needs a coach and a reason
 const sent=await rpc('successor','save_round',['save_personal_training',JSON.stringify({training_date:'2026-11-08',distance:18,target_face:'WA 40 cm',category:'Recurve',scoring_format:'Practice',arrows:{ends:[[{p:'8'}]]}}),true],'$1,$2::jsonb,$3');
 await denied(()=>del('successor','training_round',sent.id),/Only your own/);
 await grant('officer','training_officer');
 await command('officer','validate_training',{id:sent.id,version:(await row('training_records',sent.id)).version,validation:'validated',feedback:'ok'});
 await denied(()=>del('officer','training_round',sent.id),/Say why/);
 await del('officer','training_round',sent.id,'Duplicate import');
 // equipment: never borrowed goes; borrowed stays
 await grant('officer','equipment_officer');
 const eq=await command('officer','save_equipment',{label:'Delete Me Bow',category:'Bow',specifications:'x',handedness:'right',draw_weight:20,bow_setup:'recurve',condition:'serviceable'});
 await denied(()=>del('applicant','equipment',eq.id),/Permission/);
 await del('officer','equipment',eq.id);
 const used=(await db.query('select equipment_id from app_private.borrowings limit 1')).rows[0];
 if(used)await denied(()=>del('officer','equipment',used.equipment_id),/Retired/);
 // custom roles nobody held
 await rpc('admin','save_role',['temp_role','Temp role',['reports'],true],'$1,$2,$3::text[],$4');
 await rpc('admin','delete_role',['temp_role'],'$1');
 await denied(()=>rpc('admin','delete_role',['president'],'$1'),/Built-in/);
});

test('A whole training goes to the coach at once',async()=>{
 const save=(d)=>rpc('successor','save_round',['save_personal_training',JSON.stringify({training_date:'2026-11-12',distance:30,target_face:'WA Full (40cm)',category:'Recurve',scoring_format:'Practice',arrows:{ends:[[{p:d}]],session:{session_id:'s-demo',title:'Capstone App Demo'}}}),false],'$1,$2::jsonb,$3');
 const a=await save('X'),b=await save('9');
 await denied(()=>rpc('applicant','submit_training_session',[[a.id,b.id]],'$1::uuid[]'));
 const out=await rpc('successor','submit_training_session',[[a.id,b.id]],'$1::uuid[]');
 assert.equal(out.sent,2);
 assert.equal((await row('training_records',a.id)).validation,'pending');assert.equal((await row('training_records',b.id)).validation,'pending');
 assert.equal((await row('training_records',a.id)).arrows.session.session_id,'s-demo');
});

test('M10 inventory: the club sets up categories and places, items follow the category fields, sheets import',async()=>{
 const ws=await rpc('officer','club_workspace',[],'');
 const cats=Object.fromEntries(ws.inventory_categories.map(c=>[c.name,c]));
 const places=Object.fromEntries(ws.inventory_places.map(p=>[p.name,p]));
 assert.equal(cats.Riser.fields[0].label,'Hand'); assert.ok(places['Short White Cabinet (Left)']);
 // Members can't change the inventory.
 await denied(()=>rpc('applicant','save_inventory_place',[JSON.stringify({name:'Locker'})],'$1::jsonb'),/Equipment desk|permission/);
 // A category with a choice and a range; repeats and blanks in choices are dropped, order kept.
 const cat=await rpc('officer','save_inventory_category',[JSON.stringify({name:'Test Limbs',color:'orange',labelled:true,
  fields:[{label:'Label',type:'text'},{label:'Size',type:'choice',options:['S','M','',' M','L']},{label:'Draw weight (lbs.)',type:'range',on_list:true}]})],'$1::jsonb');
 const saved=(await db.query('select fields from app_private.inventory_categories where id=$1',[cat.id])).rows[0].fields;
 assert.deepEqual(saved[1].options,['S','M','L']); assert.equal(saved[2].key,'draw_weight_lbs');
 await denied(()=>rpc('officer','save_inventory_category',[JSON.stringify({name:'Bad',fields:[{label:'A'},{label:'a'}]})],'$1::jsonb'),/both called/);
 const place=await rpc('officer','save_inventory_place',[JSON.stringify({name:'Locker 1'})],'$1::jsonb');
 // An item: label is built and kept unique; draw weight and hand feed borrowing matches.
 const item={name:'Akusta Breeze',category_id:cat.id,place_id:place.id,details:{label:'M4',size:'M',draw_weight_lbs:'20-22'},notes:'confirm label'};
 const a=await rpc('officer','save_inventory_item',[JSON.stringify(item)],'$1::jsonb');
 const b=await rpc('officer','save_inventory_item',[JSON.stringify(item)],'$1::jsonb');
 const ra=await row('equipment',a.id), rb=await row('equipment',b.id);
 assert.equal(ra.label,'Akusta Breeze M4'); assert.equal(rb.label,'Akusta Breeze M4 #2'); assert.equal(Number(ra.draw_weight),22);
 assert.equal(ra.category,'Test Limbs'); assert.equal(ra.owner,'Club property'); assert.match(ra.specifications,/Size: M/);
 await denied(()=>rpc('officer','save_inventory_item',[JSON.stringify({...item,details:{size:'XL'}})],'$1::jsonb'),/one of/);
 await denied(()=>rpc('officer','save_inventory_item',[JSON.stringify({...item,details:{draw_weight_lbs:'heavy'}})],'$1::jsonb'),/range/);
 await denied(()=>rpc('officer','save_inventory_item',[JSON.stringify({...item,quantity:3})],'$1::jsonb'),/one by one/);
 const riser=await rpc('officer','save_inventory_item',[JSON.stringify({name:'SF Axiom',category_id:cats.Riser.id,details:{hand:'LH',length:'23'}})],'$1::jsonb');
 assert.equal((await row('equipment',riser.id)).handedness,'left');
 // A place or category with items can't be deleted; an empty one can.
 await denied(()=>rpc('officer','delete_inventory_group',['place',place.id],'$1,$2::uuid'),/Move/);
 const empty=await rpc('officer','save_inventory_place',[JSON.stringify({name:'Spare shelf'})],'$1::jsonb');
 await rpc('officer','delete_inventory_group',['place',empty.id],'$1,$2::uuid');
 // Import: all or nothing, with the row number in the error.
 const rows=[{name:'Focus',category_id:cats.Sight.id,quantity:4,details:{size:'L',color:'Black',pins:'6 pins'},notes:'2 circle sights'},
  {name:'SJA-40',category_id:cats.Sight.id,quantity:4,details:{size:'S'}}];
 await denied(()=>rpc('officer','import_inventory',[place.id,JSON.stringify([...rows,{name:'Bad',category_id:cats.Sight.id,details:{size:'XXL'}}])],'$1::uuid,$2::jsonb'),/Row 3/);
 assert.equal((await db.query("select count(*)::int n from app_private.equipment where name='Focus'")).rows[0].n,0);
 assert.equal((await rpc('officer','import_inventory',[place.id,JSON.stringify(rows)],'$1::uuid,$2::jsonb')).imported,2);
 // A category that isn't lent out can't be borrowed.
 await rpc('officer','save_inventory_category',[JSON.stringify({id:cats.Sight.id,version:cats.Sight.version,name:'Sight',fields:cats.Sight.fields,borrowable:false})],'$1::jsonb');
 const focus=(await db.query("select id from app_private.equipment where name='Focus'")).rows[0];
 await denied(()=>command('successor','request_borrowing',{equipment_id:focus.id,starts_at:future(60),ends_at:future(62),handedness:'right',bow_setup:'',purpose:'Practice'}),/not lent/);
});

test('M8 announcements: each member marks their own as read, and only sees their own marks',async()=>{
 const a=await command('officer','publish_announcement',{title:'Range closed Saturday',message:'Set-up for the UAAP event.'});
 const id=a.id||(await db.query("select id from app_private.announcements where title='Range closed Saturday'")).rows[0].id;
 assert.equal((await rpc('successor','mark_announcements_read',['{'+id+'}'],'$1::uuid[]')).marked,1);
 assert.equal((await rpc('successor','mark_announcements_read',['{'+id+'}'],'$1::uuid[]')).marked,0);
 const mine=(await rpc('successor','club_workspace',[],'')).announcement_reads;
 assert.equal(mine.length,1); assert.equal(mine[0].announcement_id,id);
 assert.equal((await rpc('officer','club_workspace',[],'')).announcement_reads.filter(r=>r.announcement_id===id).length,0);
 await denied(()=>rpc('successor','mark_announcements_read',['{}'],'$1::uuid[]'),/1 to 200/);
});

test('M2 application details: college, program and year are saved, required to send, and copied to the profile on approval',async()=>{
 ids.newbie='00000000-0000-4000-8000-000000000099';
 await db.query('insert into auth.users values($1,$2,now())',[ids.newbie,'newbie@example.test']);
 await as('newbie',()=>db.exec('select public.initialize_account()'));
 const base={full_name:'Nina Newbie',phone:'09171234567',student_number:'12345678',experience:''};
 await denied(()=>rpc('newbie','save_application_details',['save_application',JSON.stringify({...base,emergency_contact_name:'Mom'})],'$1,$2::jsonb'),/both/);
 const a=await rpc('newbie','save_application_details',['save_application',JSON.stringify({...base,sex:'Female',college:'CCS',program:'BS Information Technology',year_level:'2nd year',
  emergency_contact_name:'Maria Newbie',emergency_contact_phone:'09181234567'})],'$1,$2::jsonb');
 assert.equal(a.college,'CCS'); assert.equal(a.sex,'Female');
 await denied(()=>rpc('newbie','save_application_details',['save_application',JSON.stringify({...base,sex:'X'})],'$1,$2::jsonb'),/Male or Female/); assert.equal(a.year_level,'2nd year'); assert.equal(a.full_name,'Nina Newbie');
 await denied(()=>rpc('newbie','save_application_details',['submit_application',JSON.stringify({...base,version:a.version,confirmed:true})],'$1,$2::jsonb'),/sex, college, program and year/);
 await denied(()=>rpc('newbie','save_application_details',['review_application',JSON.stringify({})],'$1,$2::jsonb'),/Unknown/);
 await db.exec('reset role');
 await db.query("update app_private.applications set status='approved' where user_id=$1",[ids.newbie]);
 const p=(await db.query('select sex,college,program,year_level,emergency_contact_name from app_private.profiles where id=$1',[ids.newbie])).rows[0];
 assert.deepEqual(p,{sex:'Female',college:'CCS',program:'BS Information Technology',year_level:'2nd year',emergency_contact_name:'Maria Newbie'});
});

test('M8 announcements no longer create Notices, and an edit makes one unread again',async()=>{
 await command('officer','publish_announcement',{title:'No notice please',message:'Announcements tab only.'});
 const n=(await db.query("select count(*)::int n from app_private.notifications where kind='announcement' and completed_at is null")).rows[0].n;
 assert.equal(n,0);
 const id=(await db.query("select id from app_private.announcements where title='No notice please'")).rows[0].id;
 await rpc('successor','mark_announcements_read',['{'+id+'}'],'$1::uuid[]');
 await command('officer','update_announcement',{id,version:1,title:'No notice please',message:'Changed time.'});
 assert.equal((await rpc('successor','club_workspace',[],'')).announcement_reads.filter(r=>r.announcement_id===id).length,0);
});

test('Testing round 18 Nov: Philippine mobiles, 8-digit ID, emails for unnamed accounts, officer roles need an Active membership',async()=>{
 ids.fresh='00000000-0000-4000-8000-000000000098';
 await db.query('insert into auth.users values($1,$2,now())',[ids.fresh,'Fresh.Sign-Up@example.test']);
 await as('fresh',()=>db.exec('select public.initialize_account()'));
 assert.equal((await row('profiles',ids.fresh)).email,'fresh.sign-up@example.test','an account that has not applied is named by its email');
 const call=(action,data)=>rpc('fresh','save_application_details',[action,JSON.stringify(data)],'$1,$2::jsonb');
 const base={full_name:'Fresh Archer',phone:'09171234567',student_number:'12345678',experience:''};
 await denied(()=>call('save_application',{...base,phone:'+1 415 555 0100'}),/Philippine mobile/);
 await denied(()=>call('save_application',{...base,student_number:'1234567'}),/8 digits/);
 await denied(()=>call('save_application',{...base,student_number:'123456789'}),/8 digits/);
 await denied(()=>call('save_application',{...base,emergency_contact_name:'Mom',emergency_contact_phone:'(02) 8123 4567'}),/Philippine mobile/);
 const a=await call('save_application',{...base,phone:'+63 917 123 4567',emergency_contact_name:'Mom',emergency_contact_phone:'0918-123-4567'});
 await denied(()=>call('submit_application',{...base,phone:'',version:a.version,confirmed:true,sex:'Male',college:'CCS',program:'BSIT',year_level:'1st year'}),/mobile number and DLSU ID/);
 await denied(()=>call('submit_application',{...base,student_number:'',version:a.version,confirmed:true,sex:'Male',college:'CCS',program:'BSIT',year_level:'1st year'}),/mobile number and DLSU ID/);
 // Profile edits keep the mobile number required, and the emergency contact is a Philippine mobile too.
 await denied(()=>command('fresh','save_profile',{full_name:'Fresh Archer',phone:''}),/Enter your mobile/);
 await denied(()=>command('fresh','save_profile',{full_name:'Fresh Archer',phone:'09171234567',emergency_contact_name:'Mom',emergency_contact_phone:'+44 20 7946 0958'}),/Philippine mobile/);
 await command('fresh','save_profile',{full_name:'Fresh Archer',phone:'+639171234567',emergency_contact_name:'Mom',emergency_contact_phone:'09181234567'});
 // Officer roles go only to Active members; since migration 042 the Administrator role does too (no exceptions).
 await denied(()=>command('admin','assign_role',{user_id:ids.fresh,role_id:'treasurer',position:'Treasurer',ends_at:'2035-01-01',reason:'Test'}),/Active members/);
 await denied(()=>command('admin','assign_role',{user_id:ids.fresh,role_id:'administrator',position:'Adviser',ends_at:'2035-01-01',reason:'Adviser access'}),/Active members/);
 assert.equal((await db.query("select count(*)::int n from app_private.role_assignments where user_id=$1",[ids.fresh])).rows[0].n,0);
});

test('Testing round 19 Nov: a non-member\'s existing role can be shortened or retired, not extended',async()=>{
 // A role given before the rule (like a mistaken Administrator) is inserted directly, as the old rule allowed.
 const r=(await db.query("insert into app_private.role_assignments(user_id,role_id,position,starts_at,ends_at,assigned_by,reason) values($1,'administrator','Mistake',now()-interval '1 day',now()+interval '30 days',$2,'Old rule') returning id,ends_at",[ids.fresh,ids.admin])).rows[0];
 const edit=data=>rpc('admin','update_role_term',[JSON.stringify(data)],'$1::jsonb');
 await denied(()=>edit({id:r.id,position:'Mistake',ends_at:'2035-01-01',reason:'Extend'}),/Only Active members/);
 await edit({id:r.id,position:'Mistake',ends_at:new Date(Date.now()+86400000).toISOString(),reason:'Shorten'});
 await command('admin','retire_role',{id:r.id,reason:'Not a member'});
});

test('Testing round 19 Nov: administrators see each account\'s membership standing (only), members see none',async()=>{
 const admin=await rpc('admin','club_workspace',[],'');
 assert.ok(admin.member_standing.some(m=>m.user_id===ids.officer&&m.status==='Active'),'the role picker can tell members apart');
 assert.deepEqual(Object.keys(admin.member_standing[0]).sort(),['status','user_id'],'standing only, no student number or category');
 assert.ok(!admin.member_standing.some(m=>m.user_id===ids.fresh),'an account without a membership has no standing');
 assert.deepEqual((await rpc('applicant','club_workspace',[],'')).member_standing,[]);
});

test('Administration 19 Nov: edit a role term (title, end date) in place, with the previous values in the audit log',async()=>{
 const a=(await db.query("select id,position from app_private.role_assignments where user_id=$1 and retired_at is null and role_id<>'administrator' order by starts_at desc limit 1",[ids.officer])).rows[0];
 assert.ok(a,'fixture officer has a role');
 const edit=(person,data)=>rpc(person,'update_role_term',[JSON.stringify(data)],'$1::jsonb');
 await denied(()=>edit('officer',{id:a.id,ends_at:'2036-01-01',position:'Officer',reason:'Self-extend'}));
 await denied(()=>edit('admin',{id:a.id,ends_at:'2020-01-01',position:'Officer',reason:'Before start'}),/end after it starts/);
 await edit('admin',{id:a.id,ends_at:'2036-06-30T15:59:00Z',position:'Secretary and Auditor',reason:'Term extended by the board'});
 const after=(await db.query('select ends_at,position,reason from app_private.role_assignments where id=$1',[a.id])).rows[0];
 assert.equal(after.position,'Secretary and Auditor'); assert.match(after.reason,/Changed: Term extended by the board/);
 assert.equal(new Date(after.ends_at).toISOString(),'2036-06-30T15:59:00.000Z');
 const log=(await db.query("select detail from app_private.audit_log where module=14 and action='update_role_term' and record_id=$1",[a.id])).rows[0];
 assert.equal(log.detail.previous.position,a.position,'the old title is kept in the log');
});

test('Payments 19 Nov: how it was paid, and the reference number must fit (GCash 13 digits; Maya or bank 6-30 letters or numbers)',async()=>{
 const pay=(data)=>rpc('applicant','save_payment',[JSON.stringify({purpose:'Method test',amount:'150',...data})],'$1::jsonb');
 await denied(()=>pay({reference:'1234567890123'}),/Choose how you paid/);
 await denied(()=>pay({payment_method:'gcash',reference:'FT24ABC123'}),/13-digit/);
 await denied(()=>pay({payment_method:'gcash',reference:'123456789012'}),/13-digit/);
 await denied(()=>pay({payment_method:'bank',reference:'AB1'}),/6 to 30/);
 await denied(()=>pay({payment_method:'bank',reference:'REF#2026/11'}),/6 to 30/);
 const g=await pay({payment_method:'gcash',reference:'5012 3456 78901'});
 const r=await row('financial_records',g.id);
 assert.equal(r.payment_method,'gcash'); assert.equal(r.reference,'5012345678901');
 const b=await pay({payment_method:'bank',reference:'ft24-abc-778'});
 assert.equal((await row('financial_records',b.id)).reference,'FT24-ABC-778','letters allowed for banks, saved in capitals');
 // An older app saving directly still gets the general rule.
 await denied(()=>command('applicant','save_financial_request',{kind:'payment',purpose:'Old app',amount:'10',reference:'X?'}),/6 to 30/);
});

test('Payments 20 Nov: the "pay your fee" notice closes when the payment is sent for checking, and comes back if it is sent back',async()=>{
 const fee=await command('officer','assess_fee',{user_id:ids.applicant,purpose:'Notice fee test',amount:'200',due_on:'2030-01-01'});
 const open=async()=> (await db.query("select completed_at from app_private.notifications where record_id=$1 and kind='fee'",[fee.id])).rows[0].completed_at===null;
 assert.equal(await open(),true);
 const p=await rpc('applicant','save_payment',[JSON.stringify({purpose:'Notice fee test',amount:'200',payment_method:'gcash',reference:'5012345678911'})],'$1::jsonb');
 assert.equal(await open(),true,'a saved draft is not a payment yet');
 await upload('applicant',12,p.id);
 const v=async()=>Number((await row('financial_records',p.id)).version);
 await command('applicant','submit_financial_request',{id:p.id,version:await v()});
 assert.equal(await open(),false,'sent for checking: the fee notice is done');
 await command('officer','verify_financial_request',{id:p.id,version:await v(),status:'returned_for_correction',remark:'Blurry receipt'});
 assert.equal(await open(),true,'sent back: the fee is still unpaid');
 await command('applicant','submit_financial_request',{id:p.id,version:await v()});
 assert.equal(await open(),false);
 await command('officer','verify_financial_request',{id:p.id,version:await v(),status:'verified',remark:'OK'});
 assert.equal(await open(),false,'verified stays done');
});

test('Interclub 20 Nov: partner reps join with a single-use invite code, not the DLSU application',async()=>{
 ids.uprep='00000000-0000-4000-8000-000000000097';
 await db.query('insert into auth.users values($1,$2,now())',[ids.uprep,'up.rep@example.test']);
 await as('uprep',()=>db.exec('select public.initialize_account()'));
 const e=await command('officer','save_event',{title:'Invite code fixture',description:'Synthetic',venue:'Range',starts_at:future(48),ends_at:future(52),registration_opens:future(-1),registration_closes:future(40),capacity:20});
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','share_interclub_event',{event_id:e.id,shared_details:'Shared',requirements:'School ID',registration_closes:future(30),rules:'Rules'});
 const p=await command('officer','save_partner',{name:'Invite Code University',official_channel:'icu@example.test'});
 const call=(person,name,data)=>rpc(person,name,[JSON.stringify(data)],'$1::jsonb');
 await denied(()=>call('applicant','create_partner_invite',{event_id:e.id,partner_id:p.id}),/interclub staff/);
 const inv=await call('officer','create_partner_invite',{event_id:e.id,partner_id:p.id});
 assert.match(inv.code,/^[0-9A-F]{4}-[0-9A-F]{4}$/);
 const details={full_name:'Uma Partner',university:'Invite Code University',phone:'0917 123 4567'};
 // A DLSU member or applicant can't become another club's representative (one person, one club).
 await denied(()=>call('applicant','claim_partner_invite',{...details,code:inv.code}),/member or applicant/);
 await denied(()=>call('uprep','claim_partner_invite',{...details,phone:'8123-4567',code:inv.code}),/Philippine mobile/);
 assert.equal((await call('uprep','claim_partner_invite',{...details,code:'0000-0000'})).ok,false);
 const ok=await call('uprep','claim_partner_invite',{...details,code:inv.code.toLowerCase().replace('-',' ')});
 assert.equal(ok.ok,true); assert.equal(ok.event_id,e.id);
 const prof=await row('profiles',ids.uprep);
 assert.equal(prof.account_type,'partner'); assert.equal(prof.university,'Invite Code University'); assert.equal(prof.full_name,'Uma Partner');
 // The code is now spent for anyone else, and the rep can add delegates for their own club only.
 ids.uprep2='00000000-0000-4000-8000-000000000096';
 await db.query('insert into auth.users values($1,$2,now())',[ids.uprep2,'second.rep@example.test']);
 await as('uprep2',()=>db.exec('select public.initialize_account()'));
 assert.equal((await call('uprep2','claim_partner_invite',{...details,code:inv.code})).ok,false);
 await command('uprep','save_delegate',{event_id:e.id,partner_id:p.id,full_name:'Ana Archer',affiliation:'ICU',category:'Recurve',requirements:'School ID'});
 // A cancelled code can't be claimed; a used one can't be cancelled.
 const second=await call('officer','create_partner_invite',{event_id:e.id,partner_id:p.id});
 await call('officer','revoke_partner_invite',{id:second.id});
 assert.equal((await call('uprep','claim_partner_invite',{...details,code:second.code})).ok,false);
 await denied(()=>call('officer','revoke_partner_invite',{id:inv.id}),/already used/);
 // Guessing is limited: 8 wrong codes in an hour locks claiming.
 for(let k=0;k<6;k++) await call('uprep','claim_partner_invite',{...details,code:'FFFF-000'+k});
 await denied(()=>call('uprep','claim_partner_invite',{...details,code:'FFFF-0009'}),/Too many/);
 // The officer sees the codes; the rep sees only their own.
 await as('uprep',async()=>assert.equal((await db.query('select * from app_private.partner_invites')).rows.length,1));
});
test('Interclub 21 Nov: a concern\'s reply reaches the rep, responsibility is optional, the invite keeps the rep\'s name',async()=>{
 ids.uprep3='00000000-0000-4000-8000-000000000095';
 await db.query('insert into auth.users values($1,$2,now())',[ids.uprep3,'third.rep@example.test']);
 await as('uprep3',()=>db.exec('select public.initialize_account()'));
 const e=await command('officer','save_event',{title:'Concern reply fixture',description:'Synthetic',venue:'Range',starts_at:future(48),ends_at:future(52),registration_opens:future(-1),registration_closes:future(40),capacity:20});
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','share_interclub_event',{event_id:e.id,shared_details:'Shared',requirements:'School ID',registration_closes:future(30),rules:'Rules'});
 const p=await command('officer','save_partner',{name:'Concern Reply College',official_channel:'crc@example.test'});
 const call=(person,name,data)=>rpc(person,name,[JSON.stringify(data)],'$1::jsonb');
 const inv=await call('officer','create_partner_invite',{event_id:e.id,partner_id:p.id});
 assert.equal((await call('uprep3','claim_partner_invite',{full_name:'Rita Rep',university:'Concern Reply College',phone:'09171234567',code:inv.code})).ok,true);
 // The officer can't read the rep's profile, so the invite keeps the name the rep gave.
 await as('officer',async()=>assert.equal((await db.query('select claimed_name from app_private.partner_invites where id=$1',[inv.id])).rows[0].claimed_name,'Rita Rep'));
 // An archer's target needs no "responsibility".
 const d=await command('uprep3','save_delegate',{event_id:e.id,partner_id:p.id,full_name:'Ben Bowman',affiliation:'CRC',category:'Recurve',requirements:'School ID'});
 await command('officer','review_delegate',{event_id:e.id,id:d.id,version:1,status:'eligible',remark:'Requirements checked'});
 const ev=(await db.query('select shared_start,shared_end from app_private.interclub_events where event_id=$1',[e.id])).rows[0];
 const a=await command('officer','assign_delegate',{event_id:e.id,delegate_id:d.id,starts_at:ev.shared_start.toISOString(),ends_at:ev.shared_end.toISOString(),target_group:'Target 3A'});
 assert.equal((await row('interclub_assignments',a.id)).responsibility,'');
 // The rep's concern, answered: the rep gets a notice with the reply and still sees the answered concern.
 const c=await command('uprep3','raise_concern',{event_id:e.id,concern:'Can Ben arrive at 8:30 for equipment check?'});
 await command('officer','resolve_concern',{event_id:e.id,id:c.id,resolution:'Yes, the range opens at 8:30.'});
 await as('uprep3',async()=>{
  const n=(await db.query("select message from app_private.notifications where user_id=$1 and kind='interclub'",[ids.uprep3])).rows;
  assert.equal(n.length,1); assert.match(n[0].message,/Concern reply fixture: Yes, the range opens at 8:30\./);
  const mine=(await db.query('select status,resolution from app_private.interclub_concerns where id=$1',[c.id])).rows[0];
  assert.equal(mine.status,'resolved'); assert.equal(mine.resolution,'Yes, the range opens at 8:30.');
 });
});
test('Interclub 21 Nov: each result keeps who recorded it, and that officer still cannot verify it',async()=>{
 const e=(await db.query("select event_id from app_private.interclub_events where shared_title='Concern reply fixture'")).rows[0].event_id;
 const d=(await db.query("select id,version from app_private.delegates where event_id=$1 and full_name='Ben Bowman'",[e])).rows[0];
 await command('officer','update_shared_event',{event_id:e,version:(await db.query('select version from app_private.interclub_events where event_id=$1',[e])).rows[0].version,status:'active',
  ...Object.fromEntries(Object.entries((await db.query('select shared_details,shared_venue,shared_start,shared_end from app_private.interclub_events where event_id=$1',[e])).rows[0]).map(([k,v])=>[k,v instanceof Date?v.toISOString():v]))});
 await command('officer','review_delegate',{event_id:e,id:d.id,version:d.version,status:'checked_in',remark:'Checked in at the range'});
 const r=await command('officer','record_result',{event_id:e,delegate_id:d.id,result:'612 / 720'});
 assert.equal((await row('interclub_results',r.id)).recorded_by,ids.officer);
 await denied(()=>command('officer','verify_result',{event_id:e,id:r.id}),/Another officer must verify/);
});
test('Developer accounts 21 Nov: the flagged developer account can hold roles without a membership; nobody else can',async()=>{
 ids.devacct='00000000-0000-4000-8000-000000000094';
 await db.query('insert into auth.users values($1,$2,now())',[ids.devacct,'dev.account@example.test']);
 await as('devacct',()=>db.exec('select public.initialize_account()'));
 const give=()=>command('admin','assign_role',{user_id:ids.devacct,role_id:'interclub_officer',position:'Interclub officer',ends_at:future(24*90),reason:'Testing'});
 await denied(give,/Active members/);
 await db.query('update app_private.profiles set developer=true where id=$1',[ids.devacct]);
 await give();
 // The role picker sees the account as "Developer", not "not a member".
 await as('admin',async()=>{
  const s=(await db.query('select app_private.member_standing() s')).rows[0].s;
  assert.ok(s.some(x=>x.user_id===ids.devacct&&x.status==='Developer'));
 });
});
test('Club funds 22 Nov: President and Treasurer get the same verified totals; the President still cannot read members\' payments',async()=>{
 await grant('officer','treasurer');await grant('successor','president');
 await db.query("insert into app_private.financial_records(user_id,kind,purpose,amount,reference,status,verified_at) values($1,'payment','Membership fee',800,'7000000000048',$2,now())",[ids.other,'verified']);
 const expected=(await db.query("select coalesce(sum(amount) filter (where kind='payment'),0)::float8 r,coalesce(sum(amount) filter (where kind in ('expense','reimbursement')),0)::float8 s,count(*) filter (where kind='payment')::int n from app_private.financial_records where status='verified'")).rows[0];
 assert.ok(expected.r>=800);
 const ws=name=>as(name,async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 const treasurer=await ws('officer'),president=await ws('successor'),member=await ws('other');
 for(const w of [treasurer,president]){
  assert.equal(Number(w.fund_totals.received),expected.r);assert.equal(Number(w.fund_totals.spent),expected.s);assert.equal(Number(w.fund_totals.payments),expected.n);
 }
 assert.equal(president.financial_records.filter(r=>r.kind==='payment'&&r.user_id!==ids.successor).length,0,'payment rows stay with the Treasurer');
 assert.ok(treasurer.financial_records.some(r=>r.kind==='payment'&&r.user_id===ids.other));
 assert.equal(member.fund_totals,null,'members get no club totals');
});
test('Other schools 23 Nov: an application records the school; DLSU keeps the 8-digit ID, other schools accept their own ID format',async()=>{
 ids.uste='00000000-0000-4000-8000-0000000000e1';
 await db.query('insert into auth.users values($1,$2,now())',[ids.uste,'uste@example.test']);
 await as('uste',()=>db.exec('select public.initialize_account()'));
 const base={full_name:'Tomas Santos',phone:'09171234567',sex:'Male',college:'College of Science',program:'BS Biology',year_level:'1st year',experience:''};
 const save=(d,action='save_application')=>rpc('uste','save_application_details',[action,JSON.stringify(d)],'$1,$2::jsonb');
 // A UST-style ID is refused for DLSU (the default school) ...
 await denied(()=>save({...base,student_number:'2021-12345'}),/DLSU ID number is 8 digits/);
 // ... and accepted once the school is UST.
 const a=await save({...base,school:'University of Santo Tomas',student_number:'2021-12345'});
 assert.equal(a.school,'University of Santo Tomas'); assert.equal(a.student_number,'2021-12345');
 await denied(()=>save({...base,version:a.version,school:'University of Santo Tomas',student_number:'12'}),/4 to 20 letters/);
 await denied(()=>save({...base,version:a.version,confirmed:true,school:'University of Santo Tomas',student_number:''},'submit_application'),/student ID number/);
 // "dlsu" typed by hand still means DLSU and its ID rule.
 const b=await save({...base,version:a.version,school:'dlsu',student_number:'12345678'});
 assert.equal(b.school,'De La Salle University');
 // Existing DLSU applications default to DLSU.
 await db.exec('reset role');
 assert.equal((await db.query('select school from app_private.applications where user_id=$1',[ids.newbie])).rows[0].school,'De La Salle University');
});
test('Clubs 23 Nov: a second club is created by the platform administrator and kept completely apart from DLSU',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 ids.upadmin='00000000-0000-4000-8000-00000000cc01'; ids.upnew='00000000-0000-4000-8000-00000000cc02';
 for(const n of ['upadmin','upnew'])await db.query('insert into auth.users values($1,$2,now())',[ids[n],n+'@up.example.test']);
 await as('upadmin',()=>db.exec('select public.initialize_account()'));
 // Only the platform administrator creates clubs.
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'UP Archery Club',admin_email:'upadmin@up.example.test'})],'$1::jsonb'),/platform administrator/);
 await db.query('update app_private.profiles set platform_admin=true where id=$1',[ids.admin]);
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'UP Archery Club',email_domain:'example.test',admin_email:'applicant@example.test'})],'$1::jsonb'),/already belongs to a club/);
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'UP Archery Club',email_domain:'example.test',admin_email:'nobody@example.test'})],'$1::jsonb'),/No account uses/);
 const up=(await rpc('admin','create_club',[JSON.stringify({name:'UP Archery Club',short_name:'UPAC',school:'University of the Philippines',email_domain:'up.example.test',admin_email:'upadmin@up.example.test'})],'$1::jsonb')).id;
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'up archery club',admin_email:'upnew@up.example.test'})],'$1::jsonb'),/already exists/);
 const ws=name=>as(name,async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 // The UP administrator sees UP's blank settings and none of DLSU's people, money or records.
 const u=await ws('upadmin');
 assert.equal(u.club.id,up); assert.equal(u.settings.length,1); assert.equal(u.settings[0].club_name,'UP Archery Club');
 assert.deepEqual(u.settings[0].application_requirements,[]);
 assert.ok(u.permissions.includes('administration'));
 assert.ok(u.permissions.includes('membership'),'first administrator can accept members');
 for(const t of ['memberships','applications','events','financial_records','equipment','role_assignments','audit_log','partner_clubs'])
  assert.ok(u[t].every(r=>r.club_id===up),t+' leaked another club');
 assert.deepEqual(u.profiles.map(p=>p.id),[ids.upadmin],'only UP people');
 assert.ok(u.member_directory.every(m=>m.user_id===ids.upadmin||!m.user_id)||u.member_directory.length===0,'directory leaked');
 assert.equal(Number(u.fund_totals?.received??0),0);
 // UP sets its own rules; DLSU's stay as they were.
 const dlsuBefore=(await db.query('select application_requirements from app_private.settings where club_id=$1',[dlsu])).rows[0];
 await command('upadmin','configure_club',{club_name:'UP Archery Club',application_requirements:['UP Form 5'],renewal_requirements:[],membership_categories:['Regular member'],retention_policy:'UP policy',rules_validated:true});
 assert.deepEqual((await db.query('select application_requirements from app_private.settings where club_id=$1',[up])).rows[0].application_requirements,['UP Form 5']);
 assert.deepEqual((await db.query('select application_requirements from app_private.settings where club_id=$1',[dlsu])).rows[0],dlsuBefore);
 // UP's activity is UP's: DLSU people don't see it, and UP doesn't see DLSU's.
 await db.query("insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason,club_id) values($1,'activity_officer','Activity officer',now()+interval '1 year','UP fixture',$2)",[ids.upadmin,up]);
 const ev=await command('upadmin','save_event',{title:'UP Open Shoot',description:'UP only',venue:'UP Range',starts_at:new Date(Date.now()+48*3600e3).toISOString(),ends_at:new Date(Date.now()+50*3600e3).toISOString(),registration_opens:new Date(Date.now()-3600e3).toISOString(),registration_closes:new Date(Date.now()+24*3600e3).toISOString(),capacity:10});
 assert.equal((await db.query('select club_id from app_private.events where id=$1',[ev.id])).rows[0].club_id,up);
 const d=await ws('admin');
 assert.ok(!d.events.some(e=>e.id===ev.id),'DLSU sees UP event');
 const dOfficer=await ws('officer'); assert.ok(!dOfficer.events.some(e=>e.id===ev.id),'DLSU officer sees UP event');
 assert.ok(dOfficer.events.every(e=>e.club_id===dlsu));
 assert.ok(d.clubs.length===2 && d.club.id===dlsu);
 // A DLSU officer can't reach UP's event by its id either.
 await assert.rejects(()=>command('officer','save_event',{id:ev.id,version:1,title:'Hijack',description:'x',venue:'x',starts_at:new Date(Date.now()+48*3600e3).toISOString(),ends_at:new Date(Date.now()+50*3600e3).toISOString(),registration_opens:new Date(Date.now()-3600e3).toISOString(),registration_closes:new Date(Date.now()+24*3600e3).toISOString(),capacity:10}));
 assert.equal((await db.query('select title from app_private.events where id=$1',[ev.id])).rows[0].title,'UP Open Shoot');
 // A new @up account lands in UP by its email domain (migration 051); a DLSU member can't switch.
 await as('upnew',()=>db.exec('select public.initialize_account()'));
 assert.equal((await ws('upnew')).club.id,up);
 assert.equal((await ws('upnew')).settings.length,1);
 await denied(()=>rpc('applicant','join_club',[JSON.stringify({club_id:up})],'$1::jsonb'),/already belongs/);
 // Interclub across clubs: UP's administrator joins a DLSU event with a code, stays in UP, and adds an archer that
 // DLSU sees; UP still sees no other DLSU event.
 const e2=await command('officer','save_event',{title:'DLSU Open (cross-club)',description:'Shared',venue:'Range',starts_at:future(60),ends_at:future(64),registration_opens:future(-1),registration_closes:future(50),capacity:30});
 await command('officer','publish_event',{id:e2.id,version:1});
 await command('officer','share_interclub_event',{event_id:e2.id,shared_details:'Shared',requirements:'School ID',registration_closes:future(40),rules:'Rules'});
 const pUp=await command('officer','save_partner',{name:'UP Archery Club (cross-club)',official_channel:'up@example.test'});
 const inv2=await rpc('officer','create_partner_invite',[JSON.stringify({event_id:e2.id,partner_id:pUp.id})],'$1::jsonb');
 const claimed=await rpc('upadmin','claim_partner_invite',[JSON.stringify({code:inv2.code})],'$1::jsonb');
 assert.equal(claimed.ok,true);
 const upProf=(await db.query('select club_id,account_type from app_private.profiles where id=$1',[ids.upadmin])).rows[0];
 assert.equal(upProf.club_id,up,'UP admin stayed in UP'); assert.notEqual(upProf.account_type,'partner');
 const u2=await ws('upadmin');
 assert.ok(u2.events.some(e=>e.id===e2.id),'guest sees the shared event');
 assert.ok(!u2.events.some(e=>e.club_id===dlsu&&e.id!==e2.id),'guest sees only the event invited to');
 assert.ok(u2.interclub_events.some(i=>i.event_id===e2.id));
 assert.ok(u2.permissions.includes('administration'),'still UP administrator');
 const del=await command('upadmin','save_delegate',{event_id:e2.id,partner_id:pUp.id,full_name:'UP Archer',affiliation:'UP',category:'Recurve',requirements:'Confirmed'});
 assert.equal((await db.query('select club_id from app_private.delegates where id=$1',[del.id])).rows[0].club_id,dlsu,'event rows stay with the host');
 assert.ok((await ws('officer')).delegates.some(d=>d.id===del.id),'host sees the guest archer');
 // A UP account without an invite can't add archers to a DLSU event.
 await assert.rejects(()=>command('upnew','save_delegate',{event_id:e2.id,partner_id:pUp.id,full_name:'Sneak',affiliation:'UP',category:'Recurve',requirements:'x'}));
 // Each club's fee rules: UP sets its own amount and days to pay; DLSU's stay.
 await db.query("insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason,club_id) values($1,'treasurer','Treasurer',now()+interval '1 year','UP fixture',$2)",[ids.upadmin,up]);
 const dlsuFee=(await db.query('select membership_fee,fee_due_days from app_private.settings where club_id=$1',[dlsu])).rows[0];
 await command('upadmin','set_membership_fee',{amount:500,due_days:30});
 assert.deepEqual((await db.query('select membership_fee::float8 f,fee_due_days d from app_private.settings where club_id=$1',[up])).rows[0],{f:500,d:30});
 assert.deepEqual((await db.query('select membership_fee,fee_due_days from app_private.settings where club_id=$1',[dlsu])).rows[0],dlsuFee);
 await denied(()=>command('upadmin','set_membership_fee',{amount:500,due_days:0}),/1 to 120/);
 // The shared role catalogue can't be changed by one club's administrator once there are several clubs.
 await denied(()=>command('upadmin','define_role',{role_id:'treasurer',name:'Bursar',permissions:['finance']}),/platform administrator|shared/);
 await db.query('update app_private.profiles set platform_admin=false where id=$1',[ids.admin]);
});
test('Club domains 23 Nov: the email domain decides the club, and an account can never cross into another school\'s club',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 await db.query('update app_private.profiles set platform_admin=true where id=$1',[ids.admin]);
 await rpc('admin','set_club_domain',[JSON.stringify({club_id:up,email_domain:'@UP.example.test'})],'$1::jsonb');
 await rpc('admin','set_club_domain',[JSON.stringify({club_id:dlsu,email_domain:'example.test'})],'$1::jsonb');
 assert.equal((await db.query('select email_domain from app_private.clubs where id=$1',[up])).rows[0].email_domain,'up.example.test');
 await denied(()=>rpc('upadmin','set_club_domain',[JSON.stringify({club_id:up,email_domain:'x.test'})],'$1::jsonb'),/platform administrator/);
 const make=async(name,id,email)=>{ids[name]=id;await db.query('insert into auth.users values($1,$2,now())',[id,email]);await as(name,()=>db.exec('select public.initialize_account()'));
  return (await db.query('select club_id from app_private.profiles where id=$1',[id])).rows[0].club_id;};
 // New accounts land in their domain's club; a sub-domain counts; the longest domain wins (up.example.test over example.test).
 assert.equal(await make('upstudent','00000000-0000-4000-8000-0000000000d1','juan@up.example.test'),up);
 assert.equal(await make('dlsustudent','00000000-0000-4000-8000-0000000000d2','ana@example.test'),dlsu);
 assert.equal(await make('dlsusub','00000000-0000-4000-8000-0000000000d3','ben@student.example.test'),dlsu);
 assert.equal(await make('outsider','00000000-0000-4000-8000-0000000000d4','x@gmail.test'),null);
 // No crossing: joining, being moved, or an outsider joining a club with a domain.
 await denied(()=>rpc('upstudent','join_club',[JSON.stringify({club_id:dlsu})],'$1::jsonb'),/can.t join|only for its own school|already belongs/);
 await denied(()=>rpc('outsider','join_club',[JSON.stringify({club_id:up})],'$1::jsonb'),/can.t join|only for its own school/);
 await assert.rejects(()=>db.query('update app_private.profiles set club_id=$1 where id=$2',[dlsu,ids.upstudent]),/only for its own school/);
 // Creating a club: the domain is required and the first administrator's email must be on it.
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'Ateneo Archery Club',admin_email:'ana@example.test'})],'$1::jsonb'),/email domain/);
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'Ateneo Archery Club',email_domain:'ateneo.example.test',admin_email:'ana@example.test'})],'$1::jsonb'),/needs an @ateneo/);
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'Ateneo Archery Club',email_domain:'up.example.test',admin_email:'juan@up.example.test'})],'$1::jsonb'),/already uses/);
 ids.ateneoadmin='00000000-0000-4000-8000-0000000000d5';
 await db.query('insert into auth.users values($1,$2,now())',[ids.ateneoadmin,'karl@ateneo.example.test']);
 await as('ateneoadmin',()=>db.exec('select public.initialize_account()'));
 const at=(await rpc('admin','create_club',[JSON.stringify({name:'Ateneo Archery Club',school:'Ateneo',email_domain:'ateneo.example.test',admin_email:'karl@ateneo.example.test',theme:'blue'})],'$1::jsonb')).id;
 const ws=await as('ateneoadmin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(ws.club.id,at); assert.equal(ws.club.theme,'blue'); assert.equal(ws.club.email_domain,'ateneo.example.test');
 // A partner-only guest (account_type partner) still works as the host club's guest whatever the domain.
 await db.query("update app_private.profiles set account_type='partner', club_id=$1 where id=$2",[dlsu,ids.outsider]);
 // Put the fixtures back for any later test.
 await db.query('update app_private.clubs set email_domain=null');
 await db.query('update app_private.profiles set platform_admin=false where id=$1',[ids.admin]);
});
test('Club requests 23 Nov: a student whose school has no club asks for one; the platform administrator approves or declines',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 await db.query("update app_private.clubs set email_domain='example.test' where id=$1",[dlsu]);
 await db.query('update app_private.profiles set platform_admin=true where id=$1',[ids.admin]);
 const make=async(name,id,email)=>{ids[name]=id;await db.query('insert into auth.users values($1,$2,now())',[id,email]);await as(name,()=>db.exec('select public.initialize_account()'));};
 await make('feu','00000000-0000-4000-8000-0000000000f1','maria@feu.test');
 await make('feu2','00000000-0000-4000-8000-0000000000f2','jose@feu.test');
 await make('gmailer','00000000-0000-4000-8000-0000000000f3','someone@gmail.com');
 await make('mapua','00000000-0000-4000-8000-0000000000f4','lea@mapua.test');
 const req=(n,d)=>rpc(n,'request_club',[JSON.stringify(d)],'$1::jsonb');
 const decide=(n,d)=>rpc(n,'decide_club_request',[JSON.stringify(d)],'$1::jsonb');
 // Only for your own school's domain, never a personal email, never from inside a club.
 // Since migration 059 a personal email asks for a community club instead of being refused.
 assert.equal((await req('gmailer',{name:'Gmail Archers',school:'None'})).community,true);
 await denied(()=>req('applicant',{name:'Another DLSU club',school:'DLSU'}),/already belongs|already has a club/);
 const r=await req('feu',{name:'FEU Archery Club',short_name:'FEU',school:'Far Eastern University',theme:'green'});
 await denied(()=>req('feu2',{name:'FEU Archers 2',school:'FEU'}),/already waiting/);
 // The platform administrator is told and sees it; the requester sees their own; other students don't.
 const aws=await as('admin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.ok(aws.club_requests.some(q=>q.id===r.id)); assert.ok(aws.notifications.some(n=>n.record_id===r.id&&n.kind==='club_request'));
 const own=await as('feu',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(own.club_requests.length,1); assert.equal(own.club_requests[0].email_domain,'feu.test');
 assert.equal((await as('mapua',async()=>(await db.query('select public.club_workspace() w')).rows[0].w)).club_requests.length,0);
 // Only the platform administrator decides; a decline needs a reason.
 await denied(()=>decide('upadmin',{id:r.id,decision:'approve'}),/platform administrator/);
 const m=await req('mapua',{name:'Mapua Archery',school:'Mapua University'});
 await denied(()=>decide('admin',{id:m.id,decision:'decline'}),/Say why/);
 await decide('admin',{id:m.id,decision:'decline',remark:'Ask your school org office to endorse it first.'});
 const mw=await as('mapua',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(mw.club_requests[0].status,'declined'); assert.ok(mw.notifications.some(n=>/endorse/.test(n.message)));
 // Approving creates the club with the requester as its administrator; their next load is the new club.
 const out=await decide('admin',{id:r.id,decision:'approve'});
 const fw=await as('feu',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(fw.club.id,out.club_id); assert.equal(fw.club.email_domain,'feu.test'); assert.ok(fw.permissions.includes('administration'));
 assert.ok(fw.notifications.some(n=>/approved/.test(n.message)));
 await denied(()=>decide('admin',{id:r.id,decision:'approve'}),/already decided/);
 // A second FEU student now lands in FEU's club automatically.
 await make('feu3','00000000-0000-4000-8000-0000000000f5','ana@feu.test');
 assert.equal((await db.query('select club_id from app_private.profiles where id=$1',[ids.feu3])).rows[0].club_id,out.club_id);
 await db.query('update app_private.clubs set email_domain=null');
 await db.query('update app_private.profiles set platform_admin=false where id=$1',[ids.admin]);
});
test('Join codes 23 Nov: an alumnus with a personal email joins a club only with that club\'s single-use code',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 await db.query("update app_private.clubs set email_domain='example.test' where id=$1",[dlsu]);
 await db.query("update app_private.clubs set email_domain='up.example.test' where id=$1",[up]);
 const make=(d)=>rpc('admin','create_join_code',[JSON.stringify(d)],'$1::jsonb');
 // Only Membership or Administration makes codes, and only for emails no club already owns.
 await denied(()=>rpc('other','create_join_code',[JSON.stringify({email:'alum@gmail.test',full_name:'Ana Alumna',reason:'Alumni'})],'$1::jsonb'),/Membership officer or an Administrator/);
 await denied(()=>make({email:'new@example.test',full_name:'X Y',reason:'Alumni'}),/don.t need a code/);
 await denied(()=>make({email:'juan@up.example.test',full_name:'X Y',reason:'Alumni'}),/belongs to UP Archery Club/);
 await denied(()=>make({email:'admin@example.test',full_name:'X Y',reason:'Alumni'}),/don.t need a code|already exists/);
 const c=await make({email:'Alum@Gmail.test',full_name:'Ana Alumna',reason:'Alumni, batch 2019'});
 assert.match(c.code,/^[0-9A-F]{4}-[0-9A-F]{4}$/);
 // Before sign-up: the code checks out for that email only; an interclub invite code is told apart.
 const check=async(code,email)=>(await db.query('select public.check_signup_code($1,$2) r',[code,email])).rows[0].r;
 const dlsuName=(await db.query('select name from app_private.clubs where id=$1',[dlsu])).rows[0].name;
 assert.deepEqual(await check(c.code.toLowerCase(),'alum@gmail.test'),{kind:'join',club:dlsuName});
 assert.equal((await check(c.code,'someone@gmail.test')).kind,null);
 assert.equal((await check('0000-0000','alum@gmail.test')).kind,null);
 // Sign up with the personal email: no club until the code is claimed, then DLSU; the guard accepts it.
 ids.alum='00000000-0000-4000-8000-0000000000a9';
 await db.query('insert into auth.users values($1,$2,now())',[ids.alum,'alum@gmail.test']);
 await as('alum',()=>db.exec('select public.initialize_account()'));
 assert.equal((await db.query('select club_id from app_private.profiles where id=$1',[ids.alum])).rows[0].club_id,null);
 await rpc('alum','claim_join_code',[JSON.stringify({code:c.code})],'$1::jsonb');
 const p=(await db.query('select club_id,full_name from app_private.profiles where id=$1',[ids.alum])).rows[0];
 assert.equal(p.club_id,dlsu); assert.equal(p.full_name,'Ana Alumna');
 const w=await as('alum',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(w.club.id,dlsu);
 // Single use, own email only, and still no route into another club.
 ids.alum2='00000000-0000-4000-8000-0000000000ab';
 await db.query('insert into auth.users values($1,$2,now())',[ids.alum2,'other@gmail.test']);
 await as('alum2',()=>db.exec('select public.initialize_account()'));
 await denied(()=>rpc('alum2','claim_join_code',[JSON.stringify({code:c.code})],'$1::jsonb'),/isn.t valid/);
 await assert.rejects(()=>db.query('update app_private.profiles set club_id=$1 where id=$2',[up,ids.alum]),/only for its own school/);
 // A cancelled code can't be used; officers see their club's codes.
 const c2=await make({email:'coach@gmail.test',full_name:'Coach Cruz',reason:'Coach'});
 await rpc('admin','revoke_join_code',[JSON.stringify({id:c2.id})],'$1::jsonb');
 assert.equal((await check(c2.code,'coach@gmail.test')).kind,null);
 const aw=await as('admin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.ok(aw.join_codes.some(j=>j.id===c.id&&j.used_by===ids.alum));
 await db.query('update app_private.clubs set email_domain=null');
});

test('M14 a club turns features off for its members and officers, and back on',async()=>{
 await grant('officer','equipment_officer');
 const eq=await command('officer','save_equipment',{label:'Recurve 77',category:'Bow',specifications:'Training',handedness:'right',draw_weight:24,bow_setup:'recurve',condition:'serviceable'});
 const req={equipment_id:eq.id,starts_at:future(300),ends_at:future(302),handedness:'right',maximum_draw_weight:28,bow_setup:'recurve',purpose:'Practice'};
 const can=async(person,p)=>as(person,async()=>(await db.query('select app_private.can($1) c',[p])).rows[0].c);
 assert.equal(await can('officer','equipment'),true);
 // Only an administrator; core features can't be switched off.
 await denied(()=>rpc('applicant','set_club_features',['{equipment}'],'$1::text[]'),/Permission/);
 await denied(()=>rpc('admin','set_club_features',['{membership}'],'$1::text[]'),/can.t be turned off/);
 await rpc('admin','set_club_features',['{equipment}'],'$1::text[]');
 // Off: the member can't borrow, the officer's desk answers no, and the app sees the switch.
 await denied(()=>command('applicant','request_borrowing',req),/turned off equipment borrowing/);
 await denied(()=>command('officer','save_equipment',{label:'Recurve 78',category:'Bow',specifications:'x',handedness:'right',draw_weight:24,bow_setup:'recurve',condition:'serviceable'}),/turned off/);
 assert.equal(await can('officer','equipment'),false);
 const w=await as('admin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.deepEqual(w.settings[0].disabled_features,['equipment']);
 // Other features keep working, and turning it back on restores everything.
 assert.equal(await can('admin','administration'),true);
 await rpc('admin','set_club_features',['{}'],'$1::text[]');
 assert.equal(await can('officer','equipment'),true);
 const b=await command('applicant','request_borrowing',req);
 assert.ok(b.id);
});

test('Club join code 23 Nov: one shared code per club lets any personal email in, never another school',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 await db.query("update app_private.clubs set email_domain='example.test' where id=$1",[dlsu]);
 await db.query("update app_private.clubs set email_domain='up.example.test' where id=$1",[up]);
 const codeOf=async club=>(await db.query('select join_code from app_private.settings where club_id=$1',[club])).rows[0].join_code;
 const code=await codeOf(dlsu);
 assert.match(code,/^[0-9A-F]{4}-[0-9A-F]{4}$/); assert.notEqual(code,await codeOf(up));
 const check=async(c,email)=>(await db.query('select public.check_signup_code($1,$2) r',[c,email])).rows[0].r;
 const dlsuName=(await db.query('select name from app_private.clubs where id=$1',[dlsu])).rows[0].name;
 assert.deepEqual(await check(code.toLowerCase().replace('-',''),'anyone@gmail.test'),{kind:'join',club:dlsuName});
 assert.equal((await check(code,'juan@up.example.test')).kind,null,'another school stays out');
 // Two strangers sign up with the same code; officers see both; the domain guard accepts them.
 for(const [k,id,email] of [['cc1','00000000-0000-4000-8000-0000000c0de1','first@gmail.test'],['cc2','00000000-0000-4000-8000-0000000c0de2','second@yahoo.test']]){
  ids[k]=id; await db.query('insert into auth.users values($1,$2,now())',[id,email]);
  await as(k,()=>db.exec('select public.initialize_account()'));
  await rpc(k,'claim_join_code',[JSON.stringify({code})],'$1::jsonb');
  assert.equal((await db.query('select club_id from app_private.profiles where id=$1',[id])).rows[0].club_id,dlsu);
 }
 const aw=await as('admin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(aw.join_codes.filter(j=>j.reason==='Signed up with the club code').length,2);
 assert.equal(aw.settings[0].join_code,code);
 // A UP student can't claim DLSU's code; a member can't change it; an officer can, and the old one stops working.
 ids.ccup='00000000-0000-4000-8000-0000000c0de3';
 await db.query('insert into auth.users values($1,$2,now())',[ids.ccup,'pedro@up.example.test']);
 await db.query('insert into app_private.profiles(id,email) values($1,$2) on conflict do nothing',[ids.ccup,'pedro@up.example.test']);
 await db.query('update app_private.profiles set club_id=null where id=$1',[ids.ccup]);
 await denied(()=>rpc('ccup','claim_join_code',[JSON.stringify({code})],'$1::jsonb'),/belongs to UP|already belongs/);
 await denied(()=>rpc('cc1','reset_club_join_code',[],''),/Membership officer or an Administrator/);
 const fresh=(await rpc('admin','reset_club_join_code',[],'')).code;
 assert.notEqual(fresh,code); assert.equal(await codeOf(dlsu),fresh);
 assert.equal((await check(code,'late@gmail.test')).kind,null);
 await db.query('update app_private.clubs set email_domain=null');
});

test('Interclub 23 Nov: the host sends an event to clubs in the app; a guest club accepts or declines',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 const e=await command('officer','save_event',{title:'Send to clubs fixture',description:'Synthetic',venue:'Range',starts_at:future(48),ends_at:future(52),registration_opens:future(-1),registration_closes:future(40),capacity:20});
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','share_interclub_event',{event_id:e.id,shared_details:'Shared',requirements:'School ID',registration_closes:future(30),rules:'Rules'});
 const send=(person,clubs)=>rpc(person,'send_interclub_invitations',[e.id,`{${clubs.join(',')}}`],'$1::uuid,$2::uuid[]');
 await denied(()=>send('applicant',[up]),/interclub officer/);
 await denied(()=>send('officer',[]),/at least one/);
 assert.equal((await send('officer',[up,dlsu])).sent,1,'only the other club, never itself');
 assert.equal((await send('officer',[up])).sent,0,'already waiting');
 // UP sees it and hears about it; DLSU sees what it sent.
 const upw=await as('upadmin',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 const inv=upw.interclub_invitations.find(x=>x.event_id===e.id);
 assert.equal(inv.status,'pending'); assert.equal(inv.event_title,'Send to clubs fixture');
 assert.ok(upw.notifications.some(n=>n.kind==='interclub_invitation'&&n.record_id===inv.id));
 const dw=await as('officer',async()=>(await db.query('select public.club_workspace() w')).rows[0].w);
 assert.equal(dw.interclub_invitations.filter(x=>x.event_id===e.id).length,1);
 // A UP member without the desk can't answer; a decline needs a reason; accepting makes the UP admin the rep.
 const respond=(person,accept,note='')=>rpc(person,'respond_interclub_invitation',[inv.id,accept,note],'$1::uuid,$2::boolean,$3::text');
 await denied(()=>respond('officer',true),/not found/);
 await denied(()=>respond('upadmin',false,''),/why your club is declining/);
 await respond('upadmin',true);
 const access=(await db.query('select * from app_private.event_access where event_id=$1 and user_id=$2',[e.id,ids.upadmin])).rows[0];
 assert.equal(access.role,'partner'); assert.equal(access.club_id,dlsu);
 await denied(()=>respond('upadmin',true),/already answered/);
 // The UP rep adds a delegate for UP, but can't use DLSU's host controls even though they're UP's administrator.
 await command('upadmin','save_delegate',{event_id:e.id,partner_id:inv.partner_id,full_name:'Uno Archer',affiliation:'UP',category:'Recurve',requirements:'School ID'});
 await as('upadmin',async()=>assert.equal(await (async()=>{const r=await db.query('select app_private.event_staff($1) s',[e.id]);return r.rows[0].s;})(),false));
 await denied(()=>command('upadmin','update_shared_event',{event_id:e.id,status:'active',shared_details:'x',shared_venue:'x',shared_start:future(48),shared_end:future(52)}),/host club/);
 // The host still runs its own event, and heard back.
 await as('officer',async()=>assert.equal((await db.query('select app_private.event_staff($1) s',[e.id])).rows[0].s,true));
 const sender=await as('officer',async()=>(await db.query("select message from app_private.notifications where user_id=$1 and kind='interclub' and message like '%Send to clubs fixture%' limit 1",[ids.officer])).rows[0]);
 assert.match(sender.message,/UP Archery Club accepted Send to clubs fixture/);
});

test('Interclub 23 Nov: the guest club posts an accepted event to its members, they sign up, the rep confirms them as delegates',async()=>{
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 const e=await command('officer','save_event',{title:'Member sign-up fixture',description:'Synthetic',venue:'Range B',starts_at:future(48),ends_at:future(52),registration_opens:future(-1),registration_closes:future(40),capacity:20});
 await command('officer','publish_event',{id:e.id,version:1});
 await command('officer','share_interclub_event',{event_id:e.id,shared_details:'Shared',requirements:'School ID',registration_closes:future(30),rules:'Rules'});
 await rpc('officer','send_interclub_invitations',[e.id,`{${up}}`],'$1::uuid,$2::uuid[]');
 const inv=(await db.query('select * from app_private.interclub_invitations where event_id=$1',[e.id])).rows[0];
 // Two UP members (upnew, plus a second) and a UP account that isn't a member.
 ids.upm2='00000000-0000-4000-8000-0000000c0de7'; ids.upguest='00000000-0000-4000-8000-0000000c0de8';
 await db.query('insert into auth.users values($1,$2,now()),($3,$4,now())',[ids.upm2,'upm2@up.example.test',ids.upguest,'upguest@up.example.test']);
 for(const n of ['upm2','upguest']) await as(n,()=>db.exec('select public.initialize_account()'));
 await db.query('update app_private.profiles set club_id=$2 where id=$1',[ids.upguest,up]);
 for(const n of ['upnew','upm2']){
  await db.query("update app_private.profiles set club_id=$2, full_name=$3 where id=$1",[ids[n],up,n==='upnew'?'Nina Uno':'Mara Dos']);
  await db.query("insert into app_private.memberships(user_id,category,status,student_number,approved_by,club_id) values($1,'Regular member','Active','',$2,$3) on conflict(user_id) do update set status='Active'",[ids[n],ids.upadmin,up]);
  await db.query("update app_private.memberships set status='Active' where user_id=$1",[ids[n]]);
 }
 const signup=(p,cat='Recurve')=>rpc(p,'interclub_signup',[inv.id,cat,''],'$1::uuid,$2::text,$3::text');
 // Not open to members until the club accepts and posts it.
 await denied(()=>signup('upnew'),/isn.t open to your club/);
 await denied(()=>rpc('upnew','publish_interclub_invitation',[inv.id,'',null],'$1::uuid,$2::text,$3::int'),/Permission denied/);
 await rpc('upadmin','respond_interclub_invitation',[inv.id,true,''],'$1::uuid,$2::boolean,$3::text');
 await denied(()=>signup('upnew'),/isn.t open to your club/);
 const pub=await rpc('upadmin','publish_interclub_invitation',[inv.id,'Bring your own arrows',1],'$1::uuid,$2::text,$3::int');
 assert.equal(pub.notified,2);
 // Members sign up; a non-member and a DLSU member can't; a member sees only their own sign-up, the rep sees all.
 await denied(()=>signup('upguest'),/Active members/);
 await denied(()=>signup('applicant'),/isn.t open to your club/);
 const s1=await signup('upnew'); await signup('upm2','Compound');
 await denied(()=>signup('upnew'),/already signed up/);
 await as('upnew',async()=>assert.equal((await db.query('select * from app_private.interclub_signups')).rows.length,1));
 await as('upadmin',async()=>assert.equal((await db.query('select * from app_private.interclub_signups')).rows.length,2));
 // The rep confirms one (one slot), the second can't fit; declining needs a reason.
 const decide=(p,id,ok,why='')=>rpc(p,'decide_interclub_signup',[id,ok,why],'$1::uuid,$2::boolean,$3::text');
 await denied(()=>decide('upnew',s1.id,true),/Permission denied/);
 const c=await decide('upadmin',s1.id,true);
 const d=await row('delegates',c.delegate_id);
 assert.equal(d.full_name,'Nina Uno'); assert.equal(d.member_id,ids.upnew); assert.equal(d.partner_id,inv.partner_id); assert.equal(d.category,'Recurve');
 const s2=(await db.query('select id from app_private.interclub_signups where user_id=$1',[ids.upm2])).rows[0];
 await denied(()=>decide('upadmin',s2.id,true),/slots are taken/);
 await denied(()=>decide('upadmin',s2.id,false,''),/short reason/);
 await decide('upadmin',s2.id,false,'One slot only this time');
 // The host reviews the delegate as usual; the member follows it in their own workspace.
 await command('officer','review_delegate',{event_id:e.id,id:d.id,version:1,status:'eligible',remark:'Checked'});
 const w=await rpc('upnew','club_workspace',[],'');
 const mine=w.my_interclub.find(x=>x.event_id===e.id);
 assert.equal(mine.status,'confirmed'); assert.equal(mine.delegate_status,'eligible');
 await denied(()=>rpc('upnew','withdraw_interclub_signup',[inv.id],'$1::uuid'),/Ask your club/);
});

test('Community clubs 23 Nov: a gmail account requests its own club; people join it with its code only',async()=>{
 const dlsu='00000000-0000-4000-8000-00000000d150';
 const up=(await db.query("select id from app_private.clubs where name='UP Archery Club'")).rows[0].id;
 await db.query("update app_private.clubs set email_domain='example.test' where id=$1",[dlsu]);
 await db.query("update app_private.clubs set email_domain='up.example.test' where id=$1",[up]);
 await db.query('update app_private.profiles set platform_admin=true where id=$1',[ids.admin]);
 const mk=async(n,id,email)=>{ids[n]=id;await db.query('insert into auth.users values($1,$2,now())',[id,email]);await as(n,()=>db.exec('select public.initialize_account()'));};
 await mk('founder','00000000-0000-4000-8000-0000000c0e01','founder@gmail.test');
 await mk('friend','00000000-0000-4000-8000-0000000c0e02','friend@gmail.test');
 await mk('upkid','00000000-0000-4000-8000-0000000c0e03','kid@up.example.test');
 // The test domains aren't on the personal list, so mark the founder's as personal the way the app sees gmail.
 await db.query("update auth.users set email='founder@gmail.com' where id=$1",[ids.founder]);
 await db.query("update auth.users set email='friend@gmail.com' where id=$1",[ids.friend]);
 const req=await rpc('founder','request_club',[JSON.stringify({name:'Weekend Archers',short_name:'WKND',theme:'blue',note:'Alumni who shoot on Saturdays'})],'$1::jsonb');
 assert.equal(req.community,true);
 await denied(()=>rpc('founder','request_club',[JSON.stringify({name:'Another'})],'$1::jsonb'),/already waiting/);
 const r=(await db.query('select * from app_private.club_requests where id=$1',[req.id])).rows[0];
 assert.equal(r.email_domain,null); assert.equal(r.school,'Community club');
 const ok=await rpc('admin','decide_club_request',[JSON.stringify({id:req.id,decision:'approve'})],'$1::jsonb');
 const club=(await db.query('select * from app_private.clubs where id=$1',[ok.club_id])).rows[0];
 assert.equal(club.email_domain,null);
 assert.equal((await db.query('select club_id from app_private.profiles where id=$1',[ids.founder])).rows[0].club_id,ok.club_id);
 const perms=(await rpc('founder','club_workspace',[],'')).permissions;
 assert.ok(perms.includes('administration')&&perms.includes('membership'));
 // Nobody lands in it by email or picks it from a list: only its code works, and not for a school email.
 await denied(()=>rpc('friend','join_club',[JSON.stringify({club_id:ok.club_id})],'$1::jsonb'),/join code/);
 const code=(await db.query('select join_code from app_private.settings where club_id=$1',[ok.club_id])).rows[0].join_code;
 await rpc('friend','claim_join_code',[JSON.stringify({code})],'$1::jsonb');
 assert.equal((await db.query('select club_id from app_private.profiles where id=$1',[ids.friend])).rows[0].club_id,ok.club_id);
 await db.query('update app_private.profiles set club_id=null where id=$1',[ids.upkid]);
 await denied(()=>rpc('upkid','claim_join_code',[JSON.stringify({code})],'$1::jsonb'),/belongs to UP/);
 // A community club's first administrator can't be a school email.
 await denied(()=>rpc('admin','create_club',[JSON.stringify({name:'Faux Community',community:true,admin_email:'kid@up.example.test'})],'$1::jsonb'),/school email/);
 await db.query('update app_private.clubs set email_domain=null');
});

test('Elections 23 Nov: an officer runs a whole election in one sitting; early closing needs a reason; a second officer confirms',async()=>{
 await grant('officer','election_officer'); await grant('successor','election_officer');
 const e=await command('officer','create_election',{title:'One-sitting election',kind:'special',term_end:'2031-01-01',announces_at:future(24),candidacy_closes:future(48),
  voting_opens:future(72),voting_closes:future(96),result_due:future(120),threshold:10,residency_terms:0,rules:'One vote per position',rules_validated:true,method:'digital',positions:['President']});
 const adv=(p,reason='')=>rpc(p,'advance_election',[e.id,reason],'$1::uuid,$2::text');
 await denied(()=>adv('applicant'),/Permission denied/);
 assert.equal((await adv('officer')).status,'announced');            // a day before "announces at"
 const c=await command('applicant','nominate_candidate',{election_id:e.id,user_id:ids.successor,position:'President',statement:'Platform'});
 await denied(()=>adv('officer'),/voter roll|Confirm/);               // the old checks still apply
 await command('officer','confirm_candidate',{id:c.id,confirmed:true,remark:'Eligible'});
 const roll=await rpc('officer','add_all_eligible_voters',[e.id],'$1::uuid');
 assert.ok(roll.added>=1); assert.equal(roll.short_of_residency,0);
 assert.equal((await rpc('officer','add_all_eligible_voters',[e.id],'$1::uuid')).added,0,'nobody twice');
 const v=await adv('officer'); assert.equal(v.status,'voting'); assert.equal(v.early,true);
 await command('applicant','cast_ballot',{election_id:e.id,choices:{President:c.id}});
 const left=(await db.query('select count(*)::int n from app_private.eligible_voters x where election_id=$1 and not exists(select 1 from app_private.voter_participation p where p.election_id=x.election_id and p.user_id=x.user_id)',[e.id])).rows[0].n;
 if(left>0) await denied(()=>adv('officer'),/Say why voting closes early/);
 assert.equal((await adv('officer','Everyone present has voted')).status,'closed');
 const ver=(await db.query('select version from app_private.elections where id=$1',[e.id])).rows[0].version;
 await denied(()=>command('officer','confirm_results',{id:e.id,version:ver,confirmation:'Self'}),/Another COMELEC/);
 await command('successor','confirm_results',{id:e.id,version:ver,confirmation:'Checked the count'});
 assert.equal((await db.query('select status from app_private.elections where id=$1',[e.id])).rows[0].status,'confirmed');
 // The residency rule is reported, not silently applied.
 const e2=await command('officer','create_election',{title:'Residency check',kind:'special',term_end:'2031-01-01',announces_at:future(24),candidacy_closes:future(48),
  voting_opens:future(72),voting_closes:future(96),result_due:future(120),threshold:60,residency_terms:6,rules:'Six terms',rules_validated:true,method:'digital',positions:['President']});
 const r2=await rpc('officer','add_all_eligible_voters',[e2.id],'$1::uuid');
 assert.equal(r2.added,0); assert.ok(r2.short_of_residency>=1); assert.equal(r2.residency_terms,6);
});
