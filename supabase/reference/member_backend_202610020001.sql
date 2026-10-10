-- Archery Club backend for M3 Activities, M4 Attendance/Training, and M6 Dashboard.
-- HISTORICAL ALTERNATIVE ONLY. Do not apply to the proposal development backend.
-- Preserved from the parallel member-screen implementation; see docs/team-setup.md.
-- Development seed records are included for published activities only.

create extension if not exists pgcrypto;

create table if not exists public.profiles (
    id uuid primary key references auth.users(id) on delete cascade,
    full_name text not null,
    email text not null,
    phone text not null default '',
    student_number text not null default '',
    category text not null default 'Regular member',
    membership_status text not null default 'Active'
        check (membership_status in ('Active', 'Pending', 'Suspended', 'Expired')),
    renewal_pending boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create unique index if not exists profiles_student_number_unique
    on public.profiles (student_number)
    where student_number <> '';

create table if not exists public.activities (
    id uuid primary key default gen_random_uuid(),
    title text not null,
    description text not null default '',
    activity_date date not null,
    start_time time,
    end_time time,
    location text not null default '',
    registration_deadline date not null,
    published boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists public.activity_registrations (
    activity_id uuid not null references public.activities(id) on delete cascade,
    member_id uuid not null references public.profiles(id) on delete cascade,
    status text not null default 'registered'
        check (status in ('registered', 'cancelled')),
    registered_at timestamptz not null default now(),
    primary key (activity_id, member_id)
);

create table if not exists public.attendance_records (
    id uuid primary key default gen_random_uuid(),
    activity_id uuid not null references public.activities(id) on delete restrict,
    member_id uuid not null references public.profiles(id) on delete cascade,
    status text not null check (status in ('PRESENT', 'ABSENT', 'EXCUSED')),
    note text,
    recorded_at timestamptz not null default now(),
    recorded_by uuid references auth.users(id)
);

create unique index if not exists attendance_activity_member_unique
    on public.attendance_records (activity_id, member_id);

create table if not exists public.training_records (
    id uuid primary key default gen_random_uuid(),
    member_id uuid not null references public.profiles(id) on delete cascade,
    training_date date not null,
    training_type text not null,
    focus text not null default '',
    score integer check (score is null or (score between 0 and 100)),
    coach text not null default '',
    feedback text not null default '',
    recorded_at timestamptz not null default now(),
    recorded_by uuid references auth.users(id)
);

create index if not exists training_member_date_idx
    on public.training_records (member_id, training_date desc);

-- Keep updated_at authoritative on the server.
create or replace function public.set_updated_at()
returns trigger
language plpgsql
as $$
begin
    new.updated_at = now();
    return new;
end;
$$;

drop trigger if exists profiles_set_updated_at on public.profiles;
create trigger profiles_set_updated_at
before update on public.profiles
for each row execute function public.set_updated_at();

-- Create the member profile automatically after Supabase Auth signup.
create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer set search_path = public
as $$
begin
    insert into public.profiles (id, full_name, email, phone, student_number)
    values (
        new.id,
        coalesce(new.raw_user_meta_data ->> 'full_name', split_part(coalesce(new.email, ''), '@', 1)),
        coalesce(new.email, ''),
        coalesce(new.raw_user_meta_data ->> 'phone', ''),
        coalesce(new.raw_user_meta_data ->> 'student_number', '')
    )
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
after insert on auth.users
for each row execute function public.handle_new_user();

-- Registration rules used by both the policy and database function.
create or replace function public.can_register_for_activity(p_activity_id uuid)
returns boolean
language sql
stable
security definer set search_path = public
as $$
    select exists (
        select 1
        from public.profiles p
        join public.activities a on a.id = p_activity_id
        where p.id = auth.uid()
          and p.membership_status = 'Active'
          and a.published = true
          and current_date <= a.registration_deadline
          and current_date <= a.activity_date
    );
$$;

-- Members may safely update only the two fields exposed by the app.
create or replace function public.update_member_contact(p_full_name text, p_phone text)
returns void
language plpgsql
security definer set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    if length(trim(p_full_name)) < 2 then
        raise exception 'Full name is required';
    end if;
    update public.profiles
    set full_name = trim(p_full_name), phone = trim(coalesce(p_phone, ''))
    where id = auth.uid();
end;
$$;

create or replace function public.request_membership_renewal()
returns void
language plpgsql
security definer set search_path = public
as $$
begin
    if auth.uid() is null then
        raise exception 'Not authenticated';
    end if;
    update public.profiles
    set renewal_pending = true
    where id = auth.uid() and renewal_pending = false;
    if not found then
        raise exception 'A renewal request is already pending';
    end if;
end;
$$;

-- RLS: every member sees only their own private records.
alter table public.profiles enable row level security;
alter table public.activities enable row level security;
alter table public.activity_registrations enable row level security;
alter table public.attendance_records enable row level security;
alter table public.training_records enable row level security;

drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles
for select to authenticated using (id = auth.uid());

drop policy if exists activities_select_published on public.activities;
create policy activities_select_published on public.activities
for select to authenticated using (published = true);

drop policy if exists registrations_select_own on public.activity_registrations;
create policy registrations_select_own on public.activity_registrations
for select to authenticated using (member_id = auth.uid());

drop policy if exists registrations_insert_own on public.activity_registrations;
create policy registrations_insert_own on public.activity_registrations
for insert to authenticated
with check (member_id = auth.uid() and status = 'registered' and public.can_register_for_activity(activity_id));

drop policy if exists registrations_delete_own on public.activity_registrations;
create policy registrations_delete_own on public.activity_registrations
for delete to authenticated using (member_id = auth.uid());

drop policy if exists attendance_select_own on public.attendance_records;
create policy attendance_select_own on public.attendance_records
for select to authenticated using (member_id = auth.uid());

drop policy if exists training_select_own on public.training_records;
create policy training_select_own on public.training_records
for select to authenticated using (member_id = auth.uid());

-- No direct profile UPDATE is granted to members. Contact/renewal go through the two RPCs.
revoke insert, update, delete on public.profiles from anon, authenticated;
revoke insert, update, delete on public.activities from anon, authenticated;
revoke insert, update, delete on public.attendance_records from anon, authenticated;
revoke insert, update, delete on public.training_records from anon, authenticated;
grant select on public.profiles to authenticated;
grant select on public.activities to authenticated;
grant select, insert, delete on public.activity_registrations to authenticated;
grant select on public.attendance_records to authenticated;
grant select on public.training_records to authenticated;
grant execute on function public.update_member_contact(text, text) to authenticated;
grant execute on function public.request_membership_renewal() to authenticated;
grant execute on function public.can_register_for_activity(uuid) to authenticated;

-- Seed published activities for development.
insert into public.activities (id, title, description, activity_date, start_time, end_time, location, registration_deadline, published)
values
    ('00000000-0000-4000-8000-000000000001', 'Weekly Club Training', 'Technique, form checks and scoring practice for regular members.', '2026-10-03', '08:00', '11:00', 'University Archery Range', '2026-10-02', true),
    ('00000000-0000-4000-8000-000000000002', 'Inter-Club Friendly Match', 'Friendly competition and team preparation.', '2026-10-10', '07:00', '14:00', 'National Archery Range', '2026-10-07', true),
    ('00000000-0000-4000-8000-000000000003', 'Equipment Inspection', 'Member equipment inspection and safety check.', '2026-10-13', '16:00', '18:00', 'Club Equipment Room', '2026-10-12', true)
on conflict (id) do nothing;

-- DEVELOPMENT ONLY: after creating a test account, run the following with that user's UUID
-- replaced for sample official records. Do not use fake records in production.
--
-- insert into public.attendance_records (activity_id, member_id, status, note, recorded_by)
-- values
-- ('00000000-0000-4000-8000-000000000001', 'YOUR_USER_UUID', 'PRESENT', null, 'YOUR_USER_UUID');
--
-- insert into public.training_records (member_id, training_date, training_type, focus, score, coach, feedback, recorded_by)
-- values
-- ('YOUR_USER_UUID', '2026-09-19', 'Technical', 'Anchor point consistency', 82, 'Coach Maria', 'Keep the anchor position consistent across the full shot cycle.', 'YOUR_USER_UUID');
