// Explicitly requested local demonstration accounts; never target a hosted project.
// Usage: node tools/setup_local_demo_users.mjs PRIVATE_STATUS_JSON PRIVATE_USERS_JSON
// The users file contains {admin:{email,password},member:{email,password},applicant:{email,password}}.
// It is removed after successful verification. No passwords or tokens are logged.
import assert from 'node:assert/strict';
import {readFile, unlink} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
const config=JSON.parse(await readFile(process.argv[2],'utf8'));
assert.equal(config.API_URL,'http://127.0.0.1:55421','Only the isolated local backend is allowed');
const accounts=JSON.parse(await readFile(process.argv[3],'utf8'));
const names={admin:'Development Administrator',member:'Test Member',applicant:'Test Applicant'};
const users={};
const key=config.PUBLISHABLE_KEY;
async function api(path,body,token,method='POST'){
 const response=await fetch(config.API_URL+path,{method,signal:AbortSignal.timeout(15000),
  headers:{apikey:key,'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},
  body:body===undefined?undefined:JSON.stringify(body)});
 const data=await response.json().catch(()=>({}));
 return {ok:response.ok,status:response.status,data};
}
async function ok(path,body,token,method){
 const r=await api(path,body,token,method);
 assert.ok(r.ok,`${path.split('?')[0]} failed (HTTP ${r.status}); no credentials logged`);
 return r.data;
}
const rpc=(name,data,token)=>ok('/rest/v1/rpc/'+name,data,token);
function sql(query){
 return execFileSync('/opt/homebrew/bin/docker',['exec','-i','supabase_db_archery-proposal-test',
  'psql','-U','postgres','-v','ON_ERROR_STOP=1','-At'],{input:query,encoding:'utf8',stdio:['pipe','pipe','pipe']}).trim();
}
const existing=[];
for(let page=1;;page++){
 const list=await ok(`/auth/v1/admin/users?page=${page}&per_page=100`,undefined,config.SERVICE_ROLE_KEY,'GET');
 existing.push(...list.users);
 if(list.users.length<100)break;
}
for(const role of ['admin','member','applicant']){
 const supplied=accounts[role];
 assert.ok(supplied&&/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(supplied.email));
 assert.ok(typeof supplied.password==='string'&&supplied.password.length>=8);
 const email=supplied.email.toLowerCase();
 const old=existing.find(u=>u.email?.toLowerCase()===email);
 // Confirmation here is a local demo fixture; no email is sent or ownership attested.
 const auth=old?await ok('/auth/v1/admin/users/'+old.id,{password:supplied.password,email_confirm:true},config.SERVICE_ROLE_KEY,'PUT'):
  await ok('/auth/v1/admin/users',{email,password:supplied.password,email_confirm:true,user_metadata:{local_demo:true}},config.SERVICE_ROLE_KEY);
 assert.match(auth.id,/^[0-9a-f-]{36}$/);
 const session=await ok('/auth/v1/token?grant_type=password',{email,password:supplied.password});
 users[role]={id:auth.id,email,token:session.access_token};
 await rpc('initialize_account',{},session.access_token);
 if(!old)await rpc('club_command',{p_action:'save_profile',p_data:{full_name:names[role]}},session.access_token);
}
const admin=users.admin,member=users.member,applicant=users.applicant;
if(sql(`select count(*) from app_private.role_assignments where user_id='${admin.id}' and role_id='administrator' and retired_at is null and now() between starts_at and ends_at;`)==='0'){
 const bootstrap=await readFile(new URL('../supabase/bootstrap-admin.sql',import.meta.url),'utf8');
 sql(bootstrap.replace('REPLACE_WITH_VERIFIED_ADMIN_EMAIL',admin.email.replaceAll("'","''")));
}
// Seed an explicitly requested test member without inventing an approved application.
sql(`begin;
 insert into app_private.memberships(user_id,category,status,approved_by)
 select '${member.id}',membership_categories[1],'Active','${admin.id}' from app_private.settings
 on conflict(user_id) do nothing;
 insert into app_private.audit_log(actor,module,record_id,action,detail)
 values('${admin.id}',14,'${member.id}','trusted_local_demo_member_setup',
 '{"reason":"Project owner explicitly requested an active local test member; not a screened application"}');
 commit;`);
let workspace=await rpc('club_workspace',{},applicant.token);
if(!workspace.applications.length)await rpc('club_command',{p_action:'save_application',p_data:{full_name:names.applicant,confirmed:false}},applicant.token);
for(const role of ['admin','member','applicant']){
 const user=users[role];
 const w=await rpc('club_workspace',{},user.token);
 assert.equal(w.user_id,user.id);
 if(role==='admin')assert.deepEqual(w.permissions,['administration']);
 else {
  assert.deepEqual(w.permissions,[]);
  assert.ok(w.profiles.every(p=>p.id===user.id),'Test account must not read other private profiles');
  const denied=await api('/rest/v1/rpc/club_command',{p_action:'assign_role',p_data:{}},user.token);
  assert.equal(denied.ok,false);
  assert.equal(denied.data.code,'42501','Role administration must be denied before input handling');
 }
 if(role==='member')assert.ok(w.memberships.some(m=>m.user_id===user.id&&m.status==='Active'));
 if(role==='applicant'){
  assert.equal(w.memberships.length,0);
  assert.equal(w.applications.length,1);
  assert.equal(w.applications[0].status,'draft');
 }
 await ok('/auth/v1/logout',{},user.token);
 console.log(`PASS: ${role} ${user.email} signs in with the supplied password and has the intended access.`);
}
await unlink(process.argv[3]);
console.log('Created/verified only in archery-proposal-test. No email sent. No hosted account created. Private password input removed.');
