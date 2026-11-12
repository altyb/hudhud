<p align="center"><img src="docs/brand/banner.png" width="100%" alt="Hudhud"></p>

<p align="center">A small Android keyboard for English and Arabic. No internet permission. Nothing leaves the phone.</p>

<p align="center"><i>The hudhud, the hoopoe, is the messenger bird of the old stories. This one carries your words only as far as the text box.</i></p>

<p align="center">
  <a href="https://github.com/altyb/hudhud/releases/latest"><img src="docs/badges/github.png" height="64" alt="Get it on GitHub"></a>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/altyb/hudhud"><img src="docs/badges/obtainium.png" height="64" alt="Get it on Obtainium"></a>
  <!-- Turn on once listed. docs/badges/fdroid.png, play.png -->
</p>

<p align="center">
  <a href="https://github.com/altyb/hudhud/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/altyb/hudhud?label=release"></a>
  <a href="https://github.com/altyb/hudhud/releases"><img alt="Downloads" src="https://img.shields.io/github/downloads/altyb/hudhud/total"></a>
  <a href="https://github.com/altyb/hudhud/stargazers"><img alt="Stars" src="https://img.shields.io/github/stars/altyb/hudhud"></a>
  <img alt="Android 8.0+" src="https://img.shields.io/badge/Android-8.0%2B-3ddc84">
  <a href="LICENSE"><img alt="Apache-2.0" src="https://img.shields.io/badge/license-Apache--2.0-blue"></a>
</p>

<p align="center"><img src="docs/screens/english.png" width="45%" alt="English layout"> <img src="docs/screens/arabic.png" width="45%" alt="Arabic layout"></p>
<p align="center"><img src="docs/screens/one-handed.png" width="45%" alt="One-handed mode"> <img src="docs/screens/emoji-search.png" width="45%" alt="Emoji search"></p>

Sister app: [Shoebill](https://github.com/altyb/shoebill), a private video player.

## Install

1. Download `hudhud-1.4.0.apk` from [Releases](https://github.com/altyb/hudhud/releases/latest). One file for every Android phone, Android 8.0 or newer.
2. Open it and allow installing from your browser or file manager if Android asks.
3. Open **Hudhud**, tap **Enable keyboard**, switch it on, then **Choose keyboard**.

Check the file before installing if you like: the SHA-256 is listed on the release page.

Using the F-Droid app? Add my own repo under Settings, Repositories: `https://altyb.github.io/fdroid/repo?fingerprint=4882F491E55FA2B5630EBA1409087C2DC2F404E8786813D0E60B13569CC1040A`. These are the same signed APKs as the GitHub releases. The apps are not in the main F-Droid catalogue yet.

**Installed a version before 1.3.0?** It will not update, because the app ID changed. In the old app save a backup in Settings. Install the new app, restore the file there, then turn the new keyboard on and remove the old one.

## Why I made it

For years I typed on Ginger Keyboard. Not for the grammar tools. I kept it because of the layout: big keys, the old Android look, numbers and symbols printed on the keys so a long-press gives you what you see. Every newer keyboard I tried felt cramped, or slow after a few weeks, or wanted an account, or showed me things I did not ask for.

What I actually use a keyboard for is simple. I type in Arabic and English all day, I copy and paste a lot, and the thing I look at most is the row of suggestions above the keys. If those suggestions are good, everything else matters less.

So I wrote my own. The rules were: it has to stay light on any Android phone, not only mine. Every feature gets an on/off switch. No internet permission at all, so it cannot send anything even by mistake. And the code has to stay small enough that one person can read all of it.

## What it does

- **Suggestions that predict.** It completes the word you are typing and guesses the next one after a space, using word frequencies and word pairs. It learns your own words over time, on the phone only.
- **Glide typing.** Slide across the letters in English or Arabic. It runs on the phone with no Google library. Other guesses wait on the strip, and backspace removes the whole word. Words on one straight row are the hard case, so check the strip for those.
- **Autocorrect that stays out of the way.** It only fixes a word when it is fairly sure, and backspace right after puts your word back.
- **Seven languages.** English, Arabic, French, Spanish, German, Portuguese and Russian, each with its own layout and dictionary. Pick yours in Settings. Next-word prediction is English and Arabic only for now.
- **English and Arabic.** Standard Arabic layout with all letters, hamza forms on long-press, and Arabic matching that ignores diacritics. Swipe the space bar to switch.
- **Cursor and delete swipes.** Hold space and drag to move the cursor. A quick flick on space still switches language. Slide on delete to remove whole words.
- **One-handed mode.** Long-press the emoji key. The free side moves the keys across or goes back to full width.
- **Edit buttons.** Undo, redo, select all, cut, copy and paste in the clipboard panel.
- **Voice.** A button there hands over to a voice keyboard you installed, such as FUTO Voice Input, for offline dictation. This app never hears anything.
- **Split layout** on tablets and wide screens.
- **Backup.** Save your learned words and settings to a file and restore them on a new phone.
- **The classic layout.** Tall keys, symbols and digits on the keys, long-press popups with accents.
- **Clipboard.** Recent copies, including images where the app you type in accepts them, pin the ones you want to keep, and a paste chip in the suggestion row for what you just copied.
- **Emoji panel** with recents and search by English or Arabic name.
- **Your colours.** Ten dark and light surfaces (charcoal, black, slate, midnight and more) with eleven accents, any combination, plus your wallpaper colour on Android 12+. Square keys, a springy press, and a live preview in Settings.
- **Both question marks.** `?` and `؟` are a long-press away in every language.
- **Calm motion.** Short fades and slides with one shared timing, never on the key press itself. Off with one switch, and off automatically when Android's "Remove animations" is on.

## Privacy

- No `INTERNET` permission. The build checks this.
- Password fields: no learning, no clipboard, no emoji history, and (on by default) the keyboard is hidden from screenshots and screen recording.
- Anything with digits, `@`, `/` or `:` is never learned (codes, card numbers, emails, links).
- Incognito switch: no learning and no clipboard history anywhere.
- Clipboard history is wiped when the screen locks (you can turn that off). Only pinned clips are saved to storage.
- Learned words stay in the app's private storage. Cloud backup and device transfer are off.
- Buttons to clear learned words and clipboard history.

## How it stays fast

The dictionary is built once into a binary trie and memory-mapped, so it is not loaded into RAM. Suggestions are computed on a worker thread and old requests are dropped while you type. The keys are drawn on a single canvas with no allocations while drawing. Learned data has hard size limits and slowly forgets words you stopped using, so it does not grow forever.

Kotlin only. The keyboard itself has no dependencies; the settings screen uses Jetpack Compose with Material 3.

Measured on a Xiaomi 17 Ultra while typing: 0.3% janky frames, median frame 5 ms. The dictionary file carries a checksum; a damaged one is rebuilt instead of crashing the keyboard.

## Build

```
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Needs JDK 17+ and the Android SDK (compileSdk 36, minSdk 26).

## How it was made

Much of this code was written with an AI coding assistant (Claude) under my direction. If something looks wrong, please open an issue.

## Credits

Word frequency lists from [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords) (built from OpenSubtitles), licensed CC BY-SA 4.0. Those word lists stay under that licence; the code is Apache 2.0. Emoji names from [Unicode CLDR](https://cldr.unicode.org). Next-word pairs counted from [Tatoeba](https://tatoeba.org) sentences, CC BY 2.0 FR. See [NOTICE](NOTICE).

## Privacy policy

[PRIVACY.md](PRIVACY.md)

## License

Apache License 2.0. See [LICENSE](LICENSE).

Rights holders can report a problem to altybanony@gmail.com.
