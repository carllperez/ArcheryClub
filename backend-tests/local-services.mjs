// Runs ONLY against the isolated local Supabase project. Never point this at hosted data.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const config=JSON.parse(await readFile(process.argv[2],'utf8'));
assert.equal(config.API_URL,'http://127.0.0.1:55421','Only the isolated local test endpoint is allowed');
const base=config.API_URL,key=config.PUBLISHABLE_KEY||config.ANON_KEY;
const password='Local-test-'+crypto.randomUUID();
async function api(path,body,token,method='POST',extra={}){
 const response=await fetch(base+path,{method,headers:{apikey:key,...(token?{Authorization:'Bearer '+token}:{}),'Content-Type':'application/json',...extra},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await response.json().catch(()=>({}));return {status:response.status,ok:response.ok,result};
}
async function ok(path,body,token,method){const r=await api(path,body,token,method);assert.ok(r.ok,`${path}: ${r.status} ${r.result.message||r.result.msg||r.result.error||''}`);return r.result;}
const rpc=(name,data,token)=>ok('/rest/v1/rpc/'+name,data,token);
const users={};
for(const name of ['reviewer','applicant','outsider']){
 const email=`${name}-${crypto.randomUUID()}@example.test`;
 const created=await ok('/auth/v1/admin/users',{email,password,email_confirm:true},config.SERVICE_ROLE_KEY);
 assert.match(created.id,/^[0-9a-f-]{36}$/);
 const session=await ok('/auth/v1/token?grant_type=password',{email,password});
 users[name]={id:created.id,email,...session};await rpc('initialize_account',{},session.access_token);
}
// Fixture role assignment is intentionally outside the public app API.
execFileSync('/opt/homebrew/bin/docker',['exec','supabase_db_archery-proposal-test','psql','-U','postgres','-v','ON_ERROR_STOP=1','-c',
 `insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason) values('${users.reviewer.id}','membership_officer','Integration reviewer',now()+interval '1 day','Isolated local fixture');`],{stdio:'pipe'});
const token=users.applicant.access_token,review=users.reviewer.access_token;
assert.equal((await api('/rest/v1/rpc/club_workspace',{})).ok,false);
let application=await rpc('club_command',{p_action:'save_application',p_data:{full_name:'Local Service Archer',confirmed:true}},token);
const path=`${users.applicant.id}/2/${application.id}/${crypto.randomUUID()}.pdf`;
const file=await fetch(base+'/storage/v1/object/club-documents/'+path,{method:'POST',headers:{apikey:key,Authorization:'Bearer '+token,'Content-Type':'application/pdf'},body:'%PDF-1.4\nLocal service test\n%%EOF'});
assert.ok(file.ok,'Real storage upload must pass owner policy');
await rpc('register_document',{p_data:{module:2,record_id:application.id,path,name:'Test proof.pdf',requirement:'Proof'}},token);
assert.ok((await api('/storage/v1/object/sign/club-documents/'+path,{expiresIn:60},token)).ok);
assert.equal((await api('/storage/v1/object/sign/club-documents/'+path,{expiresIn:60},users.outsider.access_token)).ok,false);
application=await rpc('club_command',{p_action:'submit_application',p_data:{version:application.version,full_name:'Local Service Archer',confirmed:true}},token);
await rpc('club_command',{p_action:'review_application',p_data:{id:application.id,version:application.version,status:'approved',category:'Regular member',requirements_verified:true}},review);
assert.equal((await rpc('club_workspace',{},token)).memberships[0].user_id,users.applicant.id);
const renewed=await ok('/auth/v1/token?grant_type=refresh_token',{refresh_token:users.applicant.refresh_token});
assert.equal((await rpc('club_workspace',{},renewed.access_token)).memberships[0].status,'Active');
const again=await ok('/auth/v1/token?grant_type=password',{email:users.applicant.email,password});
assert.equal((await rpc('club_workspace',{},again.access_token)).applications[0].status,'approved');
const outside=await rpc('club_workspace',{},users.outsider.access_token);assert.equal(outside.applications.length,0);assert.equal(outside.memberships.length,0);
await ok('/auth/v1/logout',{},again.access_token);
// Independent HTTP requests exercise separate database transactions, not a mocked queue.
let second=await rpc('club_command',{p_action:'save_application',p_data:{full_name:'Concurrent Archer',confirmed:true}},users.outsider.access_token);
second=await rpc('club_command',{p_action:'submit_application',p_data:{version:second.version,full_name:'Concurrent Archer',confirmed:true}},users.outsider.access_token);
await rpc('club_command',{p_action:'review_application',p_data:{id:second.id,version:second.version,status:'approved',category:'Regular member',requirements_verified:true}},review);
execFileSync('/opt/homebrew/bin/docker',['exec','supabase_db_archery-proposal-test','psql','-U','postgres','-v','ON_ERROR_STOP=1','-c',
 `insert into app_private.role_assignments(user_id,role_id,position,ends_at,reason) values('${users.reviewer.id}','activity_officer','Integration reviewer',now()+interval '1 day','Isolated fixture'),('${users.reviewer.id}','equipment_officer','Integration reviewer',now()+interval '1 day','Isolated fixture');`],{stdio:'pipe'});
const command=(action,data,access)=>rpc('club_command',{p_action:action,p_data:data},access);
const future=hours=>new Date(Date.now()+hours*3600000).toISOString();
const event=await command('save_event',{title:'Concurrent capacity',description:'Test',venue:'Test range',starts_at:future(24),ends_at:future(25),registration_opens:future(-1),registration_closes:future(12),capacity:1},review);
await command('publish_event',{id:event.id,version:1},review);
const registrations=await Promise.all([token,users.outsider.access_token].map(access=>api('/rest/v1/rpc/club_command',{p_action:'register_event',p_data:{event_id:event.id}},access)));
assert.equal(registrations.filter(r=>r.ok).length,1,'Concurrent registration must not exceed capacity');
const eq=await command('save_equipment',{label:'Concurrent bow '+crypto.randomUUID(),category:'Bow',specifications:'Fixture',handedness:'right',condition:'serviceable',draw_weight:24,bow_setup:'recurve'},review);
const requests=await Promise.all([token,users.outsider.access_token].map(access=>command('request_borrowing',{equipment_id:eq.id,starts_at:future(48),ends_at:future(50),handedness:'right',maximum_draw_weight:30,bow_setup:'recurve',purpose:'Fixture'},access)));
const decisions=await Promise.all(requests.map(r=>api('/rest/v1/rpc/club_command',{p_action:'review_borrowing',p_data:{id:r.id,version:1,status:'approved',compatibility_confirmed:true,remark:'Test fit'}},review)));
assert.equal(decisions.filter(r=>r.ok).length,1,'Concurrent approval must not double-book equipment');
console.log('PASS: real local Auth sign-in/refresh/re-login/logout, PostgREST application approval, same identity, private Storage upload/download and cross-user denial.');
console.log('PASS: concurrent HTTP registration and equipment approval preserve capacity and exclusive booking.');
console.log('Fictional test users remain only in archery-proposal-test. No hosted project was contacted.');
await writeFile('/private/tmp/archery-device-fixture.json',JSON.stringify({email:users.applicant.email,password}),{mode:0o600});
