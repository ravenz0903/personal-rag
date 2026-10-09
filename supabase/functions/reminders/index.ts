import { createClient } from "npm:@supabase/supabase-js@2.117.0";
import webpush from "npm:web-push@3.6.7";
import { deliverReminders } from "../_shared/deliver-reminders.mjs";

Deno.serve(async (request: Request) => {
  const secret = Deno.env.get("REMINDER_CRON_SECRET");
  if (request.method !== "POST" || !secret || request.headers.get("x-reminder-secret") !== secret)
    return new Response("Unauthorized", { status: 401 });
  try {
    const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
    webpush.setVapidDetails(Deno.env.get("VAPID_SUBJECT")!, Deno.env.get("VAPID_PUBLIC_KEY")!, Deno.env.get("VAPID_PRIVATE_KEY")!);
    const { data: rows, error } = await db.rpc("claim_reminders");
    if (error) throw error;
    await deliverReminders(rows, {
      stillEligible: async (row: any) => {
        const { data, error } = await db.from("tasks").select("id").eq("id", row.task_id)
          .eq("remind_at", row.remind_at).in("status", ["PENDING", "IN_PROGRESS"]).maybeSingle();
        if (error) throw error;
        return !!data;
      },
      send: async (row: any) => {
        const details = webpush.generateRequestDetails({ endpoint: row.endpoint, keys: { p256dh: row.p256dh, auth: row.auth } },
          JSON.stringify({ title: "Folio reminder", body: "A task reminder is due. Open your private workspace to view it.", tag: row.task_id + ":" + row.remind_at }), { TTL: 3600 });
        const response = await fetch(details.endpoint, { method: details.method, headers: details.headers, body: details.body, signal: AbortSignal.timeout(5000), redirect: "error" });
        return response.status;
      },
      markSent: async (row: any) => {
        const { error } = await db.from("reminder_deliveries").update({ sent_at: new Date().toISOString() })
          .eq("task_id", row.task_id).eq("subscription_id", row.subscription_id).eq("remind_at", row.remind_at);
        if (error) throw error;
      },
      removeSubscription: async (row: any) => {
        const { error } = await db.from("push_subscriptions").delete().eq("id", row.subscription_id);
        if (error) throw error;
      }
    });
    return Response.json({ processed: rows.length });
  } catch { return new Response("Reminder delivery failed", { status: 500 }); }
});
