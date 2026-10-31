-- Migration 007: SuperAdministrator role.
--
-- A fifth value of public.user_role, above Administrator: the same unlimited quotas plus the
-- management of the other accounts — list (GET /api/users), role (PUT /api/users/{uid}/role),
-- deactivation and reactivation (POST /api/users/{uid}/deactivate | reactivate). Only this role
-- manages roles; Administrator keeps everything else it had (unlimited quotas, persistent
-- frames, every run).
--
-- No row is changed here: the accounts listed in BOOTSTRAP_ADMIN_EMAILS are promoted to
-- SuperAdministrator on their next authenticated request (FirebaseAuthDirective), which is how
-- the first Administrator was granted before this migration. Existing roles are kept.
--
-- ALTER TYPE ... ADD VALUE is a top-level statement on purpose (it is not allowed inside a DO
-- block / a transaction that then uses the value); IF NOT EXISTS makes it safe to re-run on
-- every startup like the other migrations.

ALTER TYPE public.user_role ADD VALUE IF NOT EXISTS 'SuperAdministrator';
