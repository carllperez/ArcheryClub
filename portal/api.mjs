/** Session transport shared by every portal operation, including private files. */
export class PortalApi {
 constructor(config,{fetcher=fetch,storage=sessionStorage,clock=()=>Date.now(),onSession=()=>{}}={}){
  this.config=config;this.fetcher=fetcher;this.storage=storage;this.clock=clock;this.onSession=onSession;
  try{this.session=JSON.parse(storage.getItem('archery-session')||'null');}catch{this.session=null;storage.removeItem('archery-session');}
  this.onSession(this.session);
 }
 saveSession(value){
  this.session=value?{...value,expires_at:value.expires_at??Math.floor(this.clock()/1000)+(value.expires_in||3600)}:null;
  if(this.session)this.storage.setItem('archery-session',JSON.stringify(this.session));else this.storage.removeItem('archery-session');
  this.onSession(this.session);
 }
 async ensureSession(){
  if(!this.session)return;
  if(this.session.expires_at*1000>this.clock()+60000)return;
  if(!this.refreshing)this.refreshing=(async()=>{
   const result=await this.raw('/auth/v1/token?grant_type=refresh_token',{refresh_token:this.session.refresh_token});
   if(!result.response.ok){
    if([400,401,403].includes(result.response.status)){this.saveSession(null);throw Error('Your session expired. Sign in again.');}
    throw Error('Session refresh is temporarily unavailable. Please retry.');
   }
   this.saveSession(result.data);
  })().finally(()=>{this.refreshing=null;});
  await this.refreshing;
 }
 async raw(path,body,options={}){
  const {rawBody,...rest}=options;
  let response;
  try{response=await this.fetcher(this.config.url+path,{method:body===undefined?'GET':'POST',...rest,
   signal:AbortSignal.timeout(30000),headers:{apikey:this.config.key,...(this.session?{Authorization:'Bearer '+this.session.access_token}:{}),
   'Content-Type':'application/json',...options.headers},body:rawBody??(body===undefined?undefined:JSON.stringify(body))});}
  catch{throw Error('Connection interrupted. Check saved records before submitting again.');}
  return {response,data:await response.json().catch(()=>({}))};
 }
 async request(path,body,options={}){
  const publicAuth=/^\/auth\/v1\/(token|signup|recover|verify)(\?|$)/.test(path);
  if(!publicAuth)await this.ensureSession();
  const {response,data}=await this.raw(path,body,options);
  if(!response.ok){
   if(response.status===401&&!publicAuth){this.saveSession(null);throw Error('Your session expired. Sign in again.');}
   const codes={invalid_credentials:'The email or password is incorrect.',email_not_confirmed:'This account needs email verification. Contact the club administrator while email setup is pending.',otp_expired:'This code is invalid or expired.'};
   const message=codes[data.error_code]||(['P0001','PT409'].includes(data.code)?data.message:null);
   throw Error(message||([401,403].includes(response.status)?'This action is not permitted for your account.':response.status===429?'Too many attempts. Wait a moment and retry.':'The request could not be completed. Refresh the record and check your entries.'));
  }
  return data;
 }
 async uploadDocument(file,metadata){
  await this.request('/storage/v1/object/club-documents/'+metadata.path,undefined,{method:'POST',rawBody:file,headers:{'Content-Type':file.type,'x-upsert':'false'}});
  try{return await this.request('/rest/v1/rpc/register_document',{p_data:metadata});}
  catch(error){
   // Storage permits deletion only of caller-owned, unattached objects. A committed
   // registration whose response was lost is protected from this cleanup by RLS.
   try{await this.request('/storage/v1/object/club-documents',{prefixes:[metadata.path]},{method:'DELETE'});}catch{}
   throw error;
  }
 }
}
