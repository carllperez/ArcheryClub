// Owner-authorized October 2026 development fixtures; never use for production provisioning.
// Usage: node tools/setup_hosted_demo.mjs SUPABASE_CLI PRIVATE_ACCOUNTS_JSON
// Private input has admin/member/applicant/officer/partner: {email,password}.
// Credentials are used in memory, never logged or saved in the repository.
import assert from 'node:assert/strict';
import {readFile, unlink} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const cli=process.argv[2], inputPath=process.argv[3];
const ref='cidxuzrzgexkejssoglk', base=`https://${ref}.supabase.co`;
const expiry='2026-11-01T00:00:00+08:00';
assert.ok(Date.now()<Date.parse(expiry),'October demo plan expired');
const accounts=JSON.parse(await readFile(inputPath,'utf8'));
const expected={admin:'carll_perez@dlsu.edu.ph',member:'testmember@dlsu.edu.ph',applicant:'testapplicant@dlsu.edu.ph',officer:'testofficer@dlsu.edu.ph',partner:'testpartner@dlsu.edu.ph'};
const names={admin:'Development Administrator',member:'Test Member',applicant:'Test Applicant',officer:'Test Officer',partner:'Test Partner Representative'};
const roles=['membership_officer','activity_officer','election_officer','equipment_officer','training_officer','treasurer','reports_officer','interclub_officer'];
function cliJson(args){
 let output;
 try {output=execFileSync(cli,[...args,'--output-format','json'],{encoding:'utf8',stdio:['ignore','pipe','pipe'],timeout:120000});}
 catch {throw Error('Supabase administrative command failed; credentials and raw output withheld.');}
 // Some CLI commands prefix their JSON with an informational line.
 const start=output.search(/[\[{]/);assert.ok(start>=0,'Expected CLI JSON');
 return JSON.parse(output.slice(start));
}
const keyResult=cliJson(['projects','api-keys','--project-ref',ref]);
const keys=Array.isArray(keyResult)?keyResult:keyResult.keys??keyResult.result;
assert.ok(Array.isArray(keys),'Unexpected API-key response; no credentials printed');
const service=keys.find(k=>k.name==='service_role')?.api_key;
const settings=await readFile(new URL('../backend.properties',import.meta.url),'utf8');
assert.equal(settings.match(/^SUPABASE_URL=(.*)$/m)?.[1].trim(),base);
const publishable=settings.match(/^SUPABASE_PUBLISHABLE_KEY=(.*)$/m)?.[1].trim();
assert.ok(service&&publishable,'Expected server and public credentials');
async function api(path,body,token,method='POST'){
 const response=await fetch(base+path,{method,signal:AbortSignal.timeout(30000),headers:{
  apikey:token===service?service:publishable,'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})
 },body:body===undefined?undefined:JSON.stringify(body)});
 return {ok:response.ok,status:response.status,data:await response.json().catch(()=>({}))};
}
async function ok(path,body,token,method){
 const r=await api(path,body,token,method);
 assert.ok(r.ok,`${path.split('?')[0]} failed: HTTP ${r.status}; response withheld`);return r.data;
}
const rpc=(name,data,token)=>ok('/rest/v1/rpc/'+name,data,token);
const command=(action,data,user)=>rpc('club_command',{p_action:action,p_data:data},user.token);
const workspace=user=>rpc('club_workspace',{},user.token);
function sql(query){
 // A leading SQL comment looks like a flag to the CLI argument parser.
 const statement=query.replace(/^--[^\n]*(\n|$)/gm,'').trim();
 return cliJson(['db','query','--linked','--project-ref',ref,statement]).rows;
}
const existing=[];
for(let page=1;;page++){
 const result=await ok(`/auth/v1/admin/users?page=${page}&per_page=100`,undefined,service,'GET');
 existing.push(...result.users);if(result.users.length<100)break;
}
const users={};
for(const [role,email] of Object.entries(expected)){
 assert.equal(accounts[role]?.email.toLowerCase(),email);
 assert.ok(accounts[role].password.length>=8);
 let auth=existing.find(u=>u.email?.toLowerCase()===email);
 const created=!auth;
 if(!auth)auth=await ok('/auth/v1/admin/users',{
  email,password:accounts[role].password,email_confirm:true,
  app_metadata:{owner_provisioned_demo:true,not_email_ownership_verification:true,demo_plan:'October 2026'},
 },service);
 else assert.equal(auth.app_metadata?.owner_provisioned_demo,true,'Refusing to modify an existing non-demo account');
 assert.match(auth.id,/^[0-9a-f-]{36}$/);
 const session=await ok('/auth/v1/token?grant_type=password',{email,password:accounts[role].password});
 users[role]={id:auth.id,email,token:session.access_token,refresh:session.refresh_token};
 await rpc('initialize_account',{},session.access_token);
 if(created)await command('save_profile',{full_name:names[role]},users[role]);
}
const {admin,member,applicant,officer,partner}=users;
let w=await workspace(admin);
if(!w.permissions.includes('administration')){
 const bootstrap=await readFile(new URL('../supabase/bootstrap-admin.sql',import.meta.url),'utf8');
 sql(bootstrap.replace('REPLACE_WITH_VERIFIED_ADMIN_EMAIL',admin.email));
 sql(`insert into app_private.audit_log(actor,module,record_id,action,detail) values('${admin.id}',14,'${admin.id}','owner_authorized_hosted_demo_bootstrap','{"note":"Owner-provisioned test identity, not proof of email ownership or club appointment"}');`);
}
async function assign(user,role){
 w=await workspace(admin);
 if(!w.role_assignments.some(a=>a.user_id===user.id&&a.role_id===role&&!a.retired_at&&Date.parse(a.ends_at)>Date.now()))
  await command('assign_role',{user_id:user.id,role_id:role,position:'Demo '+role.replaceAll('_',' '),ends_at:expiry,
   reason:'Owner-approved hosted demonstration fixture; not a real election or appointment.'},admin);
}
await assign(admin,'president');for(const role of roles)await assign(officer,role);
for(const user of [member,officer])sql(`begin;
 with added as (insert into app_private.memberships(user_id,category,status,approved_by)
 select '${user.id}',membership_categories[1],'Active','${admin.id}' from app_private.settings
 on conflict(user_id) do nothing returning user_id)
 insert into app_private.audit_log(actor,module,record_id,action,detail)
 select '${admin.id}',14,user_id,'owner_authorized_hosted_demo_membership',
 '{"note":"Synthetic presentation membership, not a screened application"}' from added; commit;`);
w=await workspace(applicant);
if(!w.applications.length)await command('save_application',{full_name:names.applicant,confirmed:false},applicant);
w=await workspace(officer);
const title='CAPSTONE 1 Demonstration Interclub Event';
const matches=w.events.filter(e=>e.title===title);assert.ok(matches.length<=1);
let event=matches[0];
if(!event){
 const result=await command('save_event',{title,description:'Fictional hosted presentation fixture. No real tournament or institutional approval.',venue:'Development demonstration only',
  starts_at:'2026-10-10T09:00:00+08:00',ends_at:'2026-10-10T17:00:00+08:00',registration_opens:'2026-10-07T00:00:00+08:00',registration_closes:'2026-10-10T09:00:00+08:00',capacity:30,
  requirements:'Fictional demonstration data only',assigned_personnel:'Test Officer; Test Member (coordinator)'},officer);
 event=(await workspace(officer)).events.find(e=>e.id===result.id);
}
assert.ok(event&&!event.archived);
if(!event.published)await command('publish_event',{id:event.id,version:event.version},officer);
w=await workspace(officer);
if(!w.interclub_events.some(e=>e.event_id===event.id))await command('share_interclub_event',{event_id:event.id,
 shared_details:'Fictional presentation shared only with assigned demo accounts.',requirements:'Use fictional delegates only.',registration_closes:'2026-10-10T09:00:00+08:00',
 rules:'Demonstration only; club rules remain awaiting validation.'},officer);
w=await workspace(officer);
let club=w.partner_clubs.find(p=>p.name==='Demonstration Partner Club');
if(!club)club=await command('save_partner',{name:'Demonstration Partner Club',official_channel:'Demo only; no external email.'},officer);
for(const [user,role,partnerId] of [[partner,'partner',club.id],[member,'coordinator',null]]){
 w=await workspace(officer);
 const grant=w.event_access.find(g=>g.event_id===event.id&&g.user_id===user.id&&!g.revoked_at);
 if(!grant)await command('invite_partner',{event_id:event.id,email:user.email,role,partner_id:partnerId},officer);
 else {assert.equal(grant.role,role);assert.equal(grant.partner_id,partnerId);}
}
async function denied(user,action,data){
 const r=await api('/rest/v1/rpc/club_command',{p_action:action,p_data:data},user.token);
 assert.equal(r.ok,false);assert.equal(r.data.code,'42501');
}
for(const [role,user] of Object.entries(users)){
 const own=await workspace(user);
 assert.equal(own.user_id,user.id);
 const permitted=role==='admin'?['administration','president']:role==='officer'?['membership','activities','elections','equipment','training','finance','reports','interclub']:[];
 assert.deepEqual([...own.permissions].sort(),permitted.sort());
 if(['member','officer'].includes(role))assert.ok(own.memberships.some(m=>m.user_id===user.id&&m.status==='Active'));
 if(['applicant','partner'].includes(role))assert.equal(own.memberships.length,0);
 if(role==='applicant'){assert.equal(own.applications[0].status,'draft');assert.equal(own.interclub_events.length,0);}
 if(['member','applicant','partner'].includes(role))assert.ok(own.profiles.every(p=>p.id===user.id));
 if(['member','partner'].includes(role))assert.deepEqual(own.interclub_events.map(e=>e.event_id),[event.id]);
 if(role!=='admin')await denied(user,'assign_role',{});
 const refreshed=await ok('/auth/v1/token?grant_type=refresh_token',{refresh_token:user.refresh});
 user.token=refreshed.access_token;
 assert.equal((await workspace(user)).user_id,user.id);
 const again=await ok('/auth/v1/token?grant_type=password',{email:user.email,password:accounts[role].password});
 assert.deepEqual((await rpc('club_workspace',{},again.access_token)).permissions,own.permissions);
 await ok('/auth/v1/logout?scope=local',{},again.access_token);
 console.log(`PASS ${role}: hosted login, refresh, persistent access and role restrictions`);
}
await denied(officer,'approve_funds',{office:'president',id:crypto.randomUUID()});
await denied(admin,'verify_financial_request',{id:crypto.randomUUID()});
await denied(partner,'review_delegate',{event_id:event.id,id:crypto.randomUUID(),status:'eligible'});
await denied(member,'invite_partner',{event_id:event.id,email:partner.email,role:'partner',partner_id:club.id});
assert.equal((await api('/rest/v1/rpc/club_workspace',{})).ok,false);
assert.equal((await api('/functions/v1/create-account',{},member.token)).status,403);
assert.equal((await api('/functions/v1/verify-training-video',{})).ok,false);
const application=(await workspace(applicant)).applications[0];
const path=`${applicant.id}/2/${application.id}/${crypto.randomUUID()}.pdf`;
const upload=await fetch(base+'/storage/v1/object/club-documents/'+path,{method:'POST',headers:{apikey:publishable,Authorization:'Bearer '+applicant.token,'Content-Type':'application/pdf'},body:'%PDF-1.4\nSynthetic access-policy probe only\n%%EOF'});
assert.ok(upload.ok,'Private storage upload');
assert.ok((await api('/storage/v1/object/sign/club-documents/'+path,{expiresIn:30},applicant.token)).ok);
assert.equal((await api('/storage/v1/object/sign/club-documents/'+path,{expiresIn:30},partner.token)).ok,false);
await ok('/storage/v1/object/club-documents',{prefixes:[path]},applicant.token,'DELETE');
console.log('PASS private storage ownership and cross-user denial; temporary probe removed');
console.log('PASS anonymous RPC/Edge denial and split President/Treasurer/event permissions');
for(const user of Object.values(users))await ok('/auth/v1/logout?scope=local',{},user.token);
await unlink(inputPath);
console.log(JSON.stringify({project:ref,eventId:event.id,partnerClubId:club.id,roleEndsAt:expiry,privateInputRemoved:true},null,2));
