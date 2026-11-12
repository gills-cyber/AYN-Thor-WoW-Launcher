# Changes in this fork (2.7.0.1)

Stability fixes on top of upstream v2.7.0. None of these could be tested on a device before
release; they come from reading the source.

## Fixes

- **App-wide freeze while the game is suspended.** The X server writes to its clients over blocking
  sockets. A suspended game never reads, so once the socket filled up (trackpad movement or pad
  buttons while paused) the write blocked forever with the X server locks held, on the UI thread.
  The app froze, including its Resume button. Output for a suspended game is now held in memory and
  sent on resume, and pointer movement is dropped while suspended.
- **Game left frozen behind an unlabeled button.** The container used the "manual" suspend policy,
  so every time the app lost the screen or the menu opened, the game stayed stopped until a small
  white button was tapped. It now uses "auto": the game resumes by itself.
- **Pad buttons going dead (System, Close).** A pad button whose key was still marked as held was
  ignored until the pad was rebuilt. System and Close both send Escape, so they could block each
  other. A press now always goes through.
- **Audio never coming back after a pause.** A failed or timed-out PulseAudio pause/resume left the
  sink suspended for the rest of the session. Resume now always un-suspends and retries.
- **Stale pause state** from a session closed while suspended is cleared before the next launch.
- **Activity restarts mid-game** on display, density, font size and dark mode changes are avoided.
- **Low storage guard.** The launcher refuses to start the game with almost no free storage, since
  cut-short writes break the Wine prefix and destabilize the device.
- **Old update APKs** in the app cache are deleted instead of piling up.

## Performance

- A **Frame cap** setting on the launcher, per game (30, 45, 60, 90, 120 or "Game's own"). It
  defaults to 60 fps, with 30 in the background, to limit heat and thermal throttling over long
  sessions. The launcher writes it into Config.wtf at each launch; "Game's own" leaves WoW's
  settings alone.
- X output buffers grow geometrically instead of being recopied on every write when full.

## Other

- The in-app updater checks this fork's releases, so an upstream release does not replace these fixes.

## Added after review (2.7.0.1)

- **The X server never blocks on a client that is not reading**, suspended or not. Output that does
  not fit in the client's socket is kept in order and sent by a background thread. This covers the
  game being busy (loading screens, long hitches) while input keeps arriving, which previously could
  hang the UI thread. Pointer motion is dropped while a client is far behind.
- Held-back output is discarded when a client disconnects, so it can never be written to a reused
  file descriptor.
- Key auto-repeat pauses while the game is suspended.
- The second-screen pad is rebuilt if Android dismisses it after a display change.

## Diagnostics and comfort (2.7.0.1)

- **Freeze reports.** If the app stops responding for more than four seconds, a report of what every
  thread was doing, plus free memory and storage, is written to
  `Android/data/app.aynthorwow/files/freeze_logs/` (the newest five are kept).
- **Low storage warning during play** (under 200 MB free), and the launcher shows free storage.
- **The game is stopped 1.5 seconds after the app loses the screen**, not at once, so brief
  interruptions no longer stop and restart it.
