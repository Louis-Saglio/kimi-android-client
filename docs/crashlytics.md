# Querying Crashlytics from the command line

The service-account key and the Firebase app id are machine-specific: take
them from `AGENTS.local.md`.

```bash
export GOOGLE_APPLICATION_CREDENTIALS=<service-account key path>
TOKEN=$(gcloud auth application-default print-access-token)
APP=projects/<firebase-project-id>/apps/<firebase-app-id>
BASE=https://firebasecrashlytics.googleapis.com/v1alpha   # v1alpha only, v1beta1 404s
```

List issues:

```bash
curl -s -G -H "Authorization: Bearer $TOKEN" "$BASE/$APP/reports/topIssues" \
  --data-urlencode "filter.interval.startTime=2026-07-30T00:00:00Z" \
  --data-urlencode "filter.interval.endTime=2026-07-30T23:59:59Z"
```

Full stack traces (event ids from `sampleEvent` above; ~160 KB/event, parse
with `python3`; frames in `exceptions[].frames`, deobfuscated via the R8
mapping uploaded at build time):

```bash
curl -s -G -H "Authorization: Bearer $TOKEN" "$BASE/$APP/events:batchGet" \
  --data-urlencode "names=projects/-/apps/<appId>/events/<eventId>"
```

Close an issue once its fix has shipped (`projects.apps.issues.patch`; the
enum value is `CLOSED` — `RESOLVED` is rejected with INVALID_ARGUMENT):

```bash
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  "$BASE/$APP/issues/<issueId>?updateMask=state" \
  -d '{"state":"CLOSED"}'
```

Crashlytics reopens a closed issue automatically if the crash reappears on a
newer app version, so a premature close resurfaces on its own — closing is
also the regression signal for "verified fixed" tickets.
