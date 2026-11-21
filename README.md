<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h1 align="center"><b>WhaleSong</b></h1>
<h4 align="center">A clean, minimal music player for Android.</h4>

WhaleSong is a fork of [Auxio](https://github.com/OxygenCobalt/Auxio) by Alexander Capehart.
It keeps Auxio's fast, simple design, Media3 ExoPlayer playback, gapless playback and ReplayGain
support.

WhaleSong is not affiliated with or endorsed by the Auxio project. Please report WhaleSong
problems here, not to Auxio.

## Building

Clone with submodules (`git clone --recursive`, or `git submodule update --init --recursive`),
apply WhaleSong's fix to the pinned ExoPlayer fork
(`git -C media apply ../patches/media-remove-stale-hagc-argument.patch`), then build with JDK 21 and the Android SDK. Gradle installs the NDK it needs; CMake and Ninja
come from the SDK's `cmake` package:

```
export ANDROID_HOME=~/Library/Android/sdk
export PATH="$ANDROID_HOME/cmake/4.1.2/bin:$PATH"
./gradlew assembleDebug
```

## Licence

WhaleSong, like Auxio, is free software under the GNU General Public License, version 3 or
(at your option) any later version. See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md), which lists
what WhaleSong changes.
