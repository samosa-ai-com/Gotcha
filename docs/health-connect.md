# Health Connect

Health Connect is Android's on-device store for health and fitness data. It is **not** a
connector: there is no account, no token and no network — the data never leaves the phone,
and the app only ever reads it.

Whatever fitness apps the user has (Fitbit, Samsung Health, Google Fit, Strava, a watch
companion app…) write into Health Connect, so reading it once covers all of them.

## Availability

| Android version | How it ships |
|---|---|
| 14 (API 34) and above | Built into the platform |
| 13 and below | A separate **Health Connect** app from the Play Store |

`get_health_summary` and `get_health_records` detect this. If no provider is present they
return an error telling the user to install or update Health Connect rather than failing
obscurely; tapping the permission entry in Settings opens the Play listing.

## Permissions

Health Connect uses its own `android.permission.health.*` grants, requested through Health
Connect's own screen rather than the standard Android runtime dialog. The app declares
read-only permissions for: steps, distance, active calories, sleep, heart rate, resting heart
rate, weight and exercise sessions.

Grant them from **Settings → Permissions → Health → Health Connect**, or by running a health
tool and letting it open the screen. Users can grant any subset — metrics with no permission
(or no data) are simply left out of the summary rather than reported as zero.

The Settings row reflects the **last known** grant state. Health Connect only reports grants
from a suspend call, which the synchronous permission list cannot make, so the row is
refreshed whenever a health tool runs or the permission screen returns.

## Tools

| Tool | Use for |
|---|---|
| `get_health_summary(days)` | Totals and averages over a window — "how did I sleep this week", "how active have I been". |
| `get_health_records(type, days)` | Individual records — "which days did I hit 10 000 steps", "when did I work out". Types: `steps`, `distance`, `calories`, `sleep`, `heart_rate`, `resting_heart_rate`, `weight`, `exercise`. |

Both are read-only in this version. The agent never writes to the user's health record.

## Before publishing to Google Play

Apps that read Health Connect data must complete Google's **health-data declaration form**
and be approved before they can be distributed on Play. Sideloaded and internally distributed
builds are unaffected. This does not block development, but it is a release-time
prerequisite — worth knowing before planning a Play listing.

## Manual end-to-end check

1. On a device **without** Health Connect: run `get_health_summary` and confirm it steers to
   installing it rather than crashing.
2. Grant permissions, then `get_health_summary` → figures match what the Health Connect app
   itself reports for the same window.
3. Revoke one data type in Health Connect → that metric disappears from the summary instead
   of showing zero.
4. Revoke everything → the tool reports that permissions are needed and reopens the screen.
5. `get_health_records("sleep", 7)` → individual sessions with start, end and duration.
6. `get_health_records("nonsense")` → a clear error listing the valid types.
