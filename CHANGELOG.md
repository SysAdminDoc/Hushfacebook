# Changelog

Every Hushfacebook release, newest first.

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
