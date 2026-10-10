import { durationMs } from './mp4.mjs';

const headers={'Content-Type':'application/json','Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'authorization,apikey,content-type'};
Deno.serve(async(req:Request)=>{
 if(req.method==='OPTIONS')return new Response(null,{headers});
 if(req.method!=='POST')return new Response('{}',{status:405,headers});
 try {
  const url=Deno.env.get('SUPABASE_URL')!;
  const key=Deno.env.get('SUPABASE_ANON_KEY')!;
  const authorization=req.headers.get('Authorization')||'';
  if(!authorization.startsWith('Bearer '))throw Error('Sign in first');
  const authHeaders={apikey:key,Authorization:authorization};
  const identity=await fetch(url+'/auth/v1/user',{headers:authHeaders});
  if(!identity.ok)throw Error('Invalid session');
  const user=await identity.json();
  const body=await req.text();if(body.length>1000)throw Error('Invalid request');
  const {path}=JSON.parse(body);
  if(typeof path!=='string'||!new RegExp('^'+user.id+'/11/[0-9a-f-]{36}/[0-9a-f-]{36}\\.mp4$','i').test(path))throw Error('Invalid training video path');
  // The caller's JWT, not an administrator key, authorizes reading the stored file.
  const file=await fetch(url+'/storage/v1/object/authenticated/club-documents/'+path,{headers:authHeaders});
  if(!file.ok||!file.body)throw Error('Video unavailable');
  const reader=file.body.getReader(),chunks:Uint8Array[]=[];let size=0;
  while(true){const {done,value}=await reader.read();if(done)break;size+=value.length;
   if(size>20971520){await reader.cancel();throw Error('Video exceeds 20 MB');}chunks.push(value);}
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  const duration=durationMs(bytes);
  const service=Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!;
  const saved=await fetch(url+'/rest/v1/rpc/record_video_verification',{method:'POST',headers:{apikey:service,Authorization:'Bearer '+service,'Content-Type':'application/json'},body:JSON.stringify({p_path:path,p_owner:user.id,p_duration_ms:duration})});
  if(!saved.ok)throw Error('Video verification could not be recorded');
  return new Response(JSON.stringify({duration_ms:duration}),{headers});
 }catch{return new Response(JSON.stringify({error:'Video could not be verified. Use a nonfragmented MP4 clip under 10 seconds, then retry while signed in.'}),{status:400,headers});}
});
