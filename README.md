# Boresight

A general-purpose Android radio and diagnostics toolbox. It grew out of DAS
commissioning work, but most of it is not about DAS: it is about getting a phone to say
what it actually knows, across cellular, Wi-Fi, Bluetooth, GNSS, NFC, USB, the
filesystem and the network stack. Expect a toolbox rather than one tool with one
purpose.

Built without Gradle. `build.ps1` drives `aapt2`, `javac`, `d8`, `zipalign` and
`uber-apk-signer` directly, which keeps the whole toolchain visible and the build a few
seconds rather than a few minutes.

## Tested on two phones

Everything here has been run on a **Galaxy S22 Ultra (Android 15)** and a **Galaxy S25
Ultra (Android 17)**, on AT&T and T-Mobile in the United States. That is the whole of
it: two phones, one manufacturer, one country.

Android does not require a phone to report most of what this app reads. Whether a modem
fills in the neighbour list, whether `CellInfo` carries a timing advance, what the
physical channel configuration contains, how hard Wi-Fi scans are throttled, whether
BLE results arrive with the screen off — each of those is left to the manufacturer, and
they answer differently. It changes by chipset, by Android version, by carrier, and
sometimes between builds on the same handset.

The code treats a missing field as missing and says so. That behaviour has been
exercised on exactly two devices.

So: if a screen is blank or a number reads as a dash on your phone, that is the
designed response to a platform that would not answer — but whether it is the *right*
response on your hardware is genuinely unknown. Reports from other devices are the most
useful thing anyone could send.

## Installing it

Download the APK from the [Releases](../../releases) page on the phone and open it.
That is the whole process — no adb, no developer options, no cable. Every build is
signed with the same key, so a new one installs over the old one and keeps your data.

Android will warn that it came from outside the Play Store. That warning is correct:
this is an unsigned-by-Google binary from a stranger on the internet, and you should
want to know where it came from.

### Checking it before you install

Every release lists the SHA-256 of its APK, and GitHub shows the same digest on the
asset itself. Verify the file you downloaded matches, and submit that hash to VirusTotal
if you want a second opinion — a hash lookup tells you what dozens of engines already
think, without uploading anything or running a scan on your phone.

```bash
sha256sum boresight-*.apk                 # Linux, macOS, Termux
certutil -hashfile boresight-*.apk SHA256 # Windows
```

Play Protect may also offer to send the app to Google for a security check. Sending it
is a reasonable thing to do and this project has no objection — an unknown binary
getting looked at is the system working. It is not required to install, and declining
only means Google has not seen this particular build.

## Building it yourself

Needs a JDK, the Android SDK build-tools and platform, and `uber-apk-signer`. Paths are
at the top of `build.ps1`.

```powershell
.\build.ps1            # build and sign
.\build.ps1 -Install   # also install over adb
.\build.ps1 -Run       # also launch
.\test\run.ps1         # desktop tests
```

### Tests

`test/run.ps1` runs the parts that are pure Java on the desktop JVM — the treemap
layout, the `du` parser, the storage colour rules and the walk-test interpolation.

These exist because all four **fail quietly**: a wrong treemap still draws a plausible
picture, a wrong parse still builds a plausible tree, and misplaced survey readings
still render a convincing heatmap. None of it is caught by looking at the screen, so it
is checked against invariants instead.

## What it does

**Radio** — serving cell and neighbours with traces, CQI and modulation, network
transport and capabilities, and the full platform dump.

**Survey** — a cumulative session log of every distinct carrier, band, DL/UL pair and
PCI seen on a walk; an indoor walk test that places readings on a floor plan; a map with
sightings, tracks and estimated tower positions; recording with privacy controls; and a
surveillance-signature detector.

**Tools** — ping, traceroute and port scan; SSH and SFTP; a shell console that reports
what the device actually permits; and a file browser with a hex editor.

**Device** — GNSS sky view, Starlink direct-to-cell pass prediction, every sensor the
phone exposes, a task manager, package and OTA control, a storage treemap, and USB port
state.

## Shizuku is optional

Everything works on the app's own uid. When Shizuku happens to be installed, running and
granted, commands are raised to the shell uid and several screens widen — `dumpsys`, the
settings database, other apps' `Android/data`, the full process list. Nothing is gated
behind it; a feature either works unprivileged or says plainly what it would need.

`Vfs` is the single read layer that makes that transparent: ask for a path, get the
contents, without choosing a uid or handling the refusal.

## Credits

The About screen inside the app lists everything borrowed, with what was taken and under
what terms. In short: the look follows Dave Plummer's TMOG and reads its `.tmogskin`
format; SSH is the mwiede fork of JSch; the satellite propagator is SGP4 implemented from
Spacetrack Report No. 3; map tiles are OpenStreetMap.

## What is not in this repository

No survey data, no floor plans, no client drawings, no signing keys, and no captures off
a device. See `.gitignore`. Survey output lives on the phone and goes where you send it,
not here.
