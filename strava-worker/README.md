# Strava token Worker

Keeps the Strava **client secret** off users' phones. The app posts `grant_type` + `code` / `refresh_token`
to `POST /token`; the Worker adds `client_id` + `client_secret` and forwards to `https://www.strava.com/oauth/token`.
It stores and logs nothing.

## Deploy (once)
```bash
cd strava-worker
npx wrangler login                              # opens the browser, Cloudflare account (free plan is enough)
npx wrangler secret put STRAVA_CLIENT_SECRET    # paste the secret from strava.com/settings/api
npx wrangler deploy                             # prints https://fit3zone-strava.<your-subdomain>.workers.dev
```
Then in `local.properties`: `STRAVA_TOKEN_URL=https://fit3zone-strava.<your-subdomain>.workers.dev`
and rebuild. With `STRAVA_TOKEN_URL` set, debug builds use the Worker too; release builds never contain the secret.

## Check
```bash
curl -s -X POST https://…workers.dev/token -d grant_type=refresh_token   # → 400 with a message (no secret leaked)
```
