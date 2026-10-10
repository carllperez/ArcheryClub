// Apply the explicitly approved five-account plan ONLY to the isolated local Supabase project.
// Usage: node tools/apply_local_role_plan.mjs PRIVATE_STATUS_JSON PRIVATE_USERS_JSON
// Input keys: admin/member/applicant/officer/partner, each with email and password.
// No passwords or session tokens are written to the repository or printed.
import assert from 'node:assert/strict';
import {readFile,unlink} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const config=JSON.parse(await readFile(process.argv[2],'utf8'));
assert.equal(config.API_URL,'http://127.0.0.1:55421');
const input=JSON.parse(await readFile(process.argv[3],'utf8'));
const endsAt='2026-11-01T00:00:00+08:00';
assert.ok(Date.now()<Date.parse(endsAt),'This approved October 2026 demo plan has expired');
const expectedEmails={admin:'carll_perez@dlsu.edu.ph',member:'testmember@dlsu.edu.ph',applicant:'testapplicant@dlsu.edu.ph',officer:'testofficer@dlsu.edu.ph',partner:'testpartner@dlsu.edu.ph'};
const officerRoles=['membership_officer','activity_officer','election_officer','equipment_officer','training_officer','treasurer','reports_officer','interclub_officer'];
const officerPermissions=['membership','activities','elections','equipment','training','finance','reports','interclub'];
const users={};
async function api(path,body,token,method='POST'){
 const response=await fetch(config.API_URL+path,{method,signal:AbortSignal.timeout(15000),
  headers:{apikey:config.PUBLISHABLE_KEY,'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
  body:body===undefined?undefined:JSON.stringify(body)});
 return {ok:response.ok,status:response.status,data:await response.json().catch(()=>({}))};
}
async function ok(path,body,token,method){
 const r=await api(path,body,token,method);
 assert.ok(r.ok,`${path.split('?')[0]} failed: HTTP ${r.status} (${r.data.code||'no code'}); credential details withheld`);
 return r.data;
}
const rpc=(name,data,token)=>ok('/rest/v1/rpc/'+name,data,token);
const command=(action,data,user)=>rpc('club_command',{p_action:action,p_data:data},user.token);
const workspace=user=>rpc('club_workspace',{},user.token);
const existing=[];
for(let page=1;;page++){
 const list=await ok(`/auth/v1/admin/users?page=${page}&per_page=100`,undefined,config.SERVICE_ROLE_KEY,'GET');
 existing.push(...list.users);if(list.users.length<100)break;
}
// Only the officer and partner may be newly created by this approved plan.
for(const role of Object.keys(expectedEmails)){
 const supplied=input[role];
 assert.equal(supplied.email.toLowerCase(),expectedEmails[role]);
 assert.ok(typeof supplied.password==='string'&&supplied.password.length>=8);
 let auth=existing.find(u=>u.email?.toLowerCase()===expectedEmails[role]);
 const created=!auth;
 if(!auth){
  assert.ok(['officer','partner'].includes(role),'Expected existing account is missing');
  auth=await ok('/auth/v1/admin/users',{email:supplied.email,password:supplied.password,email_confirm:true,user_metadata:{local_demo:true}},config.SERVICE_ROLE_KEY);
 }
 assert.match(auth.id,/^[0-9a-f-]{36}$/);
 const session=await ok('/auth/v1/token?grant_type=password',{email:supplied.email,password:supplied.password});
 assert.equal(session.user.id,auth.id);
 users[role]={id:auth.id,email:expectedEmails[role],token:session.access_token};
 await rpc('initialize_account',{},session.access_token);
 if(created)await command('save_profile',{full_name:role==='officer'?'Test Officer':'Test Partner Representative'},users[role]);
}
const {admin,member,applicant,officer,partner}=users;
let w=await workspace(admin);
assert.ok(w.permissions.includes('administration'));
async function assign(user,role,position){
 w=await workspace(admin);
 const current=w.role_assignments.filter(a=>a.user_id===user.id&&a.role_id===role&&!a.retired_at&&Date.parse(a.starts_at)<=Date.now()&&Date.parse(a.ends_at)>Date.now());
 if(!current.length)await command('assign_role',{user_id:user.id,role_id:role,position,ends_at:endsAt,reason:'Owner approved five-account local demonstration plan; not an actual election or appointment.'},admin);
}
await assign(admin,'president','Demo President approver');
for(const role of officerRoles)await assign(officer,role,'Demo '+role.replaceAll('_',' '));
// Membership is a separate, explicitly requested test fixture; no application approval is invented.
execFileSync('/opt/homebrew/bin/docker',['exec','-i','supabase_db_archery-proposal-test','psql','-U','postgres','-v','ON_ERROR_STOP=1'],{
 input:`begin;
 with inserted as (
  insert into app_private.memberships(user_id,category,status,approved_by)
  select '${officer.id}',membership_categories[1],'Active','${admin.id}' from app_private.settings
  on conflict(user_id) do nothing returning user_id
 ) insert into app_private.audit_log(actor,module,record_id,action,detail)
 select '${admin.id}',14,user_id,'trusted_local_demo_officer_membership',
 '{"reason":"Owner approved active membership for the combined demo officer; not a screened application"}' from inserted;
 commit;`,stdio:['pipe','pipe','pipe']});
// Select this fixture by its distinctive title; refuse ambiguous matches on rerun.
w=await workspace(officer);
const title='CAPSTONE 1 Demonstration Interclub Event';
const matches=w.events.filter(e=>e.title===title);
assert.ok(matches.length<=1,'More than one demo event matches; review before applying grants');
let event=matches[0];
if(!event){
 const result=await command('save_event',{title,description:'Fictional local presentation fixture. No real tournament or institutional approval is represented.',
  venue:'Local demonstration only',starts_at:'2026-10-10T09:00:00+08:00',ends_at:'2026-10-10T17:00:00+08:00',
  registration_opens:'2026-10-07T00:00:00+08:00',registration_closes:'2026-10-10T09:00:00+08:00',capacity:30,
  requirements:'Fictional demonstration data only',assigned_personnel:'Test Officer (host); Test Member (event coordinator)'},officer);
 w=await workspace(officer);event=w.events.find(e=>e.id===result.id);
}
assert.ok(event&&!event.archived,'Demo event must be editable');
if(!event.published)await command('publish_event',{id:event.id,version:event.version},officer);
w=await workspace(officer);
if(!w.interclub_events.some(e=>e.event_id===event.id))await command('share_interclub_event',{
 event_id:event.id,shared_details:'Fictional local interclub presentation. Shared only with assigned demonstration accounts.',
 requirements:'Use fictional delegates; no real participant documents.',registration_closes:'2026-10-10T09:00:00+08:00',
 rules:'Demonstration only. Club rules remain awaiting validation; this fixture does not constitute an approved competition.'},officer);
w=await workspace(officer);
let club=w.partner_clubs.find(p=>p.name==='Demonstration Partner Club');
if(!club){const result=await command('save_partner',{name:'Demonstration Partner Club',official_channel:'Local demo only; no external messages or email.'},officer);club={id:result.id};}
for(const [user,role,partnerId] of [[partner,'partner',club.id],[member,'coordinator',null]]){
 w=await workspace(officer);
 const grant=w.event_access.find(g=>g.event_id===event.id&&g.user_id===user.id&&!g.revoked_at);
 if(!grant||grant.role!==role||grant.partner_id!==partnerId)await command('invite_partner',{event_id:event.id,email:user.email,role,partner_id:partnerId},officer);
}
async function denied(user,action,data){
 const r=await api('/rest/v1/rpc/club_command',{p_action:action,p_data:data},user.token);
 assert.equal(r.ok,false,`${action} should not be allowed`);
 assert.equal(r.data.code,'42501',`${action} must fail authorization before input processing`);
}
for(const [name,user] of Object.entries(users)){
 const own=await workspace(user);
 const perms=[...own.permissions].sort();
 assert.deepEqual(perms,name==='admin'?['administration','president']:name==='officer'?[...officerPermissions].sort():[]);
 if(['member','officer'].includes(name))assert.ok(own.memberships.some(m=>m.user_id===user.id&&m.status==='Active'));
 if(['applicant','partner'].includes(name))assert.equal(own.memberships.length,0);
 if(name==='applicant'){assert.equal(own.applications.length,1);assert.equal(own.applications[0].status,'draft');assert.equal(own.interclub_events.length,0);}
 if(['member','partner'].includes(name)){
  assert.deepEqual(own.interclub_events.map(e=>e.event_id),[event.id]);
  const grant=own.event_access.find(g=>g.user_id===user.id&&!g.revoked_at);
  assert.equal(grant.role,name==='member'?'coordinator':'partner');
  assert.equal(grant.event_id,event.id);
  assert.ok(own.profiles.every(p=>p.id===user.id));
  assert.ok(own.financial_records.every(f=>f.user_id===user.id));
 }
 if(name!=='admin')await denied(user,'assign_role',{});
 console.log(`PASS ${name}: login, expected permissions and record scope.`);
}
await denied(officer,'approve_funds',{office:'president',id:crypto.randomUUID()});
await denied(admin,'verify_financial_request',{id:crypto.randomUUID()});
await denied(partner,'review_delegate',{event_id:event.id,id:crypto.randomUUID(),status:'eligible'});
await denied(member,'invite_partner',{event_id:event.id,email:partner.email,role:'partner',partner_id:club.id});
console.log('PASS: officer cannot supply President approval; administrator cannot act as Treasurer; partner cannot review delegates; coordinator cannot invite users.');
for(const user of Object.values(users))await ok('/auth/v1/logout?scope=local',{},user.token);
await unlink(process.argv[3]);
console.log(JSON.stringify({eventId:event.id,eventTitle:title,partnerClubId:club.id,roleEndsAt:endsAt,environment:'local archery-proposal-test',passwordInputRemoved:true},null,2));
