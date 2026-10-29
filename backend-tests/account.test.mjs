import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createAccountHandler} from '../supabase/functions/create-account/handler.mjs';
const response=data=>new Response(JSON.stringify(data));
const request=()=>new Request('https://test.invalid',{method:'POST',headers:{Authorization:'Bearer user-token'},body:JSON.stringify({email:'demo@example.invalid',full_name:'Demo',reason:'Testing'})});
test('Email-disabled server rejects even an administrator without sending or recording an invitation',async()=>{
 const calls=[];
 const handler=createAccountHandler({url:'https://test.invalid',anon:'public',service:'private',fetcher:async url=>{calls.push(url);return response({permissions:['administration']});}});
 const result=await handler(request());assert.equal(result.status,503);assert.equal(calls.length,1);
 assert.ok(calls[0].endsWith('/club_workspace'));
});
test('Anonymous and ordinary members cannot invoke privileged account creation',async()=>{
 let calls=0;
 const handler=createAccountHandler({url:'https://test.invalid',emailEnabled:true,fetcher:async()=>{calls++;return response({permissions:[]});}});
 assert.equal((await handler(new Request('https://test.invalid',{method:'POST',body:'{}'}))).status,401);assert.equal(calls,0);
 assert.equal((await handler(request())).status,403);assert.equal(calls,1);
});
test('Enabled invitation uses caller permissions first and audits completion without exposing the server key',async()=>{
 const calls=[];
 const handler=createAccountHandler({url:'https://test.invalid',anon:'public',service:'private-secret',emailEnabled:true,fetcher:async(url,options)=>{
  calls.push([url,options]);
  if(url.endsWith('club_workspace'))return response({permissions:['administration']});
  if(url.endsWith('request_account_creation'))return response('request-id');
  if(url.endsWith('/invite'))return response({id:'new-id'});
  return response({});
 }});
 const result=await handler(request());assert.equal(result.status,200);assert.equal(calls.length,4);
 assert.equal(calls[0][1].headers.Authorization,'Bearer user-token');assert.equal(calls[1][1].headers.Authorization,'Bearer user-token');
 assert.equal(calls[2][1].headers.Authorization,'Bearer private-secret');assert.ok(calls[3][0].endsWith('complete_account_creation'));
 assert.equal((await result.text()).includes('private-secret'),false);
});
