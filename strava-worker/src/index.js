// Strava token exchange for Taal. The app never holds the client secret: it sends the login code (or a
// refresh token) here, this Worker adds client_id + client_secret and forwards it to Strava unchanged.
// Nothing is stored or logged. Secrets: `wrangler secret put STRAVA_CLIENT_SECRET` (client id is in wrangler.toml).

const STRAVA_TOKEN = "https://www.strava.com/oauth/token";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname !== "/token") return new Response("Not found", { status: 404 });
    if (request.method !== "POST") return new Response("Use POST", { status: 405 });

    let form;
    try {
      form = await request.formData();
    } catch {
      return json({ message: "Expected a form body" }, 400);
    }
    const grant = form.get("grant_type");
    const body = new URLSearchParams({
      client_id: env.STRAVA_CLIENT_ID,
      client_secret: env.STRAVA_CLIENT_SECRET,
      grant_type: grant ?? "",
    });
    if (grant === "authorization_code" && form.get("code")) {
      body.set("code", form.get("code"));
    } else if (grant === "refresh_token" && form.get("refresh_token")) {
      body.set("refresh_token", form.get("refresh_token"));
    } else {
      return json({ message: "grant_type must be authorization_code (with code) or refresh_token (with refresh_token)" }, 400);
    }

    const strava = await fetch(STRAVA_TOKEN, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
    });
    return new Response(strava.body, {
      status: strava.status,
      headers: { "Content-Type": strava.headers.get("Content-Type") ?? "application/json", "Cache-Control": "no-store" },
    });
  },
};

function json(obj, status) {
  return new Response(JSON.stringify(obj), { status, headers: { "Content-Type": "application/json" } });
}
