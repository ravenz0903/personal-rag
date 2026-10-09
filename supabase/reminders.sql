-- Run AFTER the cloud backend has created tasks and push_subscriptions.
create index if not exists idx_tasks_remind_at on public.tasks(remind_at);
create table if not exists public.reminder_deliveries (
  task_id uuid not null references public.tasks(id) on delete cascade,
  subscription_id uuid not null references public.push_subscriptions(id) on delete cascade,
  remind_at timestamptz not null,
  attempts integer not null default 0,
  lease_until timestamptz,
  sent_at timestamptz,
  primary key(task_id, subscription_id, remind_at)
);
alter table public.reminder_deliveries enable row level security;
revoke all on public.reminder_deliveries from anon, authenticated;
grant all on public.reminder_deliveries, public.push_subscriptions to service_role;
grant select on public.tasks to service_role;

create or replace function public.claim_reminders()
returns table(task_id uuid, subscription_id uuid, remind_at timestamptz, endpoint text, p256dh text, auth text)
language plpgsql security definer set search_path = '' as $$
begin
  -- Catch up at most one day; completed/cancelled tasks never create deliveries.
  insert into public.reminder_deliveries(task_id, subscription_id, remind_at)
  select t.id, s.id, t.remind_at from public.tasks t cross join public.push_subscriptions s
  where t.remind_at <= now() and t.remind_at > now() - interval '1 day'
    and t.status in ('PENDING', 'IN_PROGRESS')
  on conflict do nothing;
  return query
  with candidates as (
    select d.task_id, d.subscription_id, d.remind_at from public.reminder_deliveries d
    join public.tasks t on t.id = d.task_id and t.remind_at = d.remind_at
    where d.sent_at is null and d.attempts < 3 and (d.lease_until is null or d.lease_until < now())
      and d.remind_at > now() - interval '1 day' and t.status in ('PENDING', 'IN_PROGRESS')
    order by d.remind_at limit 10 for update of d skip locked
  ), claimed as (
    update public.reminder_deliveries d set lease_until = now() + interval '5 minutes', attempts = d.attempts + 1
    from candidates c where d.task_id = c.task_id and d.subscription_id = c.subscription_id and d.remind_at = c.remind_at
    returning d.task_id, d.subscription_id, d.remind_at
  )
  select c.task_id, c.subscription_id, c.remind_at, s.endpoint::text, s.p256dh::text, s.auth::text
  from claimed c join public.push_subscriptions s on s.id = c.subscription_id;
end $$;
revoke all on function public.claim_reminders() from public, anon, authenticated;
grant execute on function public.claim_reminders() to service_role;

-- Enable pg_cron and pg_net in Supabase > Database > Extensions first.
-- Add Vault secrets named folio_url (https://PROJECT.supabase.co) and
-- folio_reminder_secret (a long random value also stored as REMINDER_CRON_SECRET in Edge Function secrets).
select cron.schedule('folio-reminders', '* * * * *', $job$
  select net.http_post(
    url := (select decrypted_secret from vault.decrypted_secrets where name = 'folio_url') || '/functions/v1/reminders',
    headers := jsonb_build_object('Content-Type', 'application/json', 'x-reminder-secret',
      (select decrypted_secret from vault.decrypted_secrets where name = 'folio_reminder_secret')),
    body := '{}'::jsonb, timeout_milliseconds := 60000
  );
$job$);
select cron.schedule('folio-reminder-cleanup', '17 3 * * *',
  $$delete from public.reminder_deliveries where remind_at < now() - interval '30 days'$$);
