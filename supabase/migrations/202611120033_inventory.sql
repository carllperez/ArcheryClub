-- Inventory the club sets up itself, like its Inventory spreadsheet.
--   Categories (Riser, Limbs, Sight…) each have a colour and their own fields: text, a choice from a list,
--   a number, or a range such as 68-66. A field can show on the inventory list or only on the item page.
--   A category can be borrowable or not, labelled piece by piece or just counted, and matched to the archer.
--   Places are where things are kept (each spreadsheet tab: Tall White Cabinet, Short White Cabinet (Left)…).
--   Items keep their existing columns, so borrowing keeps working; the new columns hold the item name,
--   category, place, quantity, owner and the category's field values. The server fills label, category,
--   specifications, handedness and draw weight from them.
-- Who: anyone with the Equipment desk, and the Administrator.
begin;

create table app_private.inventory_categories (
 id uuid primary key default gen_random_uuid(),
 name text not null unique check(length(trim(name)) between 1 and 60),
 color text not null default 'green' check(color in ('red','orange','yellow','green','blue','teal','purple','gray')),
 fields jsonb not null default '[]',
 borrowable boolean not null default true,
 labelled boolean not null default true,
 match_archer boolean not null default false,
 position integer not null default 0,
 version bigint not null default 1
);
create table app_private.inventory_places (
 id uuid primary key default gen_random_uuid(),
 name text not null unique check(length(trim(name)) between 1 and 80),
 position integer not null default 0,
 version bigint not null default 1
);
alter table app_private.equipment
 add column name text not null default '',
 add column category_id uuid references app_private.inventory_categories,
 add column place_id uuid references app_private.inventory_places,
 add column quantity integer not null default 1 check(quantity between 1 and 9999),
 add column owner text not null default 'Club property' check(length(trim(owner)) between 1 and 120),
 add column details jsonb not null default '{}';

alter table app_private.inventory_categories enable row level security;
alter table app_private.inventory_places enable row level security;
revoke all on app_private.inventory_categories, app_private.inventory_places from public,anon,authenticated;
grant select on app_private.inventory_categories, app_private.inventory_places to authenticated;
create policy inventory_categories_read on app_private.inventory_categories for select to authenticated using(app_private.member() or app_private.can('equipment') or app_private.can('administration'));
create policy inventory_places_read on app_private.inventory_places for select to authenticated using(app_private.member() or app_private.can('equipment') or app_private.can('administration'));

-- Starting set taken from the club's Inventory (26-27) sheet; every club can rename, recolour or delete them.
insert into app_private.inventory_categories(name,color,fields,borrowable,labelled,match_archer,position) values
 ('Riser','red','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"sight","label":"Sight","type":"text","options":[],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":true},{"key":"length","label":"Length (in.)","type":"number","options":[],"on_list":true}]',true,false,true,1),
 ('Limbs','orange','[{"key":"label","label":"Label","type":"text","options":[],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"length","label":"Length (in.)","type":"range","options":[],"on_list":true},{"key":"lbs","label":"Draw weight (lbs.)","type":"range","options":[],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":false}]',true,true,true,2),
 ('Sight','yellow','[{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"color","label":"Color","type":"text","options":[],"on_list":true},{"key":"pins","label":"Pins","type":"text","options":[],"on_list":true}]',true,false,false,3),
 ('Finger Tab','green','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true},{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true},{"key":"kind","label":"Leather/Plate","type":"choice","options":["Leather","Plate"],"on_list":true}]',true,false,true,4),
 ('Stringers','blue','[]',true,false,false,5),
 ('Quiver','teal','[{"key":"hand","label":"Hand","type":"choice","options":["RH","LH"],"on_list":true}]',true,false,false,6),
 ('Arm/Chest Guard','purple','[{"key":"size","label":"Size","type":"choice","options":["S","M","L"],"on_list":true}]',true,false,false,7),
 ('Arrows','gray','[{"key":"length","label":"Length (in.)","type":"number","options":[],"on_list":true},{"key":"spine","label":"Spine","type":"text","options":[],"on_list":true}]',true,false,false,8);
insert into app_private.inventory_places(name,position) values
 ('Arrows',1),('Tall White Cabinet',2),('Short White Cabinet (Left)',3),('Short White Cabinet (Right)',4),('Benel Cabinet (Left)',5);

create function app_private.require_inventory() returns uuid language plpgsql stable security definer set search_path='' as $$
declare u uuid:=app_private.require_user();
begin
 if not (app_private.can('equipment') or app_private.can('administration')) then
  raise exception 'Only the Equipment desk or the Administrator can change the inventory.' using errcode='42501'; end if;
 return u;
end $$;
revoke all on function app_private.require_inventory() from public,anon,authenticated;

-- Checks a category's field list and returns it cleaned: keys made from labels, unique labels, known types.
create function app_private.clean_fields(p jsonb) returns jsonb language plpgsql immutable set search_path='' as $$
declare f jsonb; v_out jsonb:='[]'; v_label text; v_key text; v_type text; v_opts jsonb; seen text[]:='{}'; keys text[]:='{}';
begin
 if jsonb_typeof(coalesce(p,'[]')) <> 'array' or jsonb_array_length(coalesce(p,'[]')) > 12 then raise exception 'A category can have up to 12 fields.'; end if;
 for f in select value from jsonb_array_elements(coalesce(p,'[]')) loop
  v_label:=trim(coalesce(f->>'label',''));
  if length(v_label) not between 1 and 40 then raise exception 'Each field needs a name of up to 40 characters.'; end if;
  if lower(v_label) = any(seen) then raise exception 'Two fields are both called %.',v_label; end if;
  seen:=seen||lower(v_label);
  v_type:=coalesce(f->>'type','text');
  if v_type not in ('text','choice','number','range') then raise exception 'Field types are text, choice, number or range.'; end if;
  v_opts:=coalesce(f->'options','[]');
  if v_type='choice' then
   -- Drop blanks and repeats, keeping the order the club typed (S, M, L).
   select coalesce(jsonb_agg(o order by first_n),'[]') into v_opts from (
    select trim(t.o) o,min(t.n) first_n from jsonb_array_elements_text(v_opts) with ordinality t(o,n) where trim(t.o)<>'' group by trim(t.o)) s;
   if jsonb_array_length(v_opts) not between 1 and 30 then raise exception '% needs 1 to 30 choices.',v_label; end if;
  else v_opts:='[]'; end if;
  -- Keep a field's key when it is renamed so items keep their values; new fields get one from the name.
  v_key:=coalesce(nullif(f->>'key',''),trim(both '_' from regexp_replace(lower(v_label),'[^a-z0-9]+','_','g')));
  if v_key='' then v_key:='field'; end if;
  while v_key = any(keys) loop v_key:=v_key||'_2'; end loop;
  keys:=keys||v_key;
  v_out:=v_out||jsonb_build_array(jsonb_build_object('key',v_key,'label',v_label,'type',v_type,'options',v_opts,'on_list',coalesce((f->>'on_list')::boolean,true)));
 end loop;
 return v_out;
end $$;
revoke all on function app_private.clean_fields(jsonb) from public,anon,authenticated;

create function public.save_inventory_category(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare c app_private.inventory_categories; v_id uuid; v_fields jsonb:=app_private.clean_fields(p_data->'fields');
begin
 perform app_private.require_inventory();
 if nullif(p_data->>'id','') is null then
  insert into app_private.inventory_categories(name,color,fields,borrowable,labelled,match_archer,position)
  values(app_private.required(p_data,'name',60),coalesce(nullif(p_data->>'color',''),'green'),v_fields,
   coalesce((p_data->>'borrowable')::boolean,true),coalesce((p_data->>'labelled')::boolean,false),coalesce((p_data->>'match_archer')::boolean,false),
   (select coalesce(max(position),0)+1 from app_private.inventory_categories)) returning id into v_id;
 else
  select * into c from app_private.inventory_categories where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This category no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(c.version,p_data);
  update app_private.inventory_categories set name=app_private.required(p_data,'name',60),color=coalesce(nullif(p_data->>'color',''),color),fields=v_fields,
   borrowable=coalesce((p_data->>'borrowable')::boolean,borrowable),labelled=coalesce((p_data->>'labelled')::boolean,labelled),
   match_archer=coalesce((p_data->>'match_archer')::boolean,match_archer),version=version+1 where id=c.id;
  -- Items keep the category's name in their own category column for older screens.
  update app_private.equipment set category=app_private.required(p_data,'name',60) where category_id=c.id;
  v_id:=c.id;
 end if;
 perform app_private.note(10,v_id,'save_inventory_category',p_data);
 return jsonb_build_object('id',v_id);
exception when unique_violation then raise exception 'Another category already has this name.';
end $$;

create function public.save_inventory_place(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare pl app_private.inventory_places; v_id uuid;
begin
 perform app_private.require_inventory();
 if nullif(p_data->>'id','') is null then
  insert into app_private.inventory_places(name,position) values(app_private.required(p_data,'name',80),(select coalesce(max(position),0)+1 from app_private.inventory_places)) returning id into v_id;
 else
  select * into pl from app_private.inventory_places where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This place no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(pl.version,p_data);
  update app_private.inventory_places set name=app_private.required(p_data,'name',80),version=version+1 where id=pl.id; v_id:=pl.id;
 end if;
 perform app_private.note(10,v_id,'save_inventory_place',p_data);
 return jsonb_build_object('id',v_id);
exception when unique_violation then raise exception 'Another place already has this name.';
end $$;

-- Order categories or places as listed.
create function public.order_inventory(p_kind text,p_ids uuid[]) returns jsonb
language plpgsql security definer set search_path='' as $$
begin
 perform app_private.require_inventory();
 if p_kind='category' then
  update app_private.inventory_categories c set position=o.n from unnest(p_ids) with ordinality o(id,n) where c.id=o.id;
 elsif p_kind='place' then
  update app_private.inventory_places pl set position=o.n from unnest(p_ids) with ordinality o(id,n) where pl.id=o.id;
 else raise exception 'Unknown list.'; end if;
 return jsonb_build_object('ordered',coalesce(array_length(p_ids,1),0));
end $$;

-- Only empty categories and places can go; otherwise the items would lose where they belong.
create function public.delete_inventory_group(p_kind text,p_id uuid) returns jsonb
language plpgsql security definer set search_path='' as $$
declare n integer; v_row jsonb;
begin
 perform app_private.require_inventory();
 if p_kind='category' then
  select count(*) into n from app_private.equipment where category_id=p_id;
  if n>0 then raise exception 'Move or delete its % item(s) first.',n; end if;
  delete from app_private.inventory_categories c where c.id=p_id returning to_jsonb(c) into v_row;
 elsif p_kind='place' then
  select count(*) into n from app_private.equipment where place_id=p_id;
  if n>0 then raise exception 'Move its % item(s) somewhere else first.',n; end if;
  delete from app_private.inventory_places pl where pl.id=p_id returning to_jsonb(pl) into v_row;
 else raise exception 'Unknown list.'; end if;
 if v_row is null then raise exception 'It no longer exists. Refresh and try again.'; end if;
 perform app_private.note(10,p_id,'delete_inventory_'||p_kind,jsonb_build_object('deleted',v_row));
 return jsonb_build_object('deleted',p_id);
end $$;

-- Save one item from a category's fields. Returns its id.
create function app_private.save_inventory_item(u uuid,p_data jsonb) returns uuid
language plpgsql security definer set search_path='' as $$
declare c app_private.inventory_categories; e app_private.equipment; f jsonb; v_details jsonb:='{}'; v_val text; v_key text;
 v_name text:=app_private.required(p_data,'name',120); v_label text; v_base text; n integer:=1; v_hand text:='not_applicable';
 v_weight numeric; v_specs text:=''; v_id uuid; v_place uuid:=nullif(p_data->>'place_id','')::uuid;
 v_condition text:=coalesce(nullif(p_data->>'condition',''),'serviceable'); v_qty integer;
begin
 select * into c from app_private.inventory_categories where id=nullif(p_data->>'category_id','')::uuid;
 if not found then raise exception 'Pick a category for the item.'; end if;
 if v_place is not null and not exists(select 1 from app_private.inventory_places where id=v_place) then raise exception 'That place no longer exists.'; end if;
 if v_condition not in ('serviceable','maintenance','damaged','retired') then raise exception 'Unknown condition.'; end if;
 v_qty:=coalesce(nullif(p_data->>'quantity','')::integer,1);
 if v_qty not between 1 and 9999 then raise exception 'Quantity is 1 to 9999.'; end if;
 if c.labelled and v_qty<>1 then raise exception '% are labelled one by one, so each piece is its own item with quantity 1.',c.name; end if;
 for f in select value from jsonb_array_elements(c.fields) loop
  v_key:=f->>'key'; v_val:=trim(coalesce(p_data->'details'->>v_key,''));
  if v_val='' then continue; end if;
  if length(v_val)>80 then raise exception '% is too long.',f->>'label'; end if;
  if f->>'type'='choice' and not (f->'options' ? v_val) then raise exception '% must be one of: %.',f->>'label',(select string_agg(o,', ') from jsonb_array_elements_text(f->'options') o); end if;
  if f->>'type'='number' and v_val !~ '^\d+(\.\d+)?$' then raise exception '% must be a number.',f->>'label'; end if;
  if f->>'type'='range' and v_val !~ '^\d+(\.\d+)?(\s*-\s*\d+(\.\d+)?)?$' then raise exception '% must be a number or a range like 68-66.',f->>'label'; end if;
  v_details:=v_details||jsonb_build_object(v_key,v_val);
  v_specs:=v_specs||case when v_specs='' then '' else ' · ' end||(f->>'label')||': '||v_val;
  -- Hand and draw weight feed the existing matching for borrowers.
  if lower(f->>'label') in ('hand','handedness') then
   v_hand:=case when upper(v_val) in ('RH','RIGHT','R') then 'right' when upper(v_val) in ('LH','LEFT','L') then 'left' else v_hand end; end if;
  if lower(f->>'label') like '%lb%' or lower(f->>'label') like '%weight%' then
   select max(t.x[1]::numeric) into v_weight from regexp_matches(v_val,'(\d+(?:\.\d+)?)','g') as t(x); end if;
 end loop;
 -- The label is what borrowers and officers see; it must be unique, so repeats get #2, #3…
 v_base:=trim(v_name||coalesce(' '||nullif(v_details->>'label',''),'')||case when v_hand='right' then ' (RH)' when v_hand='left' then ' (LH)' else '' end);
 v_label:=v_base;
 while exists(select 1 from app_private.equipment where label=v_label and id is distinct from nullif(p_data->>'id','')::uuid) loop
  n:=n+1; v_label:=v_base||' #'||n; end loop;
 if nullif(p_data->>'id','') is null then
  insert into app_private.equipment(label,category,specifications,handedness,draw_weight,bow_setup,condition,acquired_on,notes,name,category_id,place_id,quantity,owner,details)
  values(v_label,c.name,coalesce(nullif(v_specs,''),c.name),v_hand,v_weight,'',v_condition,nullif(p_data->>'acquired_on','')::date,coalesce(p_data->>'notes',''),
   v_name,c.id,v_place,v_qty,coalesce(nullif(trim(p_data->>'owner'),''),'Club property'),v_details) returning id into v_id;
 else
  select * into e from app_private.equipment where id=(p_data->>'id')::uuid for update;
  if not found then raise exception 'This item no longer exists. Refresh and try again.'; end if;
  perform app_private.check_version(e.version,p_data);
  update app_private.equipment set label=v_label,category=c.name,specifications=coalesce(nullif(v_specs,''),c.name),handedness=v_hand,draw_weight=v_weight,
   condition=v_condition,acquired_on=coalesce(nullif(p_data->>'acquired_on','')::date,acquired_on),notes=coalesce(p_data->>'notes',''),name=v_name,category_id=c.id,
   place_id=v_place,quantity=v_qty,owner=coalesce(nullif(trim(p_data->>'owner'),''),'Club property'),details=v_details,version=version+1 where id=e.id;
  v_id:=e.id;
 end if;
 insert into app_private.equipment_history(equipment_id,condition,notes,actor) values(v_id,v_condition,coalesce(p_data->>'notes',''),u);
 return v_id;
end $$;
revoke all on function app_private.save_inventory_item(uuid,jsonb) from public,anon,authenticated;

create function public.save_inventory_item(p_data jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_inventory(); v_id uuid;
begin
 v_id:=app_private.save_inventory_item(u,p_data);
 perform app_private.note(10,v_id,'save_inventory_item',p_data);
 return jsonb_build_object('id',v_id);
end $$;

-- One spreadsheet tab into one place. All rows save or none do.
create function public.import_inventory(p_place_id uuid,p_rows jsonb) returns jsonb
language plpgsql security definer set search_path='' as $$
declare u uuid:=app_private.require_inventory(); r jsonb; n integer:=0; i integer:=0;
begin
 if jsonb_typeof(p_rows) is distinct from 'array' or jsonb_array_length(p_rows) not between 1 and 500 then raise exception 'Import 1 to 500 items at a time.'; end if;
 if p_place_id is not null and not exists(select 1 from app_private.inventory_places where id=p_place_id) then raise exception 'That place no longer exists.'; end if;
 for r in select value from jsonb_array_elements(p_rows) loop
  i:=i+1;
  begin
   perform app_private.save_inventory_item(u,(r-'id')||jsonb_build_object('place_id',p_place_id));
  exception when others then raise exception 'Row %: %',i,sqlerrm;
  end;
  n:=n+1;
 end loop;
 perform app_private.note(10,p_place_id,'import_inventory',jsonb_build_object('items',n));
 return jsonb_build_object('imported',n);
end $$;

-- Categories that aren't borrowable can't be requested, whatever the app shows.
create function app_private.only_borrowable() returns trigger language plpgsql security definer set search_path='' as $$
begin
 if exists(select 1 from app_private.equipment e join app_private.inventory_categories c on c.id=e.category_id where e.id=new.equipment_id and not c.borrowable) then
  raise exception 'This item is not lent out.'; end if;
 return new;
end $$;
create trigger borrow_only_borrowable before insert or update of equipment_id on app_private.borrowings for each row execute function app_private.only_borrowable();

do $$ declare f text; begin
 foreach f in array array['save_inventory_category(jsonb)','save_inventory_place(jsonb)','order_inventory(text,uuid[])','delete_inventory_group(text,uuid)','save_inventory_item(jsonb)','import_inventory(uuid,jsonb)'] loop
  execute format('revoke all on function public.%s from public,anon',f);
  execute format('grant execute on function public.%s to authenticated',f);
 end loop;
end $$;

-- The app reads categories and places with everything else.
create or replace function public.club_workspace() returns jsonb language plpgsql security invoker set search_path='' as $$
begin
 return app_private.club_workspace_base()||jsonb_build_object(
  'waitlist',(select coalesce(jsonb_agg(w),'[]') from app_private.waitlist w),
  'event_stats',app_private.event_stats(),
  'equipment_status',app_private.equipment_status(),
  'name_corrections',(select coalesce(jsonb_agg(n),'[]') from app_private.name_corrections n),
  'inventory_categories',(select coalesce(jsonb_agg(c order by c.position,c.name),'[]') from app_private.inventory_categories c),
  'inventory_places',(select coalesce(jsonb_agg(p order by p.position,p.name),'[]') from app_private.inventory_places p));
end $$;
revoke all on function public.club_workspace() from public,anon;
grant execute on function public.club_workspace() to authenticated;

commit;
