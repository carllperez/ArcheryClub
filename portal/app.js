import {PortalApi} from './api.mjs';
import {canEditRecord} from './access.mjs';
const app=document.querySelector('#app'),notice=document.querySelector('#notice');
let config,session,workspace,catalog,api,busy=false;
const text=(tag,value,parent=app)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
const human=s=>s.replaceAll('_',' ').replace(/^./,s=>s.toUpperCase());
const button=(label,action,parent=app)=>{const b=text('button',label,parent);b.type='button';b.onclick=()=>run(action);return b;};
function input(label,type='text',parent=app,value=''){const l=text('label',label,parent),e=document.createElement(type==='textarea'?'textarea':'input');if(type!=='textarea')e.type=type;e.value=value;l.append(e);return e;}
function tell(message){notice.textContent=message;}
async function run(action){if(busy)return;busy=true;document.querySelectorAll('button').forEach(b=>b.disabled=true);tell('');try{await action();}catch(e){if(!session&&workspace){workspace=null;auth();}tell(e.message||'Request failed. Please retry.');}finally{busy=false;document.querySelectorAll('button').forEach(b=>b.disabled=false);}}
const request=(path,body,options={})=>api.request(path,body,options);
const rpc=(name,body={})=>request('/rest/v1/rpc/'+name,body);
const saveSession=value=>api.saveSession(value);
async function refresh(){
 try {
  await rpc('initialize_account');workspace=await rpc('club_workspace');home();
 }catch(e){workspace=null;app.replaceChildren();if(session){button('Retry connection',refresh);button('Sign out',logout);}else auth();throw e;}
}
async function command(action,data){await rpc('club_command',{p_action:action,p_data:data});try{await refresh();tell('Saved to the club system.');}catch{tell('Saved successfully. Refresh to load the updated records; do not submit it again.');}}
async function logout(){try{await request('/auth/v1/logout?scope=local',{});}finally{saveSession(null);workspace=null;auth();}}
function auth(){
 app.replaceChildren();text('h1','Welcome to your club');text('p','Apply for membership, follow your screening status, or coordinate an event with your partner club.');
 const form=text('form',''),email=input('Email','email',form),password=input('Password','password',form);password.autocomplete='current-password';email.autocomplete='email';email.required=true;password.required=true;
 form.onsubmit=e=>{e.preventDefault();run(async()=>{saveSession(await request('/auth/v1/token?grant_type=password',{email:email.value.trim(),password:password.value}));password.value='';await refresh();});};
 const submit=text('button','Sign in',form);submit.type='submit';
 if(config.emailEnabled){
 button('Create account',async()=>{if(password.value.length<8)throw Error('Use at least 8 characters.');await request('/auth/v1/signup',{email:email.value.trim(),password:password.value});password.value='';tell('Check your email, then enter its verification code below.');},form);
 const code=input('Email verification or recovery code','text',form);code.autocomplete='one-time-code';
 const typeLabel=text('label','Code type',form),codeType=document.createElement('select');typeLabel.append(codeType);
 for(const [value,label] of [['signup','New account'],['recovery','Password recovery'],['invite','Officer invitation']]){const option=text('option',label,codeType);option.value=value;}
 button('Verify code',async()=>{saveSession(await request('/auth/v1/verify',{email:email.value.trim(),token:code.value.trim(),type:codeType.value}));await refresh();if(codeType.value!=='signup')passwordForm();},form);
 button('Send recovery email',async()=>{await request('/auth/v1/recover',{email:email.value.trim()});tell('If this account exists, a recovery email has been sent.');},form);
 }else text('p','Use an existing club account. Email signup, recovery and invitations are not enabled yet.',form);
}
function passwordForm(){const f=text('form','');const p=input('New password','password',f);button('Save new password',async()=>{if(p.value.length<8)throw Error('Use at least 8 characters.');await request('/auth/v1/user',{password:p.value},{method:'PUT'});p.value='';await refresh();tell('Password updated.');},f);}
const rows=table=>workspace[table]||[];
const title=row=>row.full_name||row.shared_title||row.title||row.message||row.name||human(row.status||'Record');
function details(row,parent){const dl=text('dl','',parent);for(const [key,value]of Object.entries(row))if(value!=null&&typeof value!=='object'&&!['id','user_id','owner_id','path','token','version'].includes(key)&&!key.endsWith('_id')){text('dt',human(key),dl);text('dd',String(value),dl);}}
function home(){
 app.replaceChildren();text('h1',rows('settings')[0]?.club_name||'Archery Club');
 button('Refresh',refresh);button('Sign out',logout);
 if(!rows('settings')[0]?.rules_validated)text('p','Club configuration is awaiting validation.');
 const membership=rows('memberships').find(m=>m.user_id===workspace.user_id);
 text('h2','M2 · Applicant Submission and Screening Status');
 const ownApplication=rows('applications').find(r=>r.user_id===workspace.user_id);
 if(!membership&&!ownApplication)button('Save application draft',()=>edit('save_application',{}));
 for(const row of rows('applications').filter(r=>r.user_id===workspace.user_id))record('applications',row,['save_application','submit_application'],2);
 if(membership){text('h2','M1 · Member Profile and Status');details(membership,app);
  button('Edit my profile',()=>edit('save_profile',rows('profiles').find(p=>p.id===workspace.user_id)||{}));
  const renewal=rows('renewals').find(r=>r.user_id===workspace.user_id&&['draft','returned_for_correction','pending'].includes(r.status));
  if(renewal?.status!=='pending')button('Save renewal draft',()=>edit('save_renewal',renewal||{}));
  for(const row of rows('renewals').filter(r=>r.user_id===workspace.user_id))record('renewals',row,['save_renewal','request_renewal'],1);
 }
 if(rows('event_access').some(a=>a.user_id===workspace.user_id&&!a.revoked_at)||workspace.permissions.includes('interclub')){
  text('h2','M15 · Shared Interclub Event Coordination');
  button('Register own delegation',()=>edit('save_delegate',{}));button('Raise coordination concern',()=>edit('raise_concern',{}));
  for(const row of rows('interclub_events'))record('interclub_events',row,[],15);
  for(const row of rows('delegates'))record('delegates',row,['save_delegate'],15);
  for(const table of ['shared_participants','interclub_assignments','interclub_concerns','interclub_results','interclub_archives']){
   text('h3',human(table));for(const row of rows(table))record(table,row,[],null);
  }
 }
 text('h2','Notifications');for(const row of rows('notifications')){const section=text('section','');text('p',row.message,section);if(!row.acknowledged_at)button('Acknowledge',()=>command('acknowledge_notification',{id:row.id}),section);}
 text('p','Club officers use the Android application for their assigned internal workflows. This portal provides browser access for applicants and partner representatives.').className='quiet';
}
function record(table,row,actions,module){const section=text('section','');text('h3',title(row),section);details(row,section);
 for(const action of actions){const f=catalog.forms[action];if(canEditRecord(table,row,workspace)&&(!f.states.length||f.states.includes(row.status)))button(f.label,()=>edit(action,row),section);}
 if(module){for(const d of rows('documents').filter(d=>d.record_id===(row.id||row.event_id)))button('Open '+d.name,async()=>{
  const result=await request('/storage/v1/object/sign/club-documents/'+d.path,{expiresIn:300});
  const a=text('a','Open '+d.name,section);a.href=config.url+'/storage/v1'+result.signedURL;a.target='_blank';a.rel='noopener';a.click();
 },section);
 if(canEditRecord(table,row,workspace,Date.now(),true)){
 const requirement=input('Requirement name','text',section),file=input('Supporting file','file',section);file.accept='.pdf,.png,.jpg,.jpeg';
 button('Upload document',async()=>{const f=file.files[0];if(!f||!requirement.value.trim())throw Error('Choose a file and enter its requirement name.');
  if(!['application/pdf','image/jpeg','image/png'].includes(f.type)||f.size<1||f.size>20971520)throw Error('Choose PDF, JPG or PNG up to 20 MB.');
  const path=`${workspace.user_id}/${module}/${row.id}/${crypto.randomUUID()}.${f.type==='application/pdf'?'pdf':f.type==='image/png'?'png':'jpg'}`;
  await api.uploadDocument(f,{module,record_id:row.id,name:f.name,requirement:requirement.value.trim(),path});
  file.value='';try{await refresh();tell('Document uploaded.');}catch{tell('Document uploaded. Refresh to load it; do not upload it again.');}
 },section);}}
}
function edit(action,row){const f=catalog.forms[action];let dirty=false;app.replaceChildren();text('h1',f.label);button('Back',()=>{if(!dirty||confirm('Discard your unsaved changes?'))home();});if(f.help)text('p',f.help);
 const form=text('form',''),fields=[];
 form.addEventListener('input',()=>{dirty=true;});form.addEventListener('change',()=>{dirty=true;});
 for(const field of f.fields){let control;const value=row[field.name]??'';
  if(['choice','record'].includes(field.type)){const label=text('label',field.label,form);control=document.createElement('select');label.append(control);
   const options=field.type==='choice'?field.options.map(v=>[v,human(v)]):rows(field.source).map(r=>[field.source==='interclub_events'?r.event_id:field.source==='member_directory'?r.user_id:r.id,title(r)]);
   for(const [id,name]of [['','Select'],...options]){const o=text('option',name,control);o.value=id;}control.value=value;
  }else{control=input(field.label+(field.optional?' (optional)':''),field.type==='boolean'?'checkbox':field.type==='long'?'textarea':field.type==='date'?'date':'text',form,String(value));if(field.type==='boolean')control.checked=value===true;}
  control.required=!field.optional&&field.type!=='boolean';fields.push([field,control]);
 }
 const submit=text('button',f.label,form);submit.type='submit';
 form.onsubmit=e=>{e.preventDefault();run(async()=>{const data={};for(const key of ['id','version','user_id','event_id'])if(row[key]!=null)data[key]=row[key];
  for(const [field,control]of fields)data[field.name]=field.type==='boolean'?control.checked:control.value.trim()||null;
  await command(action,data);
 });};
}
try{
 ({config}=await import('./config.js'));if(!config.url.startsWith('https://')||!config.key.startsWith('sb_publishable_'))throw Error('Configure the project URL and publishable key before using this portal.');
 catalog=await fetch('./modules.json').then(r=>r.json());api=new PortalApi(config,{onSession:value=>{session=value;}});
 if(session)await run(refresh);else auth();
}catch(e){text('h1','Portal setup required');tell(e.message);}
