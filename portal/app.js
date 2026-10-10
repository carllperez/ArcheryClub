const app=document.querySelector('#app'),notice=document.querySelector('#notice');
let config,session,workspace,catalog,busy=false;
const text=(tag,value,parent=app)=>{const e=document.createElement(tag);e.textContent=value;parent.append(e);return e;};
const human=s=>s.replaceAll('_',' ').replace(/^./,s=>s.toUpperCase());
const button=(label,action,parent=app)=>{const b=text('button',label,parent);b.type='button';b.onclick=()=>run(action);return b;};
function input(label,type='text',parent=app,value=''){const l=text('label',label,parent),e=document.createElement(type==='textarea'?'textarea':'input');if(type!=='textarea')e.type=type;e.value=value;l.append(e);return e;}
function tell(message){notice.textContent=message;}
async function run(action){if(busy)return;busy=true;document.querySelectorAll('button').forEach(b=>b.disabled=true);tell('');try{await action();}catch(e){tell(e.message||'Request failed. Please retry.');}finally{busy=false;document.querySelectorAll('button').forEach(b=>b.disabled=false);}}
async function request(path,body,options={}){
 const response=await fetch(config.url+path,{method:body===undefined?'GET':'POST',...options,headers:{apikey:config.key,...(session?{Authorization:'Bearer '+session.access_token}:{}),'Content-Type':'application/json',...options.headers},body:body===undefined?undefined:JSON.stringify(body)});
 const result=await response.json().catch(()=>({}));if(!response.ok)throw Error(result.msg||result.message||result.error_description||result.error||'Request failed');return result;
}
const rpc=(name,body={})=>request('/rest/v1/rpc/'+name,body);
function saveSession(value){session=value?{...value,expires_at:value.expires_at??Math.floor(Date.now()/1000)+(value.expires_in||3600)}:null;if(session)sessionStorage.setItem('archery-session',JSON.stringify(session));else sessionStorage.removeItem('archery-session');}
async function refresh(){
 try {
  if(session.expires_at*1000<Date.now()+60000)saveSession(await request('/auth/v1/token?grant_type=refresh_token',{refresh_token:session.refresh_token}));
  await rpc('initialize_account');workspace=await rpc('club_workspace');home();
 }catch(e){workspace=null;app.replaceChildren();button('Retry connection',refresh);button('Sign out',logout);throw e;}
}
async function command(action,data){await rpc('club_command',{p_action:action,p_data:data});await refresh();tell('Saved to the club system.');}
async function logout(){try{await request('/auth/v1/logout',{});}finally{saveSession(null);workspace=null;auth();}}
function auth(){
 app.replaceChildren();text('h1','Welcome to your club');text('p','Apply for membership, follow your screening status, or coordinate an event with your partner club.');
 const form=text('form',''),email=input('Email','email',form),password=input('Password','password',form);password.autocomplete='current-password';email.autocomplete='email';
 form.onsubmit=e=>{e.preventDefault();run(async()=>{saveSession(await request('/auth/v1/token?grant_type=password',{email:email.value.trim(),password:password.value}));password.value='';await refresh();});};
 const submit=text('button','Sign in',form);submit.type='submit';
 button('Create account',async()=>{if(password.value.length<8)throw Error('Use at least 8 characters.');await request('/auth/v1/signup',{email:email.value.trim(),password:password.value});password.value='';tell('Check your email, then enter its verification code below.');},form);
 const code=input('Email verification or recovery code','text',form);code.autocomplete='one-time-code';
 const typeLabel=text('label','Code type',form),codeType=document.createElement('select');typeLabel.append(codeType);
 for(const [value,label] of [['signup','New account'],['recovery','Password recovery'],['invite','Officer invitation']]){const option=text('option',label,codeType);option.value=value;}
 button('Verify code',async()=>{saveSession(await request('/auth/v1/verify',{email:email.value.trim(),token:code.value.trim(),type:codeType.value}));await refresh();if(codeType.value!=='signup')passwordForm();},form);
 button('Send recovery email',async()=>{await request('/auth/v1/recover',{email:email.value.trim()});tell('If this account exists, a recovery email has been sent.');},form);
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
 if(!membership)button('Save application draft',()=>edit('save_application',rows('applications')[0]||{}));
 for(const row of rows('applications').filter(r=>r.user_id===workspace.user_id))record('applications',row,['save_application','submit_application'],2);
 if(membership){text('h2','M1 · Member Profile and Status');details(membership,app);
  button('Edit my profile',()=>edit('save_profile',rows('profiles').find(p=>p.id===workspace.user_id)||{}));
  button('Save renewal draft',()=>edit('save_renewal',rows('renewals').find(r=>['draft','returned_for_correction'].includes(r.status))||{}));
  for(const row of rows('renewals'))record('renewals',row,['save_renewal','request_renewal'],1);
 }
 if(rows('event_access').some(a=>a.user_id===workspace.user_id&&!a.revoked_at)||workspace.permissions.includes('interclub')){
  text('h2','M15 · Shared Interclub Event Coordination');
  button('Register own delegation',()=>edit('save_delegate',{}));button('Raise coordination concern',()=>edit('raise_concern',{}));
  for(const row of rows('interclub_events'))record('interclub_events',row,[],null);
  for(const row of rows('delegates'))record('delegates',row,['save_delegate'],15);
  for(const table of ['shared_participants','interclub_assignments','interclub_concerns','interclub_results']){
   text('h3',human(table));for(const row of rows(table))record(table,row,[],null);
  }
 }
 text('h2','Notifications');for(const row of rows('notifications')){const section=text('section','');text('p',row.message,section);if(!row.acknowledged_at)button('Acknowledge',()=>command('acknowledge_notification',{id:row.id}),section);}
 text('p','Club officers use the Android application for their assigned internal workflows. This portal provides browser access for applicants and partner representatives.').className='quiet';
}
function record(table,row,actions,module){const section=text('section','');text('h3',title(row),section);details(row,section);
 for(const action of actions){const f=catalog.forms[action];if(!f.states.length||f.states.includes(row.status))button(f.label,()=>edit(action,row),section);}
 if(module){for(const d of rows('documents').filter(d=>d.record_id===row.id))button('Open '+d.name,async()=>{
  const result=await request('/storage/v1/object/sign/club-documents/'+d.path,{expiresIn:300});window.open(config.url+'/storage/v1'+result.signedURL,'_blank','noopener');
 },section);
 if(['draft','incomplete','returned_for_correction','pending'].includes(row.status)){
 const requirement=input('Requirement name','text',section),file=input('Supporting file','file',section);file.accept='.pdf,.png,.jpg,.jpeg';
 button('Upload document',async()=>{const f=file.files[0];if(!f||!requirement.value.trim())throw Error('Choose a file and enter its requirement name.');
  if(!['application/pdf','image/jpeg','image/png'].includes(f.type)||f.size<1||f.size>20971520)throw Error('Choose PDF, JPG or PNG up to 20 MB.');
  const path=`${workspace.user_id}/${module}/${row.id}/${crypto.randomUUID()}.${f.type==='application/pdf'?'pdf':f.type==='image/png'?'png':'jpg'}`;
  const result=await fetch(config.url+'/storage/v1/object/club-documents/'+path,{method:'POST',headers:{apikey:config.key,Authorization:'Bearer '+session.access_token,'Content-Type':f.type,'x-upsert':'false'},body:f});
  if(!result.ok)throw Error('Upload denied. Refresh the record and check whether it is editable.');
  await rpc('register_document',{p_data:{module,record_id:row.id,name:f.name,requirement:requirement.value.trim(),path}});await refresh();tell('Document uploaded.');
 },section);}}
}
function edit(action,row){const f=catalog.forms[action];app.replaceChildren();text('h1',f.label);button('Back',home);if(f.help)text('p',f.help);
 const form=text('form',''),fields=[];
 for(const field of f.fields){let control;const value=row[field.name]??'';
  if(['choice','record'].includes(field.type)){const label=text('label',field.label,form);control=document.createElement('select');label.append(control);
   const options=field.type==='choice'?field.options.map(v=>[v,human(v)]):rows(field.source).map(r=>[field.source==='interclub_events'?r.event_id:r.id,title(r)]);
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
 catalog=await fetch('./modules.json').then(r=>r.json());session=JSON.parse(sessionStorage.getItem('archery-session')||'null');
 if(session)await run(refresh);else auth();
}catch(e){text('h1','Portal setup required');tell(e.message);}
