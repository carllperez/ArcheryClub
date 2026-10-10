// Only an authenticated administrator can reach the privileged invitation operation.
const headers={'Content-Type':'application/json'};
Deno.serve(async(req:Request)=>{
 if(req.method!=='POST')return new Response('{}',{status:405,headers});
 try{
  const text=await req.text();if(text.length>6000)throw Error('Invalid request');
  const {email,full_name,reason}=JSON.parse(text);
  const url=Deno.env.get('SUPABASE_URL')!,anon=Deno.env.get('SUPABASE_ANON_KEY')!;
  const authorization=req.headers.get('Authorization')||'';
  if(!authorization.startsWith('Bearer '))throw Error('Sign in first');
  // This RPC checks verified/enabled identity and the current administration assignment.
  const authorized=await fetch(url+'/rest/v1/rpc/request_account_creation',{method:'POST',headers:{apikey:anon,Authorization:authorization,'Content-Type':'application/json'},body:JSON.stringify({p_email:email,p_full_name:full_name,p_reason:reason})});
  if(!authorized.ok)return new Response(JSON.stringify({error:'Administrator access and valid account details are required.'}),{status:403,headers});
  const requestId=await authorized.json(),service=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  const privileged={apikey:service,Authorization:'Bearer '+service,'Content-Type':'application/json'};
  const invited=await fetch(url+'/auth/v1/invite',{method:'POST',headers:privileged,body:JSON.stringify({email:email.trim().toLowerCase(),data:{full_name}})});
  if(!invited.ok)return new Response(JSON.stringify({error:'The invitation could not be sent. Check whether the account already exists and whether email delivery is configured.'}),{status:409,headers});
  const user=await invited.json();
  const completed=await fetch(url+'/rest/v1/rpc/complete_account_creation',{method:'POST',headers:privileged,body:JSON.stringify({p_request:requestId,p_user:user.id})});
  if(!completed.ok)return new Response(JSON.stringify({error:'Invitation sent, but its account record needs administrator review. Do not repeat the invitation.'}),{status:500,headers});
  return new Response(JSON.stringify({invited:true}),{headers});
 }catch{return new Response(JSON.stringify({error:'Account invitation failed. Please review the account details and server configuration.'}),{status:400,headers});}
});
