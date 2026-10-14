# Hingevala

Sends Hinge likes, each with a short comment, from an Android emulator. A local control page has Start/Stop buttons.

> Automating Hinge breaks its Terms of Service and can get your account banned. Keep the limits low.

## Run

```bash
.venv/bin/python app.py          # then open http://127.0.0.1:8000
```

First open Hinge on the emulator and go to the **Discover** tab.

If the emulator sometimes freezes or crashes, also run the watchdog in a second terminal. It restarts the emulator and presses Start again:

```bash
.venv/bin/python tools/watchdog.py --avd Pixel_7
```

## How it works

- `hinge/bot.py` is the loop. It reads the screen, skips some profiles at random, picks a random photo or prompt, writes a comment, sends the like and waits a random time. It stops on any screen it doesn't recognise.
- `hinge/ui.py` holds the Hinge actions (detect the screen, like, comment, send, skip). It uses the selectors in `selectors.json`.
- `hinge/device.py` wraps uiautomator2 and makes taps and swipes look more human.
- `comments.txt` holds the comments, one per line. Edit it freely.
- `config.json` holds the limits:
  - `daily_cap`: maximum likes per day; `0` means no limit (runs until you press Stop)
  - `delay_seconds`: random gap between actions
  - `skip_probability`: share of profiles skipped at random
  - `active_hours`: hours the bot may run, as `[start, end)`
  - `mode`: `approve` or `auto`
  - `dry_run`: when true, nothing is sent or skipped
  - `device_serial`: leave empty if only one emulator is running
  - `max_restarts_in_a_row`: when something goes wrong (comment box stuck, unknown screen, Hinge closed, an error), the bot removes Hinge from Recents and opens it fresh, up to this many times in a row before giving up

## Modes

- **Approve each** (`approve`): the page shows the photo or prompt being liked (as text, because Hinge blocks screenshots) and the drafted comment. You can edit the comment, then press Send or Skip.
- **Full auto** (`auto`): sends without asking, within the limits.
- **Dry run** (on by default): picks a photo or prompt and a comment and logs them, but never opens the comment box or presses Send or Skip. Closing an unsent comment box needs Back presses, and one extra Back leaves Hinge.

## Fixing selectors when Hinge updates

Both tools below are read-only.

```bash
.venv/bin/python tools/doctor.py                 # checks which selectors match right now
.venv/bin/python tools/map_screen.py discover    # saves captures/discover.{xml,txt}
```

Open the screen you want in Hinge, run `map_screen.py`, find the button in the `.txt` file and update `selectors.json`.

> Full setup guide, with diagrams and screenshots: [SETUP_GUIDE.md](SETUP_GUIDE.md)
