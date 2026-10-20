// Uses owner-approved demo logins, never administrative credentials.
// Usage: node tools/test_hosted_workflows.mjs PRIVATE_ACCOUNTS_JSON [--write-demo]
// --write-demo leaves clearly labelled synthetic workflow records for inspection.
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir} from 'node:fs/promises';
const config=await readFile(new URL('../backend.properties',import.meta.url),'utf8');
const base=config.match(/^SUPABASE_URL=(.*)$/m)?.[1].trim();
const key=config.match(/^SUPABASE_PUBLISHABLE_KEY=(.*)$/m)?.[1].trim();
assert.equal(base,'https://cidxuzrzgexkejssoglk.supabase.co');
const accounts=JSON.parse(await readFile(process.argv[2],'utf8'));
const results=[], users={}, artifacts=[];
const label=`QA ${new Date().toISOString()}`;
async function api(path,body,token,method='POST',extra={}) {
 const r=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{apikey:key,'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} :{}),...extra},...(body===undefined?{}:{body:JSON.stringify(body)})});
 return {status:r.status,ok:r.ok,data:await r.json().catch(()=>({}))};
}
async function good(path,body,token,method,extra){const r=await api(path,body,token,method,extra);assert.ok(r.ok,`${body?.p_action??path.split('?')[0]}: HTTP ${r.status}; code ${r.data.code??'unavailable'}; ${String(r.data.message??'').slice(0,250)}`);return r.data;}
const workspace=u=>good('/rest/v1/rpc/club_workspace',{},u.token);
const command=(u,action,data={})=>good('/rest/v1/rpc/club_command',{p_action:action,p_data:data},u.token);
async function denied(u,action,data={}){const r=await api('/rest/v1/rpc/club_command',{p_action:action,p_data:data},u.token);assert.equal(r.ok,false);assert.ok([400,401,403,409].includes(r.status));assert.ok(r.data.code,'Expected database denial');}
const only=process.argv.find(x=>x.startsWith('--only='))?.slice(7).split(',');
async function check(name,fn){if(only&&/^M\d/.test(name)&&!only.some(m=>name.startsWith(m+' ')))return;try{await fn();results.push({name,status:'PASS'});console.log(`PASS ${name}`);}catch(e){const reason=e.message.replace(/https?:\/\/\S+/g,'[URL]');results.push({name,status:'FAIL',reason});console.log(`FAIL ${name}: ${reason}`);}}
const future=h=>new Date(Date.now()+h*3600000).toISOString();
await check('Auth requires confirmation for ordinary email signup',async()=>{const a=await good('/auth/v1/settings',undefined,null,'GET');assert.equal(a.mailer_autoconfirm,false);});
await check('Anonymous workspace denied',async()=>{const r=await api('/rest/v1/rpc/club_workspace',{});assert.ok([401,403].includes(r.status));});
for(const role of ['admin','member','applicant','officer','partner']){
 await check(`${role}: login, refresh, exact permissions, ownership`,async()=>{
  const a=accounts[role];assert.ok(a?.email&&a.password,'Missing private account input');
  const s=await good('/auth/v1/token?grant_type=password',a);
  const u=users[role]={id:s.user.id,token:s.access_token};
  const refreshed=await good('/auth/v1/token?grant_type=refresh_token',{refresh_token:s.refresh_token});u.token=refreshed.access_token;
  const w=await workspace(u);assert.equal(w.user_id,u.id);
  const expected=role==='admin'?['administration','president']:role==='officer'?['membership','activities','elections','equipment','training','finance','reports','interclub']:[];
  assert.deepEqual([...w.permissions].sort(),expected.sort());
  if(['member','applicant','partner'].includes(role))assert.ok(w.profiles.every(p=>p.id===u.id));
  if(['member','officer'].includes(role))assert.ok(w.memberships.some(m=>m.user_id===u.id&&m.status==='Active'));
  if(role==='partner')for(const table of ['applications','financial_records','training_records'])assert.equal(w[table].length,0);
  if(role!=='admin')await denied(u,'assign_role');
 });
}
const {admin,member,applicant,officer,partner}=users;
if(member)await check('Private schema inaccessible to signed-in member',async()=>{const r=await api('/rest/v1/profiles?select=id&limit=0',undefined,member.token,'GET',{'Accept-Profile':'app_private'});assert.equal(r.status,406);assert.equal(r.data.code,'PGRST106');});
if(officer&&admin&&partner)await check('Split finance and partner review permissions',async()=>{await denied(officer,'approve_funds',{office:'president',id:crypto.randomUUID()});await denied(admin,'verify_financial_request',{id:crypto.randomUUID()});await denied(partner,'review_delegate',{event_id:crypto.randomUUID(),id:crypto.randomUUID(),status:'eligible'});});
if(member&&officer)await check('Report access permitted for officer and denied for member',async()=>{const body={p_from:'2026-10-01',p_to:'2026-10-31'};const r=await good('/rest/v1/rpc/club_report',body,officer.token);assert.ok(r.finance&&r.equipment);const blocked=await api('/rest/v1/rpc/club_report',body,member.token);assert.equal(blocked.ok,false);});
for(const fn of ['create-account','verify-training-video'])await check(`${fn}: unauthenticated execution denied`,async()=>{const r=await api('/functions/v1/'+fn,{});assert.ok([400,401,403].includes(r.status));});
if(member)await check('Member cannot provision accounts',async()=>{const r=await api('/functions/v1/create-account',{},member.token);assert.equal(r.status,403);});
if(process.argv.includes('--write-demo')&&member&&officer){
 await check('M5/M10 borrowing lifecycle, double booking, persistence',async()=>{
  const eq=await command(officer,'save_equipment',{label:label+' Bow',category:'Bow',specifications:'Synthetic QA fixture only',handedness:'right',draw_weight:24,bow_setup:'recurve',condition:'serviceable'});artifacts.push({table:'equipment',id:eq.id});
  const data={equipment_id:eq.id,starts_at:future(48),ends_at:future(50),handedness:'right',maximum_draw_weight:28,bow_setup:'recurve',purpose:label+' synthetic borrowing'};
  const a=await command(member,'request_borrowing',data);artifacts.push({table:'borrowings',id:a.id});
  const b=await command(officer,'request_borrowing',data);artifacts.push({table:'borrowings',id:b.id});
  await denied(member,'review_borrowing',{id:a.id,version:1,status:'approved'});
  const review={version:1,status:'approved',compatibility_confirmed:true,remark:'Synthetic QA compatibility check'};
  await command(officer,'review_borrowing',{id:a.id,...review});
  await denied(officer,'review_borrowing',{id:b.id,...review});
  await command(officer,'review_borrowing',{id:b.id,version:1,status:'rejected',remark:'Synthetic conflict test complete'});
  await command(officer,'review_borrowing',{id:a.id,version:2,status:'released',compatibility_confirmed:true,remark:'Synthetic release'});
  await command(officer,'review_borrowing',{id:a.id,version:3,status:'returned',condition:'serviceable',remark:'Synthetic return'});
  assert.equal((await workspace(member)).borrowings.find(x=>x.id===a.id)?.status,'returned');
 });
 await check('M3/M8 event registration, duplicate prevention and attendance',async()=>{
  const e=await command(officer,'save_event',{title:label+' practice',description:'Synthetic QA fixture; no real event',venue:'Development testing',starts_at:future(24),ends_at:future(26),registration_opens:future(-24),registration_closes:future(12),capacity:1});artifacts.push({table:'events',id:e.id});
  await denied(member,'register_event',{event_id:e.id});
  await command(officer,'publish_event',{id:e.id,version:1});
  const a=await command(member,'register_event',{event_id:e.id});
  assert.equal((await command(member,'register_event',{event_id:e.id})).id,a.id);
  await denied(officer,'register_event',{event_id:e.id});
  const win=await command(officer,'open_attendance',{event_id:e.id,opens_at:future(-1),closes_at:future(1)});
  const w=await workspace(officer);const token=w.attendance_windows.find(x=>x.id===win.id)?.token;assert.ok(token);
  await command(member,'check_in',{token});await command(member,'check_in',{token});
  const records=(await workspace(member)).attendance.filter(x=>x.event_id===e.id);assert.equal(records.length,1);assert.equal(records[0].status,'present');
 });
 await check('M4/M11 personal training validation and score bounds',async()=>{
  const data={training_date:new Date().toISOString().slice(0,10),distance:18,target_face:'40cm',category:'Recurve',scoring_format:'30 arrows',score:240,maximum_score:300,observations:label+' synthetic score'};
  await denied(member,'save_personal_training',{...data,score:301});
  const t=await command(member,'save_personal_training',data);artifacts.push({table:'training_records',id:t.id});
  await command(member,'submit_training',{id:t.id});
  await denied(member,'validate_training',{id:t.id,version:2,validation:'validated',feedback:'Self review'});
  await command(officer,'validate_training',{id:t.id,version:2,validation:'validated',feedback:'Synthetic QA feedback'});
  assert.equal((await workspace(member)).training_records.find(x=>x.id===t.id)?.validation,'validated');
 });
 await check('M12 financial draft persistence and required receipt',async()=>{
  await denied(member,'save_financial_request',{kind:'reimbursement',purpose:label,amount:'-1'});
  const f=await command(member,'save_financial_request',{kind:'reimbursement',purpose:label+' synthetic receipt validation',amount:'1.25'});artifacts.push({table:'financial_records',id:f.id});
  await denied(member,'submit_financial_request',{id:f.id,version:1});
  assert.equal((await workspace(member)).financial_records.find(x=>x.id===f.id)?.status,'draft');
 });
 await check('M9 pending migration: draft election editing',async()=>{
  const data={title:label+' draft election',kind:'special',term_end:'2027-01-01',announces_at:future(24),candidacy_closes:future(48),voting_opens:future(72),voting_closes:future(96),result_due:future(120),threshold:60,residency_terms:2,rules:'Synthetic QA only; awaiting club validation',rules_validated:false,method:'digital',positions:['President']};
  const e=await command(officer,'create_election',data);artifacts.push({table:'elections',id:e.id});
  await command(officer,'update_election',{...data,id:e.id,version:1,title:label+' edited draft election'});
  assert.equal((await workspace(officer)).elections.find(x=>x.id===e.id)?.version,2);
 });
}
if(process.argv.includes('--continue-demo')&&member&&officer&&admin&&partner){
 const previous=JSON.parse(await readFile(new URL(process.argv.includes('--resume')?'../docs/verification/hosted-workflows-followup.json':'../docs/verification/hosted-workflows-latest.json',import.meta.url),'utf8'));
 artifacts.push(...previous.artifacts);
 const fixture=table=>{const a=artifacts.find(x=>x.table===table);assert.ok(a,`Missing ${table} fixture`);return a.id;};
 const report=()=>good('/rest/v1/rpc/club_report',{p_from:'2026-10-01',p_to:'2026-10-31'},officer.token);
 await check('M12 private receipt, separate approvals, verified ledger totals',async()=>{
  const id=fixture('financial_records');const f=(await workspace(member)).financial_records.find(x=>x.id===id);assert.equal(f.status,'draft');
  const before=Number((await report()).finance.verified_expenses);
  const path=`${member.id}/12/${id}/${crypto.randomUUID()}.pdf`;
  const content='%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[]/Count 0>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF';
  const up=await fetch(base+'/storage/v1/object/club-documents/'+path,{method:'POST',signal:AbortSignal.timeout(30000),headers:{apikey:key,Authorization:'Bearer '+member.token,'Content-Type':'application/pdf'},body:content});assert.ok(up.ok,`Storage upload HTTP ${up.status}`);
  artifacts.push({table:'storage.objects',path});
  await good('/rest/v1/rpc/register_document',{p_data:{module:12,record_id:id,requirement:'Synthetic QA receipt',name:label+' test receipt.pdf',path}},member.token);
  assert.equal((await api('/storage/v1/object/sign/club-documents/'+path,{expiresIn:30},partner.token)).ok,false);
  await good('/storage/v1/object/sign/club-documents/'+path,{expiresIn:30},member.token);
  await command(member,'submit_financial_request',{id,version:f.version});
  await denied(officer,'verify_financial_request',{id,version:f.version+1,status:'verified',remark:'Missing prior approvals QA'});
  await command(admin,'approve_funds',{id,office:'president',remark:'Synthetic QA only; no real expenditure'});
  await command(officer,'approve_funds',{id,office:'finance',remark:'Synthetic QA only; no real expenditure'});
  await command(officer,'verify_financial_request',{id,version:f.version+1,status:'verified',remark:'Synthetic receipt QA; no money transferred'});
  assert.equal((await workspace(member)).financial_records.find(x=>x.id===id)?.status,'verified');
  assert.equal(Number((await report()).finance.verified_expenses),before+Number(f.amount));
 });
 await check('M13 goal actuals calculated from backend records',async()=>{
  await denied(member,'save_goal',{metric:'active_members',title:label,target:1,starts_on:'2026-10-01',ends_on:'2026-10-31'});
  const title=label+' calculated member goal';
  await command(officer,'save_goal',{metric:'active_members',title,target:10,starts_on:'2026-10-01',ends_on:'2026-10-31'});
  const r=await report(),g=r.goals.find(x=>x.title===title);assert.ok(g);artifacts.push({table:'goals',id:g.id});assert.equal(Number(g.actual),Number(r.membership.active));
 });
 await check('M9 existing draft edit, stale edit and unvalidated publication denied',async()=>{
  const id=fixture('elections');const e=(await workspace(officer)).elections.find(x=>x.id===id);assert.equal(e.status,'draft');
  await command(officer,'update_election',{...e,id,title:label+' edited QA draft',rules_validated:false});
  await denied(officer,'update_election',{...e,id});
  await denied(officer,'set_election_status',{id,version:e.version+1,status:'announced'});
  assert.equal((await workspace(officer)).elections.find(x=>x.id===id)?.version,e.version+1);
 });
 await check('M15 partner delegation, scheduling, results and archive',async()=>{
  const id=fixture('events');const e=(await workspace(officer)).events.find(x=>x.id===id);
  let w=await workspace(officer);
  if(!w.interclub_events.some(x=>x.event_id===id))await command(officer,'share_interclub_event',{event_id:id,shared_details:label+' synthetic interclub',requirements:'QA only',registration_closes:future(12),rules:'Synthetic test rules, no official event'});
  let p=artifacts.find(x=>x.table==='partners');
  if(!p){p=await command(officer,'save_partner',{name:label+' synthetic partner',official_channel:'QA only; no external contact'});artifacts.push({table:'partners',id:p.id});}
  if(!w.event_access.some(x=>x.event_id===id&&x.user_id===partner.id&&!x.revoked_at))await command(officer,'invite_partner',{event_id:id,email:accounts.partner.email,partner_id:p.id,role:'partner'});
  let d=artifacts.find(x=>x.table==='delegates');
  if(!d){d=await command(partner,'save_delegate',{event_id:id,partner_id:p.id,full_name:'Synthetic QA Archer',affiliation:'QA fixture',category:'Recurve',requirements:'Synthetic test only'});artifacts.push({table:'delegates',id:d.id});}
  let dr=(await workspace(officer)).delegates.find(x=>x.id===d.id);
  await denied(partner,'review_delegate',{event_id:id,id:d.id,version:dr.version,status:'eligible',remark:'Self review QA'});
  if(dr.status==='pending')await command(officer,'review_delegate',{event_id:id,id:d.id,version:dr.version,status:'eligible',remark:'Synthetic eligibility QA'});
  await denied(officer,'assign_delegate',{event_id:id,delegate_id:d.id,starts_at:future(1),ends_at:future(2),target_group:'QA target',responsibility:'Out of period QA'});
  w=await workspace(officer);
  if(!w.interclub_assignments.some(x=>x.event_id===id&&x.delegate_id===d.id))await command(officer,'assign_delegate',{event_id:id,delegate_id:d.id,starts_at:new Date(Date.parse(e.starts_at)+60000).toISOString(),ends_at:new Date(Date.parse(e.ends_at)-60000).toISOString(),target_group:'QA target',responsibility:'Synthetic schedule'});
  const update={event_id:id,shared_details:'Synthetic QA shared event',shared_venue:e.venue,shared_start:e.starts_at,shared_end:e.ends_at};
  const shared=(await workspace(officer)).interclub_events.find(x=>x.event_id===id);
  if(shared.status==='preparation'){
   await denied(officer,'update_shared_event',{...update,version:shared.version,status:'completed'});
   await command(officer,'update_shared_event',{...update,version:shared.version,status:'active'});
  }
  dr=(await workspace(officer)).delegates.find(x=>x.id===d.id);
  if(dr.status==='eligible')await command(officer,'review_delegate',{event_id:id,id:d.id,version:dr.version,status:'checked_in',remark:'Synthetic QA check-in'});
  const result=await command(officer,'record_result',{event_id:id,delegate_id:d.id,result:'Synthetic QA score; not a tournament result'});
  assert.equal((await workspace(partner)).interclub_results.filter(x=>x.id===result.id).length,0);
  await command(officer,'verify_result',{event_id:id,id:result.id});
  assert.equal((await workspace(partner)).interclub_results.filter(x=>x.id===result.id).length,1);
  await command(officer,'update_shared_event',{...update,version:2,status:'completed'});
  await command(officer,'archive_interclub',{event_id:id,summary:'Synthetic QA complete',procedures:'Synthetic',agreements:'Synthetic',approval_references:'Synthetic QA only',minutes:'Synthetic',issues:'None',evaluation:'QA',recommendations:'Retain as QA evidence'});
  await denied(officer,'record_result',{event_id:id,delegate_id:d.id,result:'Attempted archived edit'});
  assert.ok((await workspace(officer)).interclub_archives.some(x=>x.event_id===id));
 });
}
for(const [role,u] of Object.entries(users))await check(`${role}: fresh login retains access; sign out test session`,async()=>{const s=await good('/auth/v1/token?grant_type=password',accounts[role]);assert.equal((await workspace({token:s.access_token})).user_id,u.id);await good('/auth/v1/logout?scope=local',{},s.access_token);await good('/auth/v1/logout?scope=local',{},u.token);});
const report={timestamp:new Date().toISOString(),project:'cidxuzrzgexkejssoglk',scope:'Hosted API checks; not Android UI or full module acceptance',label,results,artifacts};
const dir=new URL('../docs/verification/',import.meta.url);await mkdir(dir,{recursive:true});
await writeFile(new URL(`hosted-${report.timestamp.replace(/[:.]/g,'-')}.json`,dir),JSON.stringify(report,null,2)+'\n');
await writeFile(new URL(process.argv.includes('--continue-demo')?'hosted-workflows-followup.json':'hosted-workflows-latest.json',dir),JSON.stringify(report,null,2)+'\n');
console.log(`${results.filter(r=>r.status==='PASS').length}/${results.length} checks passed. Synthetic artifacts: ${artifacts.length}.`);
process.exitCode=results.some(r=>r.status==='FAIL')?1:0;
