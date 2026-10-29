const headers={'Content-Type':'application/json'};
const reply=(status,error)=>new Response(JSON.stringify({error}),{status,headers});

// Server-side gate: hidden client buttons alone cannot prevent sending invitations.
export function createAccountHandler({url,anon,service,emailEnabled=false,fetcher=fetch}){
 return async req=>{
  if(req.method!=='POST')return reply(405,'Use POST.');
  try{
   const body=await req.text();if(body.length>6000)return reply(400,'Invalid request.');
   const {email,full_name,reason}=JSON.parse(body);
   const authorization=req.headers.get('Authorization')||'';
   if(!authorization.startsWith('Bearer '))return reply(401,'Sign in first.');
   const userHeaders={apikey:anon,Authorization:authorization,'Content-Type':'application/json'};
   const call=(path,options)=>fetcher(url+path,{...options,signal:AbortSignal.timeout(15000)});
   const access=await call('/rest/v1/rpc/club_workspace',{method:'POST',headers:userHeaders,body:'{}'});
   if(!access.ok||!(await access.json()).permissions?.includes('administration'))return reply(403,'Administrator access is required.');
   if(!emailEnabled)return reply(503,'Email invitations are not enabled yet. Use existing club accounts.');
   // The database validates and audits the request under the caller's real identity.
   const authorized=await call('/rest/v1/rpc/request_account_creation',{method:'POST',headers:userHeaders,body:JSON.stringify({p_email:email,p_full_name:full_name,p_reason:reason})});
   if(!authorized.ok)return reply(403,'Administrator access and valid account details are required.');
   const requestId=await authorized.json();
   const privileged={apikey:service,Authorization:'Bearer '+service,'Content-Type':'application/json'};
   const invited=await call('/auth/v1/invite',{method:'POST',headers:privileged,body:JSON.stringify({email:email.trim().toLowerCase(),data:{full_name}})});
   if(!invited.ok)return reply(409,'The invitation could not be sent. Check whether the account already exists and whether email delivery is configured.');
   const user=await invited.json();
   const completed=await call('/rest/v1/rpc/complete_account_creation',{method:'POST',headers:privileged,body:JSON.stringify({p_request:requestId,p_user:user.id})});
   if(!completed.ok)return reply(500,'Invitation sent, but its account record needs administrator review. Do not repeat the invitation.');
   return new Response(JSON.stringify({invited:true}),{headers});
  }catch{return reply(400,'Account invitation failed. Please review the account details and server configuration.');}
 };
}
