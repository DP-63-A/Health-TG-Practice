
# Health TG Practice Frontend

React + TypeScript frontend created with Vite.

## Run locally

```bash
npm install
npm run dev
```

Useful checks:

```bash
npm run typecheck
npm run build
```

For live Telegram development on Windows, run `scripts/run-miniapp.ps1` from the
repository root. See [the local Mini App workflow](../docs/local-miniapp.md).
It manages a single frontend HTTPS tunnel and routes `/api` through Vite to the
host backend, keeping the phone independent of localhost addresses.

## API mode

The shared API client is selected with `VITE_API_MODE`.

- `fixture` uses local fixture responses and does not call the backend.
- `live` uses `fetch` and sends requests to `VITE_API_BASE_URL`.

There is no automatic fallback from `live` to `fixture`: live request failures stay visible as API errors.

## Environment

Copy `.env.example` to `.env.local` and adjust values when needed.

```env
VITE_API_MODE=live
VITE_API_BASE_URL=/api/v1
```

`VITE_API_BASE_URL` is required only when `VITE_API_MODE=live`.
The Vite proxy defaults to `http://127.0.0.1:8081`; `scripts/run-frontend.ps1`
uses `SERVER_PORT` from the root `.env` instead. Direct `npm run dev` can override
the target using the server-side `LOCAL_API_ORIGIN` environment variable.

The session token hook is prepared in memory only. It is not persisted to `localStorage` or `sessionStorage`.
