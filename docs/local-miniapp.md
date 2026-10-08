# Telegram Mini App on Windows

From the repository root in PowerShell:

```powershell
.\scripts\run-miniapp.ps1
```

Requires Docker Desktop, `cloudflared` and Node/npm on PATH, Java 21 in
`JAVA_HOME` (or installed under `Program Files\Eclipse Adoptium`), installed
frontend dependencies, and a configured root `.env` with the bot token, allowlist,
MongoDB URI and `SERVER_PORT`. Use the ordinary database, not fixture mode.
Do not share `.env`, logs, configuration backups or Telegram initData.

The script starts only `mongo` using `compose.yaml` and `compose.dev.yaml`, then
the host API, Vite and bot. It verifies API/Mongo readiness locally and over HTTPS
before updating Telegram. It waits for the bot's startup and polling messages.
It never deletes volumes, seeds data, clears Telegram updates or changes webhooks.

One Cloudflare Quick Tunnel exposes `http://127.0.0.1:5173`. The phone sends API
requests to `/api/v1` on that same HTTPS origin; Vite proxies `/api` to the local
API port from `.env`. A separate backend tunnel is unnecessary. Telegram SDK
loads from `telegram.org`; real initData is verified by the backend with the same
bot token and allowlist. A normal browser without Telegram should ask you to open
the application inside Telegram; it must not fabricate a session.

`MINI_APP_URL` in `.env` is the single public frontend base URL. The launcher
updates it and the exact CORS origin, preserves other settings, and sets
`VITE_API_MODE=live` and `VITE_API_BASE_URL=/api/v1` in `frontend/.env.local`.
Vite permits only localhost and the configured tunnel hostname, not all hosts.
Configuration backups, tunnel URL/PID and process logs are stored in the ignored
`.local-miniapp/` directory. This directory contains private configuration backups.

A healthy tunnel is reused on repeat startup. If it expires, a new URL is created
and configuration/menu URLs are synchronized. Quick Tunnel hostnames are temporary;
the script always prints the current URL. Config changes restart only recognized
API, bot and Vite processes from this exact repository. Unrelated port owners
cause an error and are never stopped. To explicitly reload project processes:

```powershell
.\scripts\run-miniapp.ps1 -Restart
```

The launcher uses background PowerShell processes; keep Docker Desktop and the
computer running. To stop the host applications, identify their PIDs from the
launcher output and Windows process list before stopping them; do not stop all
Java/node/cloudflared processes. The current tunnel PID and URL are in
`.local-miniapp/state.json`. No volume removal is required.

## Telegram URL sources and final verification

`/start` calls `setChatMenuButton` for the current chat using `MINI_APP_URL`.
This chat-specific menu overrides the default configured through BotFather.
The launcher runs `scripts/sync-miniapp-menu.ps1`, setting and reading back the
same URL for the default and every allowed chat. It does not send messages.
This synchronization can also be run independently after changing `.env`.

The draft review inline button derives `/diary/<entry-id>` from `MINI_APP_URL`.
The date/time reply WebApp button derives `/datetime-picker.html` from the same
base, with short-lived picker context in its fragment. These are different
screens on the same frontend. Ordinary text/quick-check-in buttons contain no URL.
Previously sent inline/reply buttons retain their original URL; use fresh bot
responses instead of opening old messages. The Telegram SDK itself does not
choose the app hostname.

BotFather's menu is synchronized by the script. For manual setup use
`/mybots` → `@HealthCyberbot` → **Bot Settings** → **Menu Button** → **Configure
menu button**, and paste the exact frontend URL printed by the launcher.
If a separate Main Mini App / profile **Open App** URL is configured in BotFather,
update that URL under **Configure Mini App** as well; it is separate from Bot API
chat menus and is not inspectable through `getChatMenuButton`.

On the phone, close all old Mini App windows, open the bot chat, send `/start`
and tap the newly configured **Open diary / Открыть кабинет** menu button.
Confirm that your real diary and **Stats** load, with your existing records (or
the genuine empty state for the selected period). Test a fresh draft's inline
button if you use that workflow. Never paste initData or session tokens into logs.

## Checks

```powershell
.\scripts\tests\local-miniapp.test.ps1
Set-Location frontend
npm run lint
npm run typecheck
npm test
npm run build
```

The script tests use temporary synthetic configuration only. They check
preservation of unrelated settings, duplicate URL removal, URL validation and
process ownership. Frontend unit tests intentionally use fixtures; the running
Mini App always uses the real API. Phone authentication and actual diary/analytics
rendering still require manual verification inside Telegram.
