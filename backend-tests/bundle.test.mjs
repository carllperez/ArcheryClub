// The single-file database script (supabase/deploy-development.sql, made by tools/build_sql_bundle.py) must build the
// same database as running every migration in order: it is the "Database Creation Script" in the capstone files.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {PGlite} from '@electric-sql/pglite';
import {readFile,readdir} from 'node:fs/promises';
// Supabase provides these schemas; PGlite needs small stand-ins (same as workflow.test.mjs).
const platform=`create role anon; create role authenticated; create role service_role; create schema auth; create schema storage;
create table auth.users(id uuid primary key,email text,email_confirmed_at timestamptz);
create function auth.uid() returns uuid language sql stable as $$ select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid $$;
grant usage on schema auth to authenticated; grant execute on function auth.uid() to authenticated;
create table storage.buckets(id text primary key,name text,public boolean,file_size_limit bigint,allowed_mime_types text[]);
create table storage.objects(id uuid primary key default gen_random_uuid(),bucket_id text,name text,owner_id text,metadata jsonb);
alter table storage.objects enable row level security; grant usage on schema storage to authenticated; grant select,insert,update,delete on storage.objects to authenticated;`;
const shape=async db=>({
 tables:(await db.query(`select table_schema||'.'||table_name as t from information_schema.tables where table_schema in ('app_private','public') order by 1`)).rows.map(r=>r.t),
 functions:(await db.query(`select n.nspname||'.'||p.proname as f from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname in ('app_private','public') order by 1`)).rows.map(r=>r.f),
});
test('deploy-development.sql builds the same database as the migrations, in one transaction',async()=>{
 const bundle=await readFile(new URL('../supabase/deploy-development.sql',import.meta.url),'utf8');
 const files=(await readdir(new URL('../supabase/migrations/',import.meta.url))).filter(x=>x.endsWith('.sql')).sort();
 assert.equal((bundle.match(/^-- SOURCE: /gm)||[]).length,files.length,'Regenerate with: python3 tools/build_sql_bundle.py');
 assert.equal((bundle.match(/^\s*(begin|commit);\s*$/gim)||[]).length,2,'Exactly one begin; and one commit;');
 const fromBundle=new PGlite();await fromBundle.exec(platform);await fromBundle.exec(bundle);
 const fromMigrations=new PGlite();await fromMigrations.exec(platform);
 for(const f of files)await fromMigrations.exec(await readFile(new URL('../supabase/migrations/'+f,import.meta.url),'utf8'));
 const a=await shape(fromBundle),b=await shape(fromMigrations);
 assert.deepEqual(a,b);
 assert.ok(a.tables.length>=40,`only ${a.tables.length} tables`);
 // A first sign-in sets up the account, as the app does.
 const id='00000000-0000-4000-8000-0000000000aa';
 await fromBundle.query('insert into auth.users values($1,$2,now())',[id,'first@example.test']);
 await fromBundle.query("select set_config('request.jwt.claim.sub',$1,false)",[id]);
 await fromBundle.exec('set role authenticated');await fromBundle.exec('select public.initialize_account()');
 const ws=(await fromBundle.query('select public.club_workspace() as w')).rows[0].w;
 assert.equal(ws.user_id,id);
});
