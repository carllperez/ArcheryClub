import {createAccountHandler} from './handler.mjs';

Deno.serve(createAccountHandler({
 url:Deno.env.get('SUPABASE_URL'),
 anon:Deno.env.get('SUPABASE_ANON_KEY'),
 service:Deno.env.get('SUPABASE_SERVICE_ROLE_KEY'),
 emailEnabled:Deno.env.get('ARCHERY_EMAIL_ENABLED')==='true',
}));
