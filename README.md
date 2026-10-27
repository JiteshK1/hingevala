# Hingevala

Sends Hinge likes, each with a short comment, from an Android emulator. A local control page has Start/Stop buttons.

> Automating Hinge breaks its Terms of Service and can get your account banned. Keep the limits low.

## Run

```bash
.venv/bin/python app.py          # then open http://127.0.0.1:8000
```

First open Hinge on the emulator and go to the **Discover** tab.

If the emulator crashes (`device 'emulator-5554' not found`), the bot closes what's left of it, cold-boots it (like Android Studio's *Cold Boot Now*), reopens Hinge and carries on by itself. If the emulator tends to freeze without crashing, also run the watchdog in a second terminal. It restarts a frozen emulator and presses Start again:

```bash
.venv/bin/python tools/watchdog.py --avd Pixel_7
```

## Run on a real phone

The same bot runs on a phone, driven from this computer over adb (the screen is read through uiautomator, as on the emulator).

1. On the phone: Settings → About phone → tap **Build number** 7 times. Then Settings → System → Developer options → turn on **USB debugging** and **Stay awake** (the screen stays on while charging).
2. Connect it by USB, unlock it and allow the "Allow USB debugging?" prompt (tick *Always allow*).
3. `adb devices` shows its serial. Put it in `config.json` as `device_serial` (with the emulator closed, leaving it empty also works).
4. Open Hinge on the Discover tab, check with `.venv/bin/python tools/doctor.py`, then run `app.py` as usual.

Keep the phone plugged in and unlocked while it runs. If the screen turns off the bot turns it back on, but it can't get past a PIN lock. If the phone stops answering over adb (unplugged, debugging turned off), the bot waits up to `emulator_boot_timeout_seconds` for it to come back. Cold-booting and `tools/watchdog.py` are for the emulator only.

## How it works

- `hinge/bot.py` is the loop. It reads the screen, skips some profiles at random, picks a random photo or prompt, writes a comment, sends the like and waits a random time. It stops on any screen it doesn't recognise.
- `hinge/ui.py` holds the Hinge actions (detect the screen, like, comment, send, skip). It uses the selectors in `selectors.json`.
- `hinge/device.py` wraps uiautomator2 and makes taps and swipes look more human.
- `comments.txt` holds the comments, one per line. Edit it freely.
- `config.json` holds the limits:
  - `daily_cap`: maximum likes per day; `0` means no limit (runs until you press Stop)
  - `delay_seconds`: random gap between actions
  - `skip_probability`: share of profiles skipped at random
  - `skip_keywords`: words or phrases that make the bot skip a profile, e.g. `["he/him", "smoke regularly"]`. Matched as whole words, ignoring case, against the profile's text (name, pronouns, prompts, details). Empty list = off
  - `profile_read_scrolls`: how far down a profile the bot reads before checking `skip_keywords`
  - `ai_comments`: write each comment with Claude, answering the prompt being liked (falls back to `comments.txt` on any error). Needs `ANTHROPIC_API_KEY` set in the terminal that runs `app.py`. Also a checkbox on the control page, which takes effect from the next profile. Off is the fast mode: no full profile read, comments come from `comments.txt`, and `skip_keywords` is checked only on the screens the bot passes anyway
  - `ai_model`, `ai_style`: the Claude model, and optional extra style notes, e.g. `"tease a little, ask questions"`
  - `ai_prefer_prompts`: with AI comments on, like a prompt rather than a photo when both are on screen
  - `active_hours`: hours the bot may run, as `[start, end)`
  - `mode`: `approve` or `auto`
  - `dry_run`: when true, nothing is sent or skipped
  - `device_serial`: leave empty if only one emulator is running
  - `max_restarts_in_a_row`: when something goes wrong (comment box stuck, unknown screen, Hinge closed, an error), the bot removes Hinge from Recents and opens it fresh, up to this many times in a row before giving up
  - `auto_restart_emulator`: when the emulator crashes or stops answering, close it and cold-boot it again instead of stopping (default `true`)
  - `avd_name`: which emulator to cold-boot, e.g. `Pixel_7` (see `emulator -list-avds`). Empty = the one that was running
  - `emulator_boot_timeout_seconds`, `max_emulator_restarts_per_hour`: how long to wait for a boot, and how many restarts an hour before giving up

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
