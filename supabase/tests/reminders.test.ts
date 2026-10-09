import { PGlite } from "npm:@electric-sql/pglite@0.5.8";
import webpush from "npm:web-push@3.6.7";
import { createECDH, randomBytes } from "node:crypto";
import assert from "node:assert/strict";

Deno.test("reminder SQL claims once per device, skips ineligible tasks, and bounds retries", async () => {
  const db = new PGlite();
  try {
    await db.exec(`create role anon; create role authenticated; create role service_role;
      create table public.tasks(id uuid primary key, remind_at timestamptz, status text);
      create table public.push_subscriptions(id uuid primary key, endpoint text, p256dh text, auth text);`);
    const sql = await Deno.readTextFile(new URL("../reminders.sql", import.meta.url));
    await db.exec(sql.split("-- Enable pg_cron")[0]);
    await db.exec(`insert into public.push_subscriptions values
      ('00000000-0000-0000-0000-000000000001','https://fcm.googleapis.com/a','key','auth'),
      ('00000000-0000-0000-0000-000000000002','https://fcm.googleapis.com/b','key','auth');
      insert into public.tasks values
      ('00000000-0000-0000-0000-000000000011',now() - interval '2 minutes','PENDING'),
      ('00000000-0000-0000-0000-000000000012',now() - interval '2 minutes','COMPLETED'),
      ('00000000-0000-0000-0000-000000000013',now() - interval '2 minutes','CANCELLED'),
      ('00000000-0000-0000-0000-000000000014',now() + interval '2 minutes','PENDING'),
      ('00000000-0000-0000-0000-000000000015',now() - interval '2 days','PENDING');`);
    assert.equal((await db.query("select * from public.claim_reminders() ")).rows.length, 2);
    assert.equal((await db.query("select * from public.claim_reminders() ")).rows.length, 0);
    await db.exec("update public.reminder_deliveries set lease_until = now() - interval '1 minute', attempts = 3");
    assert.equal((await db.query("select * from public.claim_reminders() ")).rows.length, 0);
    await db.exec("update public.tasks set remind_at = now() - interval '1 minute' where id = '00000000-0000-0000-0000-000000000011'");
    assert.equal((await db.query("select * from public.claim_reminders() ")).rows.length, 2);
    await db.exec("update public.tasks set status = 'COMPLETED'; update public.reminder_deliveries set lease_until = null, attempts = 0");
    assert.equal((await db.query("select * from public.claim_reminders() ")).rows.length, 0);
    await db.exec("set role authenticated");
    await assert.rejects(db.query("select * from public.claim_reminders()"), /permission denied/);
    await db.exec("reset role");
  } finally { await db.close(); }
});

Deno.test("Web Push encryption and VAPID request generation work in the Edge runtime", () => {
  const vapid = webpush.generateVAPIDKeys();
  webpush.setVapidDetails("mailto:example@example.com", vapid.publicKey, vapid.privateKey);
  const device = createECDH("prime256v1"); device.generateKeys();
  const details = webpush.generateRequestDetails({ endpoint: "https://fcm.googleapis.com/send/test", keys: {
    p256dh: device.getPublicKey().toString("base64url"), auth: randomBytes(16).toString("base64url")
  } }, JSON.stringify({ title: "Folio reminder", body: "A reminder is due" }), { TTL: 3600 });
  assert.equal(details.method, "POST");
  assert.ok(details.body.length > 100);
  assert.ok(details.headers.Authorization);
});
