# Privacy policy — Taal

_Last updated: 20 October 2026_

Taal ("the app") is a heart-rate training app for Android made by Rameez Shaikh. This policy explains what data
the app uses and where it goes. Short version: **your data stays on your phone** unless you turn on a connection
(Strava, Health Connect) or share something yourself.

## What the app collects and why

| Data | Source | Used for | Stored |
|---|---|---|---|
| Heart rate | Your Bluetooth strap, watch or band | Live zones, alerts, activity history, training stats | On your phone |
| Location (GPS) | Your phone, **only while a GPS activity is recording** | Route, distance, pace, elevation | On your phone |
| Steps / cadence | Your phone's step sensor, only during activities | Cadence and steps of the activity | On your phone |
| Profile | You (age, max and resting heart rate) | Calculating your heart-rate zones | On your phone |
| Health Connect data (read) | Steps, sleep, heart rate, resting heart rate, calories, if you allow it | My Day dashboard | Read when shown; not copied elsewhere |
| Galaxy Fit3 band data (optional, off by default) | What the band sends to your phone when it syncs | My Day band cards, resting heart rate | On your phone |
| App log | The app | Troubleshooting (connection, session and sync events; no GPS positions or heart-rate history) | On your phone, last ~4 MB |

The app has **no account, no analytics, no ads and no tracking**. Android cloud backup of app data is disabled.

## When data leaves your phone

Only in these cases, each one turned on or started by you:

- **Strava** (optional): when you connect Strava, finished activities (route, heart rate, cadence, laps, name, note)
  are uploaded to your Strava account, and renaming an activity updates it there. Login tokens are stored encrypted
  on your phone. The app's login goes through a small token service (Cloudflare Worker) that adds the app's Strava
  key; it stores nothing. Strava's own privacy policy applies to data on Strava. You can disconnect at any time
  (Device → Strava → Disconnect) or remove access at strava.com → Settings → My Apps.
- **Health Connect** (optional): when you turn on "Send to Samsung Health", finished activities (exercise session,
  route, heart rate, distance, elevation gain) are written to Health Connect on your phone, where apps you allow
  (such as Samsung Health) can read them. Deleting an activity in the app removes it from Health Connect.
- **Sharing**: exporting an activity (CSV), sharing a route (GPX) or sending feedback (with the app log attached) opens Android's share
  sheet; nothing is sent until you choose an app and send it.

- **Update check**: about twice a day the app downloads a small file (version.json) from the app's website to see
  whether a newer version exists. Nothing about you or your activities is sent with that request.
- **Backup**: "Your data → Save backup file" writes a file where you choose (phone storage, Drive…). Strava logins
  are not included.
- **New loop** (Run → Route → New loop): to plan a loop, the app asks the public OpenStreetMap Overpass service
  (overpass-api.de, or overpass.private.coffee if that is busy) for the paths in a circle around your current
  position. Only that circle (a point and a radius) is sent, with the app's name and version; no name, account or
  activity. The answer is kept on your phone for 30 days so the same area isn't downloaded again. The loop is
  planned on your phone. Map data © OpenStreetMap contributors (ODbL).
- **GPX export**: "Export GPX" on an activity saves a file to Downloads/Taal on your phone.

The app never sells data or shares it with anyone else.

## Health Connect

Data read from Health Connect is only shown in the app and is not stored elsewhere, sent off the phone or used for
advertising. Use of Health Connect data follows the
[Health Connect Permissions policy](https://support.google.com/googleplay/android-developer/answer/12991134),
including its Limited Use requirements.

## Your control

- Delete any activity in the app (Activities → activity → Delete).
- Turn off Strava, Health Connect or band data in the Device tab at any time.
- Uninstalling the app deletes all its data from your phone (data already on Strava or in Health Connect stays
  there until you delete it in those apps).

## Not affiliated

Taal is an independent app. It is not made by or affiliated with Samsung, Garmin or Strava. Reading data from a
Galaxy Fit3 band is an unofficial, experimental option that is off by default.

## Not medical advice

Taal is not a medical device. Heart-rate zones, alerts and training numbers are estimates for fitness use.

## Children

The app is not directed at children under 13.

## Changes and contact

Changes to this policy are published at this address with a new date. Questions:
**rameez0811@gmail.com**.
