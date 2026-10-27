# Installing SABLE on your Galaxy

SABLE isn't in the Play Store. You install the app file (an APK) yourself. There are two ways: copy the file to the phone (easiest), or install it from a PC over USB.

The current file is `out/SABLE-0.7.0-m7.apk` in this project folder (also attached to each release on GitHub when published).

---

## Way 1: copy the file to the phone (no PC tools needed)

1. Connect the phone to the PC with a USB-C cable. On the phone, tap the **USB** notification and choose **Transferring files / Android Auto**.
2. On the PC, open **This PC → Galaxy A17 → Internal storage → Download** and copy `SABLE-0.7.0-m7.apk` there.
3. On the phone, open **My Files → Downloads** and tap the APK.
4. The first time, Android asks to allow installs from My Files: tap **Settings → Allow from this source**, then go back and tap **Install**.
5. If Play Protect warns that it doesn't recognise the app, tap **More details → Install anyway**. (It's your own build, signed on your PC.)
6. Open **SABLE**. Allow **Music and audio** access (to find your songs) and **Notifications** (for the playback controls).

Updating later: install the newer APK the same way. Your library, analyses and settings are kept.

---

## Way 2: install from the PC over USB (adb)

### Turn on Developer options and USB debugging (once)
1. On the phone: **Settings → About phone → Software information**.
2. Tap **Build number** seven times. Enter your PIN if asked. You'll see "Developer mode has been turned on".
3. Go back to **Settings**. At the bottom is **Developer options**. Open it and turn on **USB debugging**.
4. Connect the phone to the PC. On the phone, tap **Allow** on "Allow USB debugging?" (tick **Always allow from this computer**).

### Install
On the PC, with Android's platform tools (`adb`) installed:
```
adb devices
adb install -r out\SABLE-0.7.0-m7.apk
```
`adb devices` should list one device marked `device`. `-r` replaces an older SABLE and keeps its data.

---

## Let SABLE work in the background (One UI battery settings)

One UI puts apps to sleep to save battery. Playback keeps going either way (it runs as a media service with a notification), but **listening to your library in the background** (the analysis while charging) can be stopped if SABLE is asleep.

1. In SABLE: **Settings (sliders icon) → Battery → Allow unrestricted**, then tap **Allow**.
   Or by hand: **Settings → Apps → SABLE → Battery → Unrestricted**.
2. Check **Settings → Battery → Background usage limits**:
   - SABLE must **not** be in **Sleeping apps** or **Deep sleeping apps**. If it is, remove it.
   - Optional: add SABLE to **Never auto sleeping apps**.
3. **Put unused apps to sleep** can stay on. Unrestricted SABLE is exempt.

What it costs: with the screen off, playback used about 2% of one CPU core on the A17 (measured, M7). Background analysis runs only while charging unless you tap **Rescan now**.

---

## For fair listening (optional)

SABLE measures and balances what it plays. Samsung's sound effects work *after* SABLE and change what you hear:
- **Settings → Sounds and vibration → Sound quality and effects**: turn off **Dolby Atmos**, **Equalizer** (or set it to Normal) and **UHQ upscaler**.
- **Adapt Sound**: set to **Off**.
- With Bluetooth headphones, any EQ in their own app also changes the sound.

You don't have to. SABLE works either way.

## After an update

Android finishes optimizing a newly installed Sable on its own, during the phone's quiet maintenance (usually overnight while charging). Until then it works normally; afterwards it opens a little faster (on the A17 about 0.25 s instead of 0.32 s).
