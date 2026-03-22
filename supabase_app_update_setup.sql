-- SyncPad app update infrastructure
-- Run this in your Supabase SQL editor before uploading releases.

create table if not exists public.app_versions (
    id uuid default gen_random_uuid() primary key,
    version_code integer not null unique,
    version_name text not null,
    apk_url text not null,
    release_notes text,
    is_force_update boolean not null default false,
    min_supported_version integer not null default 1,
    file_size_bytes bigint,
    checksum_md5 text,
    created_at timestamptz not null default now(),
    is_active boolean not null default true
);

create index if not exists idx_app_versions_active_version
    on public.app_versions (is_active, version_code desc);

alter table public.app_versions enable row level security;

do $$
begin
    if not exists (
        select 1
        from pg_policies
        where schemaname = 'public'
          and tablename = 'app_versions'
          and policyname = 'Allow public read app versions'
    ) then
        create policy "Allow public read app versions"
            on public.app_versions
            for select
            using (true);
    end if;
end $$;

-- Make sure the bucket exists and is public:
insert into storage.buckets (id, name, public) values ('app-releases', 'app-releases', true)
on conflict (id) do nothing;
