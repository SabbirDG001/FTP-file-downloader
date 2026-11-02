# MediaDL

An Android app (Kotlin + Jetpack Compose) for downloading:

- **Social media videos and music**: YouTube, Facebook, Instagram, TikTok, X/Twitter, Vimeo, Dailymotion and the [~1,800 sites supported by yt-dlp](https://github.com/yt-dlp/yt-dlp/blob/master/supportedsites.md).
- **Files from FTP servers**: browse folders, filter, select several files and download them.
- **Web directory listings**: browse "Index of" pages on HTTP media servers.
- **Direct links**: a single movie, or a whole season folder at once (all episodes, sorted by episode number).

## Features

| Area | What it does |
| --- | --- |
| Home | Paste a link → see thumbnail, title and duration → pick a quality (1080p, 720p, … or MP3 / M4A audio) → download. Detects links you copied in other apps. |
| Playlists | Paste a playlist link (or tap "choose videos from it" on a video that's in one), tick the videos you want, pick one quality for all, and choose where to save them: a new folder (named after the playlist by default) or any folder on the phone or SD card. |
| Share to app | In YouTube / Facebook / Instagram tap **Share → MediaDL**. |
| Browser | Built-in browser with shortcuts to each site and a **Download** button for the page you're on. Logging in here lets the app download private posts you can see (cookies stay on the device). |
| Servers | FTP / web-directory browser (server settings are saved) and the Direct link / Season downloader. |
| Downloads | "Downloading" on top, "Downloaded" below. Live progress, speed and time left; pause, resume, cancel, retry; open, share or delete finished files; filters. History is saved and survives restarts. |
| Background | Downloads continue when the app is closed, with a progress notification (Pause all / Cancel all) and a notification when each file finishes. |
| Accounts | Settings → Accounts shows which sites you're logged into, with a Log in button for each. |
| Settings | Download folder, how many downloads run at once, a total speed limit, preferred video quality and audio format, per-site sub folders, clipboard detection, yt-dlp updates, browser login. |

## Project layout

```
app/src/main/java/com/example/
├── MainActivity.kt                 Handles launch + "Share to app" links
├── MediaDownloaderApplication.kt   Creates shared objects (AppContainer), prepares yt-dlp
├── data/
│   ├── model/        DownloadTask, Platform, MediaInfo, RemoteFileItem, FtpServerConfig
│   ├── local/        Room database for download history
│   ├── settings/     SettingsRepository (user preferences)
│   ├── storage/      StorageHelper (download folder, open/share/delete files)
│   ├── remote/
│   │   ├── ftp/      FtpClientManager (browse + download with resume)
│   │   ├── http/     HttpDirectoryClient (directory pages + download with resume)
│   │   └── social/   YtDlpManager, YtDlpProgress, BrowserCookieExporter
│   ├── download/     DownloadEngine (queue) + FTP / HTTP / Social downloaders
│   └── util/         Formatters, LinkUtils, ErrorMessages
├── service/          DownloadForegroundService (background downloads + notifications)
└── ui/
    ├── AppRoot.kt    Scaffold, bottom navigation, quality sheet, snackbar
    ├── navigation/   Destinations
    ├── components/   Header, folder prompt, server dialog, quality sheet, badges
    ├── screens/      home, browser, servers, downloads, settings (screen + ViewModel each)
    └── theme/
```

## Building

### Without Android Studio (on your phone)

Every push is built automatically on GitHub (see `.github/workflows/build-apk.yml`):

1. On your phone, open the repository on GitHub → **Releases** → **Latest test build**.
2. Download `MediaDL-most-phones.apk` (or `MediaDL-any-phone.apk` if that one won't install).
3. Open the file and allow "Install unknown apps" for your browser when Android asks.

If a build fails, the error is on the repository's **Actions** tab.

### With Android Studio

Open the project in Android Studio (or run `./gradlew assembleDebug`). The build produces
one APK per CPU type (`arm64-v8a` for almost all modern phones) plus a universal APK, in
`app/build/outputs/apk/`.

The social media engine (yt-dlp with Python and FFmpeg, via
[youtubedl-android](https://github.com/yausername/youtubedl-android)) makes the APK large:
roughly 50–80 MB per CPU type. The first time you fetch a link, the app unpacks the engine,
which takes a few seconds.

## Notes

- **Google Play does not allow apps that download from YouTube.** Apps like SnapTube are
  distributed as APKs outside the Play Store. Plan to share the APK directly.
- Sites change often. If downloads from a site start failing, use **Settings → Update
  download engine now** (it also updates automatically once a week).
- youtubedl-android is GPL-3.0 licensed; if you distribute the app, your app must be
  GPL-compatible and you must offer its source code.
- Only download content you own or have permission to save, and respect each site's terms.
