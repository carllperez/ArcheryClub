// UI eligibility only. The backend independently authorizes every operation.
export function canEditRecord(table,row,workspace,now=Date.now(),upload=false){
 if(table==='applications'||table==='renewals')return row.user_id===workspace.user_id&&(table==='applications'?['draft','incomplete','returned_for_correction']:['draft','returned_for_correction']).includes(row.status);
 if(table!=='delegates')return false;
 const event=(workspace.interclub_events||[]).find(e=>e.event_id===row.event_id);
 const grants=(workspace.event_access||[]).filter(a=>a.user_id===workspace.user_id&&a.event_id===row.event_id&&!a.revoked_at);
 if(!event||event.status==='archived')return false;
 const staff=workspace.permissions.includes('interclub')||grants.some(a=>a.role==='coordinator');
 if(upload&&staff)return true;
 return event.status==='preparation'&&Date.parse(event.registration_closes)>=now&&['pending','incomplete'].includes(row.status)&&(staff||grants.some(a=>a.partner_id===row.partner_id));
}
