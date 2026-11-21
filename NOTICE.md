# Notice

WhaleSong is a modified version of [Auxio](https://github.com/OxygenCobalt/Auxio),
copyright © Alexander Capehart (OxygenCobalt) and the Auxio contributors.

Modifications are copyright © 2026 crunchbank and, like the original, are licensed under the
GNU General Public License, version 3 or (at your option) any later version. See [LICENSE](LICENSE).
This program comes with ABSOLUTELY NO WARRANTY.

Source code: https://github.com/crunchbank/WhaleSong

## Modifications

The git history records every change, with its author and date. In summary, based on Auxio
4.1.5 (upstream `dev` at 9e18f5fe6, 2026-11-14):

| File | Change |
|---|---|
| `app/build.gradle` | App ID `io.github.crunchbank.whalesong`; WhaleSong version numbers (1.0 = version code 100). |
| `res/values/donottranslate.xml`, `app/src/debug/res/values/donottranslate.xml`, `res/values*/strings.xml` | App name "WhaleSong". |
| `AndroidManifest.xml` | The cover provider's authority follows the app ID, so WhaleSong can be installed next to Auxio. |
| `res/drawable/ic_launcher_foreground.xml`, `ic_auxio_24.xml`, `ic_splash_whale.xml` (new), `res/mipmap-*/ic_launcher.png` (were `.webp`), `fastlane/metadata/android/en-US/images/icon.png`, `art/whalesong-icon.svg` (new) | New icon, a humpback whale fluke rising from the waves, copyright © 2026 crunchbank. The launch splash shows it instead of Auxio's animated logo (`res/drawable/ic_splash_anim.xml` removed). |
| `settings/AboutFragment.kt`, `res/layout/fragment_about.xml`, `res/values/whalesong.xml` (new) | About links point to WhaleSong; a "Based on Auxio" link credits the original; the supporters list and feedback email (both Auxio's own) are removed. |
| `Auxio.kt`, `util/CopyleftNoticeTree.kt` (unused) | Debug builds log normally; the copyleft warning Auxio logs for repackaged builds isn't planted, as WhaleSong is an open-source fork. |
| `patches/media-remove-stale-hagc-argument.patch` (new) | Fix for Auxio's pinned ExoPlayer fork (`media/` submodule at 717bfe2c04), which doesn't compile: a merge left a removed "HAGC" argument in `ProgressiveMediaSource`. Apply it after updating submodules. |
| `README.md`, `NOTICE.md` | WhaleSong README (replaces Auxio's) and this file. |

"Auxio" is the name of the original project. WhaleSong is not affiliated with or endorsed by it.
