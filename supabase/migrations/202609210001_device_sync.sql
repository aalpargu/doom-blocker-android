-- Doom Blocker: isolated, authenticated RPC; raw tables have no client privileges.
begin;
create schema if not exists doom_private;
revoke all on schema doom_private from public, anon, authenticated;
create table if not exists doom_private.accounts (
  user_id uuid primary key references auth.users(id) on delete cascade,
  zone text not null,
  created_at timestamptz not null default now()
);
create table if not exists doom_private.devices (
  user_id uuid not null references doom_private.accounts(user_id) on delete cascade,
  device_id uuid not null,
  name text not null,
  last_seen timestamptz not null default now(),
  primary key (user_id, device_id)
);
create table if not exists doom_private.apps (
  user_id uuid not null references doom_private.accounts(user_id) on delete cascade,
  package_name text not null,
  label text not null,
  daily_ms bigint not null check (daily_ms between 60000 and 86400000),
  pending_daily_ms bigint,
  pending_day date,
  cooldown_until timestamptz,
  primary key (user_id, package_name)
);
create table if not exists doom_private.usage (
  user_id uuid not null,
  device_id uuid not null,
  package_name text not null,
  usage_day date not null,
  used_ms bigint not null check (used_ms between 0 and 172800000),
  primary key (user_id, device_id, package_name, usage_day),
  foreign key (user_id, device_id) references doom_private.devices(user_id, device_id) on delete cascade,
  foreign key (user_id, package_name) references doom_private.apps(user_id, package_name) on delete cascade
);
create table if not exists doom_private.cooldown_events (
  user_id uuid not null,
  device_id uuid not null,
  package_name text not null,
  event_id text not null,
  created_at timestamptz not null default now(),
  primary key (user_id, device_id, package_name, event_id),
  foreign key (user_id, device_id) references doom_private.devices(user_id, device_id) on delete cascade
);
alter table doom_private.accounts enable row level security;
alter table doom_private.devices enable row level security;
alter table doom_private.apps enable row level security;
alter table doom_private.usage enable row level security;
alter table doom_private.cooldown_events enable row level security;
revoke all on all tables in schema doom_private from public, anon, authenticated;

create or replace function public.doom_sync(
  p_device uuid, p_name text, p_zone text, p_apps jsonb
) returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  uid uuid := auth.uid();
  zone_name text;
  today date;
  item jsonb;
  pkg text;
  day_value date;
  inserted integer;
  response jsonb;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_device is null or p_name is null or length(p_name) not between 1 and 80 then
    raise exception 'Invalid device';
  end if;
  if p_zone is null or not exists (select 1 from pg_catalog.pg_timezone_names where name = p_zone) then
    raise exception 'Invalid timezone';
  end if;
  if p_apps is null or jsonb_typeof(p_apps) <> 'array' or jsonb_array_length(p_apps) > 500 then
    raise exception 'Invalid apps';
  end if;
  insert into doom_private.accounts(user_id, zone) values (uid, p_zone) on conflict do nothing;
  -- Serialize this account's totals/policies; two devices never overwrite one another.
  select zone into zone_name from doom_private.accounts where user_id = uid for update;
  today := (current_timestamp at time zone zone_name)::date;
  if p_zone <> zone_name then raise exception 'Device timezone must match account'; end if;
  insert into doom_private.devices(user_id, device_id, name) values (uid, p_device, p_name)
    on conflict (user_id, device_id) do update set name = excluded.name, last_seen = current_timestamp;
  update doom_private.apps set daily_ms = pending_daily_ms, pending_daily_ms = null, pending_day = null
    where user_id = uid and pending_day <= today;
  for item in select value from jsonb_array_elements(p_apps) loop
    pkg := item->>'package';
    if pkg is null or length(pkg) not between 3 and 200 or pkg !~ '^[A-Za-z0-9_.]+$'
      or item->>'label' is null or length(item->>'label') not between 1 and 200
      or item->>'day' is null or item->>'used_ms' is null or item->>'daily_ms' is null then
      raise exception 'Invalid app record';
    end if;
    if (item->>'used_ms')::bigint not between 0 and 172800000
       or (item->>'daily_ms')::bigint not between 60000 and 86400000 then
      raise exception 'Invalid usage or limit';
    end if;
    day_value := (item->>'day')::date;
    if day_value > today or day_value < today - 30 then raise exception 'Invalid usage day'; end if;
    insert into doom_private.apps(user_id, package_name, label, daily_ms)
      values (uid, pkg, item->>'label', (item->>'daily_ms')::bigint) on conflict do nothing;
    insert into doom_private.usage(user_id, device_id, package_name, usage_day, used_ms)
      values(uid, p_device, pkg, day_value, (item->>'used_ms')::bigint)
      on conflict (user_id, device_id, package_name, usage_day)
      do update set used_ms = greatest(doom_private.usage.used_ms, excluded.used_ms);
    if coalesce((item->>'cooldown_ms')::bigint, 0) > 0 then
      if (item->>'cooldown_ms')::bigint > 86400000 or
         coalesce(length(item->>'cooldown_id'), 0) not between 1 and 100 then
        raise exception 'Invalid cooldown';
      end if;
      insert into doom_private.cooldown_events(user_id, device_id, package_name, event_id)
        values(uid, p_device, pkg, item->>'cooldown_id') on conflict do nothing;
      get diagnostics inserted = row_count;
      if inserted = 1 then
        update doom_private.apps set cooldown_until = greatest(cooldown_until,
          current_timestamp + (item->>'cooldown_ms')::bigint * interval '1 millisecond')
          where user_id = uid and package_name = pkg;
      end if;
    end if;
  end loop;
  select jsonb_build_object(
    'day', today, 'zone', zone_name,
    'server_ms', floor(extract(epoch from current_timestamp) * 1000)::bigint,
    'devices', (select coalesce(jsonb_agg(jsonb_build_object('name', name, 'device_id', device_id,
        'last_seen', last_seen)), '[]'::jsonb) from doom_private.devices where user_id = uid),
    'apps', (select coalesce(jsonb_agg(jsonb_build_object(
        'package', a.package_name, 'label', a.label, 'daily_ms', a.daily_ms,
        'pending_daily_ms', a.pending_daily_ms, 'pending_day', a.pending_day,
        'used_ms', coalesce((select sum(u.used_ms) from doom_private.usage u
            where u.user_id = uid and u.package_name = a.package_name and u.usage_day = today), 0),
        'device_used_ms', coalesce((select u.used_ms from doom_private.usage u
            where u.user_id = uid and u.package_name = a.package_name and u.device_id = p_device
              and u.usage_day = today), 0),
        'cooldown_ms', greatest(0, coalesce(floor(extract(epoch from
            (a.cooldown_until - current_timestamp)) * 1000)::bigint, 0))
    )), '[]'::jsonb) from doom_private.apps a where a.user_id = uid)
  ) into response;
  return response;
end $$;
revoke all on function public.doom_sync(uuid,text,text,jsonb) from public, anon;
grant execute on function public.doom_sync(uuid,text,text,jsonb) to authenticated;

create or replace function public.doom_set_daily_limit(p_package text, p_minutes integer)
returns void language plpgsql security definer set search_path = '' as $$
declare uid uuid := auth.uid(); today date; current_limit bigint;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_minutes is null or p_minutes not between 1 and 1440 then raise exception 'Invalid limit'; end if;
  select (current_timestamp at time zone zone)::date into today
    from doom_private.accounts where user_id = uid for update;
  update doom_private.apps set daily_ms = pending_daily_ms, pending_daily_ms = null, pending_day = null
    where user_id = uid and pending_day <= today;
  select daily_ms into current_limit from doom_private.apps where user_id = uid and package_name = p_package;
  if current_limit is null then raise exception 'App not found'; end if;
  if p_minutes::bigint * 60000 <= current_limit then
    update doom_private.apps set daily_ms = p_minutes::bigint * 60000, pending_daily_ms = null, pending_day = null
      where user_id = uid and package_name = p_package;
  else
    update doom_private.apps set pending_daily_ms = p_minutes::bigint * 60000, pending_day = today + 1
      where user_id = uid and package_name = p_package;
  end if;
end $$;
revoke all on function public.doom_set_daily_limit(text,integer) from public, anon;
grant execute on function public.doom_set_daily_limit(text,integer) to authenticated;
commit;
