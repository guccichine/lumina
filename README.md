# Lumina

A Google Photos-style library that **files pictures into type folders**, with search, delete-by-type, and optional **Google Photos / Samsung Gallery sync**.

- Desktop: Python app (no extra packages)
- Phone: Android APK built on GitHub Actions

**Download the APK:** [latest release](https://github.com/guccichine/lumina/releases/latest)

## Run on a computer

```bash
cd lumina
./start.sh
```

Open [http://127.0.0.1:8787](http://127.0.0.1:8787).

## Sign in and sync

Open **Accounts** in the top bar.

### Google Photos

1. Create a project at [Google Cloud Console](https://console.cloud.google.com/).
2. Enable **Photos Library API**.
3. Create **OAuth client ID** credentials (Desktop app, or Web app).
4. Add this redirect URI:

   `http://127.0.0.1:8787/api/auth/google/callback`

5. Paste the client ID and secret in Lumina → **Save client** → **Sign in with Google**.
6. **Sync down** pulls photos into Lumina and files them by type.
7. **Upload library** (or select photos → **Upload to Google**) sends them to a **Lumina** album in Google Photos.

Google treats full-library Photos scopes as sensitive. If Google shows a warning, continue with your own account (testing mode) or complete app verification.

### Samsung Photos / Gallery

Samsung does **not** publish a cloud Photos API the way Google does. Galaxy phones usually keep pictures in **Gallery**, and many people back Gallery up to **Google Photos**.

- **On the APK:** Accounts → **Import from Samsung Gallery** reads the phone’s Gallery (Camera, Downloads, Screenshots).
- **On a computer:** point Lumina at a copied `DCIM` / Gallery folder, then **Import from Samsung Gallery**.
- If Gallery already backs up to Google Photos, **Sign in with Google** is the cloud sync path.

## Install the APK

1. Open [github.com/guccichine/lumina/releases/latest](https://github.com/guccichine/lumina/releases/latest)
2. Download `lumina.apk`
3. On the phone, allow install from that source
4. Open Lumina and use Accounts to connect Google or import Samsung Gallery

GitHub Actions also stores the APK as a workflow artifact named `lumina-apk`.

## What it files

People, Animals, Nature, Food, Vehicles, Screenshots, Documents, Night, Graphics, Other.

Search matches name, type, and date. Album view can **delete all photos of that type** (Trash first).

## Layout on disk (desktop)

```
photos/
  People/
  Animals/
  …
  .lumina/     # index + trash + Google tokens
```
