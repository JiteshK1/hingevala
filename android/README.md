# Hingevala for Android

The same bot as the Python one, running on the phone by itself. Full auto with comments from your list, a floating STOP button, and a Dry run switch.

Hinge hides its screen from accessibility services, so the app instead uses the phone's own adb over **Wireless debugging** (as Shizuku does) to start the same uiautomator server the Python bot uses. Needs Android 11+ and Wi-Fi.

## Install and set up (once)

1. Copy `Hingevala.apk` to the phone and open it (allow "Install unknown apps" for your file manager when asked).
2. In the app, open the **Setup** tab and follow the 4 steps. Each shows ✓ when done:
   1. **Developer options**: Settings → About phone → tap **Build number** 7 times.
   2. **USB debugging** on (no cable needed; it's what lets the bot keep running on mobile data).
   3. **Pair**, on Wi-Fi: turn on **Wireless debugging** and tick **Always allow on this network** → tap **Pair** in the app → in Wireless debugging tap **Pair device with pairing code** → pull down the Hingevala notification → **Enter pairing code** → type the 6 digits. It says "paired ✓".
      If an **Allow USB debugging?** prompt for Hingevala appears (now or on the first Start), tick **Always allow** and tap Allow.
   4. **Notifications**: allow.
3. Optional: **Check Hinge screen** (Setup tab) should log `Detected screen: (discover, )`.

## Each time

- Open the app, pick **HingeX** or **Hinge+**, tap the big **START**. It sends for real; turn on **Dry run** if you only want to watch it decide without sending.
- **Mobile data or Wi-Fi both work.** Only after a phone restart does it need Wi-Fi once: connect to any Wi-Fi (no internet needed) and press START; it turns Wireless debugging on by itself and moves adb to the phone itself, after which Wi-Fi can go off.
- Keep the phone unlocked and charging. Stop with the floating red **STOP** (drag it anywhere) or **STOP** in the notification.
- **Settings** tab: waits, random skips, daily cap, active hours, skip keywords, recovery. **Comments** tab: your comment list.
- Something wrong? Setup tab → **Share log** and send it.

## Build

```bash
cd android && ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

`selectors.json`, `config.json` and `comments.txt` are taken from the repo root at build time; `src/main/assets-u2/u2.jar` is uiautomator2 3.7's server.
