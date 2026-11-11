# Rail Link – Android app

An Android app for **www.raillinknetworking.com**: events, stand booking, registration,
talks, exhibitor login and Junction+. It shows the live website, so anything changed on
the site appears in the app straight away.

## Download
Newest version (works with the Downloader app too):

https://github.com/use2025me/rail-link-app/releases/latest/download/RailLink.apk

Downloader code: **9947611** (aftv.news/9947611)

## What it does
- Opens raillinknetworking.com full screen, in Rail Link colours (steel blue, charcoal, crimson)
- Keeps people signed in
- Works on phones, tablets, Fire TV and Android TV (remote pointer on TVs)
- Pull down to refresh, offline screen, smooth loading
- Rail Link, Lookout Awards, Evessio, Junction+ and payment pages stay in the app;
  other links (LinkedIn, WhatsApp, PDFs elsewhere) open in the phone's own apps
- Checks for updates when it opens and installs them

## How updates work
Every change pushed to `main` builds, signs and publishes a new release. Installed apps
offer the update next time they open.

## Signing key
The key is stored encrypted in `signing/release.jks.enc` and unlocked with the repository
secret `SIGNING_PASSWORD` (key alias `raillink`). Keep a private backup of the key and
password: without them, updates won't install over the old app.

## Changing things
- Start page and which websites stay in the app: `app/src/main/java/com/raillink/app/Config.kt`
- Colours: `app/src/main/res/values/colors.xml`
- Icon and logo: `app/src/main/res/drawable-nodpi/` (made from the official Rail Link logo)
