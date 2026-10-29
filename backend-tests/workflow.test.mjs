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
async function grant(person,role){await command('admin','assign_role',{user_id:ids[person],role_id:role,position:role,ends_at:'2035-01-01',reason:'Integration fixture'});}
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
 await denied(()=>command('successor','register_event',{event_id:e.id}),/full/);
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
 await command('officer','confirm_results',{id:e.id,version:2,confirmation:'Turnout checked'});
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
 await command('officer','confirm_results',{id:e.id,version:6,confirmation:'Verified official result'});
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
 await command('officer','verify_result',{event_id:e.id,id:result.id});
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
