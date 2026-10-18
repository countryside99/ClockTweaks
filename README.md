# Clock Tweaks

A small LSPosed module that adds a few missing tricks to the **Samsung Clock app**.

Nothing here hacks into system services or touches other apps. Clock Tweaks simply hooks
into the Clock app itself, adds a menu entry or two, and does the boring work for you.
The alarms it creates are regular Clock alarms — they show up in your list, ring with
your usual sound, and follow the app's own rules (including its 100 alarm limit).

## Features

### Create wake-up range

Open the Clock app, tap the overflow menu (`⋮`) and pick **Create wake-up range** to set
up a whole batch of alarms in one go:

- **Start and end time** — for example 9:00 PM to 9:30 PM
- **Interval** — every 1, 2, 5, 10, 15, 30 or 60 minutes
- **Repeat on days of the week** — seven circular day chips (same style as OneUI), with
  quick shortcuts for *Every day*, *Weekdays* and *Weekend*. Leave them off and you get
  simple one-time alarms, just like before
- **Shared label** — optional, up to 40 characters (the app's own limit)
- **Skip existing times** — don't create an alarm where one already exists
- **Replace a previous set** — delete the old batch with the same label first
- **Live preview** — see exactly how many alarms will be created before you tap Create

Everything runs in the background with a short toast when it's done, so you can keep
using the app while the alarms are being created.

### Manage wake-up sets

The same menu has **Manage wake-up sets**, which shows the batches you created —
label, time range, interval, repeat days and how many alarms are still alive.
Select one or more and delete them as a whole; the alarms disappear from the Clock
list right away.

### Day-of-week repeat

The day picker isn't just decoration: created alarms are saved as real weekly repeats
with the exact encoding the Clock app uses, so they display correctly in the app's own
edit screen and ring on the right days. The day order follows your phone's week start
setting, and the letters adapt to your language.

## Requirements

- **LSPosed** with support for the modern Xposed API (libxposed API **102**)
- **Samsung Clock** (`com.sec.android.app.clockpackage`) — this is the only app in scope

## Install

1. Install the APK (`app-release.apk`).
2. Enable **Clock Tweaks** in LSPosed and make sure the Samsung Clock app is checked
   in its scope. (The scope is fixed to the Clock app, so nothing else can be hooked.)
3. Force-stop and reopen the Clock app — the new entries show up in the `⋮` menu.

The module APK itself has no interface; all features live inside the Clock app.

## More features coming soon...

This is just the start. Ideas already on the list:

- More batch options (randomized ranges, labels per alarm, …)
- Extra Clock app tweaks driven by feedback
- A README section that keeps growing, because features keep coming

If you have an idea, open an issue — requests are welcome.

## License

GNU General Public License v3.0 (GPL-3.0). See [LICENSE](LICENSE) for details.
