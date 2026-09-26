# Changelog

Every Hushfacebook release, newest first.

## Unreleased

* **Facebook:** The Hushfacebook settings screen groups controls into rounded cards, gives its status a stronger header, and uses clearer blue controls in the default dark theme.
* **Facebook:** A new, opt-in switch keeps the feed in place when you return to Facebook within ten minutes. Pull to refresh and a fresh launch still work.
* **Facebook:** Hide Reels in the feed now catches Reels showcase rows filed under other feed categories and the row Facebook injects just before the feed ends. Other showcases and reels posted by people you follow stay.

## 0.1.4 (2026-09-25)

* **Facebook:** New patch, Hide Reels in the feed. It takes the rows of reels between posts out of the news feed, and the reels Facebook adds where your feed ends. A reel a friend posts stays. The patch starts off in Morphe Manager, and its switch starts on once you pick it.
* **Facebook:** Both Facebook 580.0.0.51.74 and 577.0.0.50.72 take all 19 patches. It still needs Morphe Manager 1.32.0 or newer.

## 0.1.3 (2026-09-25)

* **Facebook:** New patch, Hide Stories tray. It takes the row of stories at the top of the feed away, "Create story" included, the way Facebook builds the feed when its own tray is turned off. Stories still open from a profile, a notification or the Stories viewer.
* **Facebook:** Hide suggested and promoted posts has two new switches. "Suggested for you" hides the posts Facebook recommends from people, pages and groups you don't follow, going by the same flag Facebook's own "hide suggested posts" reads, so posts from friends and pages you follow stay. "People you may know" hides that row in the feed and leaves friend requests alone.
* **Facebook:** New patch, Hide AI-detected posts. It hides feed posts Facebook's own detection labelled as made with AI. Its switch starts off, and a post whose label can't be read stays.
* **Facebook:** New patch, Download any video. "Download to phone" in a video post's menu saves feed and Watch videos through the same checked downloader stories and reels use. A new Save folder setting names the folder saves go to, under Movies for videos and Pictures for photos, and it starts as Facebook.
* **Facebook:** New patch, Material You theme. Facebook's dark mode takes the colours of your wallpaper on Android 12 and newer, and a fixed palette on Android 11, while light mode stays as it is. Each colour keeps its lightness, so text keeps the contrast Facebook gave it. The Hushfacebook screen follows your phone's dark or light setting in the same palette. It's off unless you pick it. With AMOLED black theme in the same build the backgrounds stay black, and Material You colours the text, icons, dividers and cards.
* **Facebook:** New patch, Sanitize sharing links. It takes the tracking tags Facebook adds to the links you share or copy (mibextid, a random per-share extid, sfnsn and others) back off, with a switch of its own. A facebook.com/share/ link is made on Facebook's servers for one share, so it can still be traced back to you, and the switch says so.
* **Facebook:** Links that open in your browser leave without the fbclid tag Facebook adds to them.
* **Facebook:** Hushfacebook's text, the launcher shortcut included, is in the language Facebook itself shows. A phone set to German with Facebook set to English used to get an English screen under a German shortcut.
* **Facebook:** A settings file holding a number too large to read is refused with a message. The import used to stop without saying anything.
* **Facebook:** Every release now comes with a software bill of materials (CycloneDX) for the bundle, and a release stops if a dependency has a known advisory nobody has looked at.
* **Facebook:** Both Facebook 580.0.0.51.74 and 577.0.0.50.72 take all 18 patches. It still needs Morphe Manager 1.32.0 or newer.

## 0.1.2 (2026-09-25)

* **Facebook:** Hushfacebook settings can now export the runtime feature switches to a JSON file and import them on this phone or another one. Import shows what will change, skips names this build doesn't know, and refuses damaged or newer files without changing anything. Pause and debug logging stay local. The new flow follows the phone's language in English, German, Spanish, Indonesian, Brazilian Portuguese and Turkish.
* **Facebook:** Added a Facebook-blue Hushfacebook identity with a new icon, README hero and light or dark wordmarks. The README now leads with the product's main benefits and direct install, download and support links.
* **Facebook:** AMOLED black theme no longer breaks a screen on Facebook 580. The colour sweep rewrote some dark greys Facebook keeps as 64-bit values with a 32-bit constant, and Android refused the class that held them. Only builds with AMOLED on had it.
* **Facebook:** Story and reel saves only fetch from Meta's media servers, over HTTPS. A file reaches your gallery only once it's whole and really is the photo or video it claims to be, and nothing over 512 MB is kept. A save that fails leaves nothing behind in the gallery or the cache. The checks still let saves through behind a home router that answers DNS from a fake-IP range, as OpenClash, ShellCrash and sing-box do.
* **Facebook:** Two saves started at the same moment no longer end with one of them failing.
* **Facebook:** The settings screen says exactly what Pause turns off and what stays in while paused, and Debug logging keeps working while paused, so a paused start can still be logged. The story save's player recorder now stops while its switch is off or Hushfacebook is paused.
* **Facebook:** Retry and Back on the settings recovery page, and a screen rotation, stay inside the settings screen instead of dropping you back into Facebook.
* **Facebook:** The diagnostic report names your Facebook build and ABI and lists every patch with whether a switch runs it. It also carries failed saves and Reels filter trouble, along with any link no browser would open. File names and the ids inside them are left out. Copy quick report keeps the build line and the patch list when it has to shorten a long report.
* **Facebook:** A story or reel save shows a notification with how far it has got and a Cancel button. Cancel stops it straight away, and with two saves running each Cancel stops its own. A save Android cut short when it closed Facebook leaves nothing in the gallery, the cache or the notification shade the next time one starts.
* **Facebook:** A hook that Facebook calls before its app has started takes Facebook's own path until Hushfacebook knows whether this start runs paused. One that read a switch too early could stop Facebook from starting at all.
* **Facebook:** Everything Hushfacebook shows, from the settings screen to the save notification and the name TalkBack reads for the reel Download button, is in German, Spanish, Indonesian, Brazilian Portuguese or Turkish when the phone is, and in English otherwise. Facebook's own language doesn't change. The diagnostic report and Debug logging's error toasts stay in English.
* **Facebook:** TalkBack reads section titles as headings and says each switch is a switch, and whether it's on. Rows that do something when tapped are read as buttons. Every row's text wraps in full at any text size, where Android used to cut the longest summaries at ten lines.
* **Facebook:** Pause and safe mode take out the reel Download button and the Save item on other people's stories, which used to stay in whatever Pause said. The Download button has its own switch now, and Save any story off leaves Save only on your own stories, as unpatched.
* **Facebook:** The bundle is built with Morphe patcher 1.14.1, so it needs Morphe Manager 1.32.0 or newer. Both Facebook 580.0.0.51.74 and 577.0.0.50.72 take all 13 patches.

## 0.1.1 (2026-09-24)

* **Facebook:** The Hushfacebook settings screen is readable. Its row titles were drawn in Facebook's near-black text on the black page, and the Export diagnostic report and Clear diagnostic data rows showed up blank. Both were found on a Galaxy S25 running 0.1.0.

## 0.1.0 (2026-09-24)

* **Facebook:** First release, with 13 patches for Facebook 580.0.0.51.74 and 577.0.0.50.72 (arm64-v8a). They bring the Facebook patches of Andrew Liang's Morphe patches into one source, rewritten with the fixes below, and every one applies to both builds.
* **Facebook:** Hide sponsored posts now drops posts Facebook files as promotions too, not only sponsored ones, as FroggoMorphePatches does. Both have their own switch.
* **Facebook:** Hide sponsored posts and Hide suggested and promoted posts share one feed guard instead of each adding their own to the same Facebook method. Two guards there are how FroggoMorphePatches users got "target dex pc is not at instruction start" crashes.
* **Facebook:** Hide sponsored reels works on Facebook 580. The ad-break state that keeps retrying a failed ad lookup moved into a parent class it shares with two other states, and the patch now stops only that state's poller.
* **Facebook:** AMOLED black theme works on Facebook 580, where the colour resolver for Facebook's components split in two and the patch stopped finding it. It now finds every resolver on the class by what it does.
* **Facebook:** Download any reel works on Facebook 580, whose sidebar button factory takes one more setting than 577's. The new setting is a Facebook experiment flag, passed as off.
* **Facebook:** Reel downloads no longer depend on the Kotlin library Facebook renames. The handler used to ship a copy of Kotlin's standard library inside Facebook, over a thousand classes, and now uses Facebook's own.
* **Facebook:** A story or reel saved from its DASH tracks keeps its sound when the audio starts before zero. Some AAC tracks report their first frames at negative times, and the join stopped the sound track there, leaving a silent file.
* **Facebook:** New Hushfacebook settings, reached by long-pressing Facebook's icon. It has switches for the feed, story and Reels filters, the external browser and story saves, plus Pause Hushfacebook, debug logging, a diagnostic export and the licenses. Hushfacebook pauses itself after Facebook crashes within a minute of starting three times in a row.
* **Facebook:** The diagnostic report counts the feed posts, story ad sources and Reels items each filter saw and how many it took out, even with debug logging off. A report now shows whether a filter ran at all.
* **Compatibility:** Every patch is checked against the real Facebook 580.0.0.51.74 and 577.0.0.50.72 bundles, and the rebuilt resource table is held to Facebook's own. All 13 patches apply to both, and every stock resource still resolves.
