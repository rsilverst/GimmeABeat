# GimmeABeat

A Wear OS + phone companion app that matches Spotify track tempo (BPM) to your
live workout signal. A Wear OS watch streams heart rate (or step cadence) to the
phone, which auto-selects and plays tempo-matched Spotify tracks in real time.

## Setup

API keys are kept out of version control via `local.properties` (git-ignored).
Before your first build:

```sh
cp local.properties.template local.properties
```

Then fill in the two keys in `local.properties`:

| Key | Where to get it |
|-----|-----------------|
| `spotifyClientId` | [Spotify Developer Dashboard](https://developer.spotify.com/dashboard) |
| `getSongBpmApiKey` | [getsongbpm.com/api](https://getsongbpm.com/api) |

These are read in `app/build.gradle.kts` and exposed as
`BuildConfig.SPOTIFY_CLIENT_ID` / `BuildConfig.GET_SONG_BPM_API_KEY`. The Spotify
integration uses the OAuth **PKCE** flow, so no client *secret* is required.

> Both keys are client-side and are compiled into the APK. The Spotify client ID
> is public by design; the getsongbpm key has no server-side equivalent yet, so
> treat it as low-sensitivity until/unless calls are proxied through a backend.

## Building

```sh
./gradlew :app:assembleDebug     # phone app
./gradlew :wear:assembleDebug    # Wear OS app
```

Emulator/device testing notes (synthetic heart-rate providers, watch↔phone
pairing, ADB commands) live in [`scratch.md`](scratch.md).
