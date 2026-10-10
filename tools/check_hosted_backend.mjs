// Non-mutating checks using only the app's publishable key.
import assert from 'node:assert/strict';
import {readFile,unlink} from 'node:fs/promises';
const config=await readFile(new URL('../backend.properties',import.meta.url),'utf8');
const base=config.match(/^SUPABASE_URL=(.*)$/m)?.[1].trim();
const key=config.match(/^SUPABASE_PUBLISHABLE_KEY=(.*)$/m)?.[1].trim();
assert.equal(base,'https://cidxuzrzgexkejssoglk.supabase.co');
async function request(path,options={}){
 const response=await fetch(base+path,{...options,signal:AbortSignal.timeout(30000),headers:{apikey:key,'Content-Type':'application/json',...options.headers}});
 return {status:response.status,data:await response.json().catch(()=>({}))};
}
const auth=await request('/auth/v1/settings');
assert.equal(auth.status,200);assert.equal(auth.data.mailer_autoconfirm,false);
console.log('PASS hosted Auth reachable; ordinary email signup still requires confirmation');
const schema=await request('/rest/v1/profiles?select=id&limit=0',{headers:{'Accept-Profile':'app_private'}});
assert.ok([401,403,406].includes(schema.status));
if(schema.status===406)assert.equal(schema.data.code,'PGRST106');
console.log('PASS anonymous private-schema request denied; inspect exposed schemas separately');
if(process.argv[2]){
 const inputPath=process.argv[2];
 const account=JSON.parse(await readFile(inputPath,'utf8'));
 assert.equal(account.email.toLowerCase(),'testmember@dlsu.edu.ph');
 const login=await request('/auth/v1/token?grant_type=password',{method:'POST',body:JSON.stringify(account)});
 assert.equal(login.status,200);
 try{
  const denied=await request('/rest/v1/profiles?select=id&limit=0',{headers:{'Accept-Profile':'app_private',Authorization:'Bearer '+login.data.access_token}});
  assert.equal(denied.status,406,`Private schema check: ${denied.status}, ${denied.data.code ?? 'no code'}`);assert.equal(denied.data.code,'PGRST106');
  console.log('PASS authenticated private-schema request rejected as unexposed');
 }finally{
  await request('/auth/v1/logout?scope=local',{method:'POST',body:'{}',headers:{Authorization:'Bearer '+login.data.access_token}});
  await unlink(inputPath);
 }
}
const anonymous=await request('/rest/v1/rpc/club_workspace',{method:'POST',body:'{}'});
assert.ok([401,403].includes(anonymous.status));
console.log('PASS anonymous workspace access denied');
for(const fn of ['create-account','verify-training-video']){
 const denied=await request('/functions/v1/'+fn,{method:'POST',body:'{}'});
 assert.equal(denied.status,400);
 assert.ok(typeof denied.data.error==='string');
 console.log(`PASS ${fn} deployed and rejects unauthenticated execution`);
}
