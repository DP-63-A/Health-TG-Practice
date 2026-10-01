
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

## API mode

The shared API client is selected with `VITE_API_MODE`.

- `fixture` uses local fixture responses and does not call the backend.
- `live` uses `fetch` and sends requests to `VITE_API_BASE_URL`.

There is no automatic fallback from `live` to `fixture`: live request failures stay visible as API errors.

## Environment

Copy `.env.example` to `.env.local` and adjust values when needed.

```env
VITE_API_MODE=fixture
VITE_API_BASE_URL=http://localhost:3000
```

`VITE_API_BASE_URL` is required only when `VITE_API_MODE=live`.

The session token hook is prepared in memory only. It is not persisted to `localStorage` or `sessionStorage`.
