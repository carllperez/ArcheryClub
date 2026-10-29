import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PortalApi} from '../portal/api.mjs';
import {canEditRecord} from '../portal/access.mjs';
const response=(status,data)=>({status,ok:status>=200&&status<300,json:async()=>data});
const config={url:'https://example.supabase.co',key:'sb_publishable_test'};
function storage(value){let saved=value;return {getItem:()=>saved,setItem:(_,v)=>saved=v,removeItem:()=>saved=null};}
const expired=JSON.stringify({access_token:'old',refresh_token:'refresh',expires_at:1});
test('Portal refreshes before mutations and shares a single refresh across concurrent requests',async()=>{
 const calls=[];
 const api=new PortalApi(config,{storage:storage(expired),clock:()=>100000,fetcher:async(url,options)=>{
  calls.push([url,options]);
  if(url.includes('grant_type=refresh_token')){await new Promise(resolve=>setTimeout(resolve,5));return response(200,{access_token:'new',refresh_token:'rotated',expires_in:3600});}
  assert.equal(options.headers.Authorization,'Bearer new');return response(200,{});
 }});
 await Promise.all([api.request('/rest/v1/rpc/club_command',{p_action:'save_application'}),api.request('/rest/v1/rpc/club_workspace',{})]);
 assert.equal(calls.filter(([url])=>url.includes('refresh_token')).length,1);
 assert.equal(api.session.refresh_token,'rotated');
});
test('Portal preserves the draft on network failure and never repeats a mutation automatically',async()=>{
 let calls=0;const s=storage(JSON.stringify({access_token:'current',expires_at:999999}));
 const api=new PortalApi(config,{storage:s,clock:()=>1000,fetcher:async()=>{calls++;throw Error('offline');}});
 await assert.rejects(()=>api.request('/rest/v1/rpc/club_command',{p_action:'save_delegate'}),/Check saved records/);
 assert.equal(calls,1);assert.ok(api.session);
});
test('Portal clears an invalid refresh session and does not send the queued operation',async()=>{
 let calls=0;const s=storage(expired);
 const api=new PortalApi(config,{storage:s,fetcher:async()=>{calls++;return response(400,{error_code:'refresh_token_not_found'});}});
 await assert.rejects(()=>api.request('/rest/v1/rpc/club_command',{}),/Sign in again/);
 assert.equal(calls,1);assert.equal(s.getItem(),null);
});
test('Portal keeps the session when refresh fails temporarily',async()=>{
 const api=new PortalApi(config,{storage:storage(expired),fetcher:async()=>response(503,{})});
 await assert.rejects(()=>api.request('/rest/v1/rpc/club_workspace',{}),/temporarily unavailable/);
 assert.ok(api.session);
});
test('Portal handles corrupt stored sessions and does not display database internals',async()=>{
 const s=storage('{broken');const api=new PortalApi(config,{storage:s,fetcher:async()=>response(400,{code:'23514',message:'private table details'})});
 assert.equal(api.session,null);assert.equal(s.getItem(),null);
 await assert.rejects(()=>api.request('/rest/v1/rpc/club_command',{}),e=>!e.message.includes('private table'));
});
test('Private file uploads use refreshed access without JSON-encoding the file',async()=>{
 const blob=new Blob(['test']);
 const api=new PortalApi(config,{storage:storage(expired),fetcher:async(url,options)=>{
  if(url.includes('refresh_token'))return response(200,{access_token:'fresh',expires_in:3600});
  assert.equal(options.body,blob);assert.equal(options.headers.Authorization,'Bearer fresh');
  assert.equal(options.headers['Content-Type'],'application/pdf');return response(200,{});
 }});
 await api.request('/storage/v1/object/club-documents/test',undefined,{method:'POST',rawBody:blob,headers:{'Content-Type':'application/pdf'}});
});
test('Rejected document registration attempts orphan cleanup without hiding the original failure',async()=>{
 const calls=[];
 const api=new PortalApi(config,{storage:storage(null),fetcher:async(url,options)=>{
  calls.push([url,options]);
  if(url.includes('register_document'))return response(400,{code:'P0001',message:'Record is locked.'});
  if(options.method==='DELETE')return response(403,{});
  return response(200,{});
 }});
 await assert.rejects(()=>api.uploadDocument(new Blob(['test'],{type:'application/pdf'}),{path:'owner/2/record/file.pdf'}),/Record is locked/);
 assert.equal(calls.length,3);assert.equal(calls[2][1].method,'DELETE');
 assert.deepEqual(JSON.parse(calls[2][1].body),{prefixes:['owner/2/record/file.pdf']});
});
test('Completed document registration is never deleted',async()=>{
 const calls=[];const api=new PortalApi(config,{storage:storage(null),fetcher:async(url,options)=>{calls.push(options.method);return response(200,{});}});
 await api.uploadDocument(new Blob(['test'],{type:'application/pdf'}),{path:'owner/2/record/file.pdf'});
 assert.deepEqual(calls,['POST','POST']);
});
test('Portal locks submitted applications and renewals, and confines partner changes to their open delegation',()=>{
 const w={user_id:'owner',permissions:[],interclub_events:[{event_id:'event',status:'preparation',registration_closes:'2026-11-01T00:00:00Z'}],event_access:[{event_id:'event',user_id:'owner',partner_id:'club',role:'partner'}]};
 const now=Date.parse('2026-10-28T00:00:00Z');
 assert.equal(canEditRecord('renewals',{user_id:'owner',status:'pending'},w,now),false);
 assert.equal(canEditRecord('applications',{user_id:'other',status:'draft'},w,now),false);
 assert.equal(canEditRecord('applications',{user_id:'owner',status:'returned_for_correction'},w,now),true);
 const row={event_id:'event',partner_id:'club',status:'pending'};
 assert.equal(canEditRecord('delegates',row,w,now),true);
 assert.equal(canEditRecord('delegates',{...row,partner_id:'other'},w,now),false);
 assert.equal(canEditRecord('delegates',row,w,Date.parse('2026-11-02')),false);
 w.event_access[0].revoked_at='2026-10-27';assert.equal(canEditRecord('delegates',row,w,now),false);
 w.permissions=['interclub'];w.interclub_events[0].status='ongoing';
 assert.equal(canEditRecord('delegates',row,w,now),false);
 assert.equal(canEditRecord('delegates',row,w,now,true),true);
 w.interclub_events[0].status='archived';assert.equal(canEditRecord('delegates',row,w,now,true),false);
});
