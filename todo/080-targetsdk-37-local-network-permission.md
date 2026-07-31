# targetSdk 37 + ACCESS_LOCAL_NETWORK verification (Tailscale endpoint)

- **ID:** 080
- **Created:** 2026-07-31
- **Depends on:** 079

## Context

079 deliberately stopped at targetSdk 36. This ticket is the last step of the
"latest everything" program: target Android 17 (API 37).

The blocker question, unresolved by public docs as of 2026-07-31: Android 17
enforces the `ACCESS_LOCAL_NETWORK` runtime permission for LAN traffic on
apps targeting 37 ("local network protections", enforced deep in the network
stack — TCP fails with timeouts, UDP with EPERM). The app's default endpoint
is a Tailscale IP in **100.64.0.0/10** (CGNAT, RFC 6598). Official docs
enumerate the affected scenarios but not the exact address-range list, and do
not say whether CGNAT space counts as "local". If it does, the app loses its
connection the moment targetSdk becomes 37, unless the permission is declared
and granted. (Apps targeting ≤36 keep an implicit grant — that is why 079 is
safe.)

Other target-37 changes were reviewed in 079 and don't affect this app; the
only one worth a smoke check is large-screen adaptive-layout enforcement
(verify the chat screen on a tablet/foldable emulator — the manifest locks
nothing, so it should be fine, and Louis uses a phone).

Alternatives considered: staying at targetSdk 36 forever (works — Android 17
runs target-36 apps unchanged — but abandons "latest"); requesting the
permission blindly without verifying need (bad UX — an unexplained "nearby
devices" prompt in a chat app).

## Description

1. Determine whether 100.64.0.0/10 is in Android 17's local-network range
   list. Preferred source: AOSP (Connectivity/netd local-network-protection
   implementation — the `android-source-search` skill covers this workflow).
   Secondary: any updated official doc.
2. If 100.64/10 is NOT local: bump `targetSdk` to 37, done — no permission
   work.
3. If it IS local (or the source stays inconclusive): declare
   `android.permission.ACCESS_LOCAL_NETWORK` in the manifest and request it
   at runtime in the server-settings / connection-check flow, only when the
   configured host is in a LAN-classified range, with rationale UI (the app
   is useless to Louis without this connection — handle denial with a clear
   error state in `ConnectionChecker`, mapping the timeout to a
   permission-needed message where detectable).
4. Bump `targetSdk = 36 → 37`.
5. Update `AGENTS.md`.

## Acceptance criteria

- [ ] The 100.64/10 question answered with the source recorded in this ticket.
- [ ] `targetSdk = 37`; app on an Android 17 device/emulator connects to the
  Tailscale endpoint and streams a chat turn (Louis's phone is the real
  Tailscale client — final verification is his).
- [ ] If the permission was needed: granted → works; denied → clear error
  state, no silent failure; unit tests for the request gating.
- [ ] Firebase robo green on an API 37 device.
- [ ] Release APK published to the file server per `AGENTS.md`; `AGENTS.md`
  updated.

## Out of scope

- mDNS/device-picker flows (`NsdManager` `FLAG_SHOW_PICKER`) — the app talks
  to one fixed server, not discovered devices.
- Google Play target-37 deadline (Aug 2027) — this app is sideloaded, noted
  for context only.

## Notes

- Docs: https://developer.android.com/privacy-and-security/local-network-permission
  and https://developer.android.com/about/versions/17/behavior-changes-17
- The permission belongs to the `NEARBY_DEVICES` group — if Louis already
  granted another permission in that group, no prompt appears.
