// npm install --prefix <test-runtime> @electric-sql/pglite
// node test-sync.mjs <absolute path to pglite/dist/index.js>
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
const { PGlite } = await import(pathToFileURL(process.argv[2]).href);
const db = new PGlite();
await db.exec(`
  set timezone = 'UTC';
  create role anon; create role authenticated;
  create schema auth;
  create table auth.users(id uuid primary key);
  create function auth.uid() returns uuid language sql stable as
    'select nullif(current_setting(''request.jwt.claim.sub'', true), '''')::uuid';
  grant usage on schema auth to authenticated;
  grant execute on function auth.uid() to authenticated;
`);
await db.exec(await readFile(new URL('./migrations/202609210001_device_sync.sql', import.meta.url), 'utf8'));
const alice = '11111111-1111-1111-1111-111111111111';
const bob = '22222222-2222-2222-2222-222222222222';
const phone = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const tablet = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
await db.query('insert into auth.users values ($1), ($2)', [alice, bob]);
const today = (await db.query("select current_date::text as day")).rows[0].day;
const record = (used, extra = {}) => ({ package: 'com.example.tiktok', label: 'TikTok',
  day: today, used_ms: used, daily_ms: 1800000, ...extra });
async function sync(user, device, items) {
  await db.query("select set_config('request.jwt.claim.sub', $1, false)", [user]);
  await db.exec('set role authenticated');
  try {
    return (await db.query('select public.doom_sync($1,$2,$3,$4::jsonb) as value',
      [device, device === phone ? 'Phone' : 'Tablet', 'UTC', JSON.stringify(items)])).rows[0].value;
  } finally { await db.exec('reset role'); }
}
let checks = 0;
function check(value, expected) { assert.deepEqual(value, expected); checks++; }
let result = await sync(alice, phone, [record(900000)]);
check(result.apps[0].used_ms, 900000);
result = await sync(alice, tablet, [record(900000)]);
check(result.apps[0].used_ms, 1800000);
check(result.devices.length, 2);
result = await sync(alice, phone, [record(900000)]);
check(result.apps[0].used_ms, 1800000); // retry
result = await sync(alice, phone, [record(100)]);
check(result.apps[0].used_ms, 1800000); // stale/out-of-order cannot refund
result = await sync(bob, phone, [record(1000)]);
check(result.apps[0].used_ms, 1000);
check(result.devices.length, 1); // separate account
result = await sync(alice, phone, [record(900000, { cooldown_id: 'break-1', cooldown_ms: 600000 })]);
check(result.apps[0].cooldown_ms > 599000, true);
result = await sync(alice, tablet, []);
check(result.apps[0].cooldown_ms > 590000, true);
await db.exec("update doom_private.apps set cooldown_until = now() - interval '1 second' where user_id = '" + alice + "'");
result = await sync(alice, phone, [record(900000, { cooldown_id: 'break-1', cooldown_ms: 600000 })]);
check(result.apps[0].cooldown_ms, 0); // replay cannot restart a finished break
await db.query("select set_config('request.jwt.claim.sub',$1,false)", [alice]);
await db.exec('set role authenticated');
await db.query('select public.doom_set_daily_limit($1,$2)', ['com.example.tiktok', 60]);
await db.exec('reset role');
result = await sync(alice, phone, []);
check(result.apps[0].daily_ms, 1800000);
check(result.apps[0].pending_daily_ms, 3600000);
await db.exec("update doom_private.apps set pending_day = current_date where user_id = '" + alice + "'");
result = await sync(alice, phone, []);
check(result.apps[0].daily_ms, 3600000);
await db.exec('set role authenticated');
await db.query('select public.doom_set_daily_limit($1,$2)', ['com.example.tiktok', 10]);
await assert.rejects(db.query('select * from doom_private.usage'));
checks++;
await db.exec('reset role');
result = await sync(alice, phone, []);
check(result.apps[0].daily_ms, 600000);
await db.exec('set role anon');
await assert.rejects(db.query('select public.doom_sync($1,$2,$3,$4)', [phone,'x','UTC','[]']));
checks++;
await db.exec('reset role');
await assert.rejects(sync(alice, phone, [record(-1)])); checks++;
await assert.rejects(sync(alice, phone, [record(10, { day: '2099-01-01' })])); checks++;
await assert.rejects(sync(alice, phone, [record(900000, { cooldown_ms: 1000 })])); checks++;
console.log(JSON.stringify({ checks, result: 'passed', engine: 'PGlite/PostgreSQL', note: 'Transactions execute serially in PGlite; physical two-device test still required.' }));
await db.close();
