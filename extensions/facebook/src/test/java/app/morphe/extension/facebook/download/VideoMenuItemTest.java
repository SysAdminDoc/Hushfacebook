/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;
import com.facebook.video.engine.api.VideoDataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.net.InetAddress;
import java.util.regex.Pattern;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

/**
 * Download to phone in a post's menu: added below Facebook's own rows only for a post holding a
 * video, only while its switch is on and Hushfacebook runs, and a tap that saves through the one
 * pipeline every save uses or says it couldn't, without an address in the report.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class VideoMenuItemTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String ATTACHMENTS = VideoMenuItemForTests.ATTACHMENTS;
    private static final String MEDIA = VideoMenuItemForTests.MEDIA;
    private static final String ATTACHED_STORY = VideoMenuItemForTests.ATTACHED_STORY;
    private static final int ICON = VideoMenuItemForTests.ICON;

    /** A video file on Meta's CDN, named the way its files are: the middle number is an id. */
    private static final String CLIP = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/"
            + "449237416_1234567890123456_1234567890123456789_n.mp4?efg=x&oh=1&oe=2";

    /** The same video's 720p track, as the player's manifest lists it. */
    private static final String TRACK = "https://video-iad3-1.xx.fbcdn.net/o1/v/t2/f2/m69/"
            + "449237417_2234567890123456_2234567890123456789_n.mp4?efg=y&oh=1&oe=2";

    /** A picture on the same CDN, the kind of address a post also reaches: its thumbnail. */
    private static final String THUMBNAIL = "https://scontent-iad3-1.xx.fbcdn.net/v/t15.5256-10/"
            + "475148478_1134540631592283_1316146539584337463_n.jpg?_nc_cat=1&oh=00_AYA&oe=66F0A1B2";

    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Settings.DOWNLOAD_VIDEOS.save(true);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        ShadowToast.reset();
        // Every Meta name answers a private address, so a save that starts is refused before a
        // socket opens, and the report says which route it took.
        MediaDownload.policyForTests = new MediaUrlPolicy(host -> new InetAddress[] { InetAddress.getByName("10.9.8.7") });
    }

    @After
    public void tearDown() throws InterruptedException {
        waitForSaves();
        MediaDownload.policyForTests = null;
        PauseForTests.resume();
        Settings.DOWNLOAD_VIDEOS.resetToDefault();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static void waitForSaves() throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (MediaDownload.savesInFlight() > 0) {
            assertTrue("a save never finished", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
    }

    private Menu filled(Object post) {
        Menu menu = VideoMenuItemForTests.facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), post, ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY);
        return menu;
    }

    private static GraphQLStory post(GraphQLMedia media) {
        return new GraphQLStory(null, new GraphQLStoryAttachment(media));
    }

    private static GraphQLMedia video(String id) {
        GraphQLMedia media = new GraphQLMedia("Video");
        media.with("id", id);
        return media;
    }

    /** Taps the item the hook added, the last row, the way the menu dispatches a tap. */
    private static void tap(Menu menu) {
        MenuItem item = menu.getItem(menu.size() - 1);
        assertEquals("Download to phone", String.valueOf(item.getTitle()));
        assertTrue("the tap wasn't taken", menu.performIdentifierAction(item.getItemId(), 0));
    }

    private static String report() throws InterruptedException {
        waitForSaves();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return LogBufferManager.buildExportText();
    }

    // ---- The menu -------------------------------------------------------------------------------

    @Test
    public void aVideoPostGetsTheItemBelowFacebooksOwnRows() {
        Menu menu = filled(VideoMenuItemForTests.videoPost());

        assertEquals(3, menu.size());
        assertEquals("Facebook's Save row moved or changed", "Save video", String.valueOf(menu.getItem(0).getTitle()));
        assertEquals(1, menu.getItem(0).getItemId());
        assertEquals("Facebook's own Download row moved or changed", "Download video",
                String.valueOf(menu.getItem(1).getTitle()));
        assertEquals(2, menu.getItem(1).getItemId());
        assertEquals("Download to phone", String.valueOf(menu.getItem(2).getTitle()));
        assertTrue(String.join("\n", HookStatus.report()),
                HookStatus.report().contains("Download any video: invoked 1, 1 found, 0 missing"));
    }

    @Test
    public void offOrPausedTheMenuIsFacebooksOwn() {
        Settings.DOWNLOAD_VIDEOS.save(false);
        assertEquals("the switch is off", 2, filled(VideoMenuItemForTests.videoPost()).size());

        Settings.DOWNLOAD_VIDEOS.save(true);
        for (HushfacebookPause.Reason why : new HushfacebookPause.Reason[]{HushfacebookPause.Reason.SWITCH,
                HushfacebookPause.Reason.CRASH_LOOP, HushfacebookPause.Reason.MARKER_FILE}) {
            PauseForTests.pause(why);
            assertEquals("paused by " + why, 2, filled(VideoMenuItemForTests.videoPost()).size());
        }
        PauseForTests.resume();
        assertEquals("running again", 3, filled(VideoMenuItemForTests.videoPost()).size());
    }

    @Test
    public void aPostWithoutAVideoKeepsFacebooksMenu() {
        // A photo post: its media is a Photo and names no video file.
        GraphQLMedia photo = new GraphQLMedia("Photo");
        photo.with("id", "3234567890123456");
        assertEquals(2, filled(post(photo)).size());

        // A post with no attachment, a post with an empty attachment, and something that isn't a post.
        assertEquals(2, filled(new GraphQLStory(null)).size());
        assertEquals(2, filled(new GraphQLStory(null, new GraphQLStoryAttachment(null))).size());
        assertEquals(2, filled(new Object()).size());
        assertEquals(2, filled(null).size());
        assertTrue("an ordinary post is not a miss", String.join("\n", HookStatus.report()).contains("0 missing"));
    }

    /** A share carries the video in the post it shares, and Facebook's own menu looks there. */
    @Test
    public void aSharedVideoIsFoundInThePostItShares() {
        GraphQLStory shared = VideoMenuItemForTests.videoPost();
        GraphQLStory share = new GraphQLStory(shared);
        assertEquals(3, filled(share).size());
        assertEquals(3, filled(new GraphQLStory(share)).size());
    }

    /** A menu built for one attachment of a post rather than for the post. */
    @Test
    public void anAttachmentsMenuFindsItsVideo() {
        assertEquals(3, filled(new GraphQLStoryAttachment(video("4234567890123456"))).size());
    }

    /**
     * The media says it's a video even when nothing to save is known yet, and the item is still
     * offered: the tap then says what to do. A model whose tree is gone isn't asked its type.
     */
    @Test
    public void aVideoTheMediaNamesGetsTheItemAndAReleasedOneIsNotAsked() {
        assertEquals(3, filled(post(video("5234567890123456"))).size());
        GraphQLMedia released = video("6234567890123456");
        released.released();
        assertEquals("a released model was taken for a video", 2, filled(post(released)).size());
        assertFalse("the type of a released model was read", released.readAfterRelease);
    }

    /** A getter this build doesn't have is a patch that went wrong, so it's a miss with a name. */
    @Test
    public void aGetterThisBuildMovedIsAMissNamedInHookStatus() {
        Menu menu = VideoMenuItemForTests.facebooksMenu(context);
        VideoMenuItem.add(menu, new View(context), VideoMenuItemForTests.videoPost(), ICON, "A9z", MEDIA,
                ATTACHED_STORY);

        assertEquals(2, menu.size());
        assertEquals(java.util.Collections.singletonList("Download any video: invoked 1, 0 found, 1 missing. "
                + "First missing: method " + GraphQLStory.class.getName() + "#A9z"), HookStatus.report());
    }

    /** A menu that throws on add is Facebook's problem, never a crash from the hook. */
    @Test
    public void aMenuThatRefusesTheItemIsReportedAndLeftAlone() {
        Menu refusing = (Menu) java.lang.reflect.Proxy.newProxyInstance(Menu.class.getClassLoader(),
                new Class<?>[]{Menu.class}, (proxy, method, args) -> {
                    if (method.getName().equals("add")) throw new IllegalStateException("menu closed");
                    return null;
                });
        VideoMenuItem.add(refusing, new View(context), VideoMenuItemForTests.videoPost(), ICON, ATTACHMENTS, MEDIA,
                ATTACHED_STORY);

        assertTrue(LogBufferManager.buildExportText().contains("could not add Download to phone to a post's menu"));
    }

    // ---- A tap ----------------------------------------------------------------------------------

    /**
     * No player was recorded and the post names no file: nothing is fetched, the person is told
     * what to do, and the report says why without naming anything of the video.
     */
    @Test
    public void aTapWithNothingToSaveSaysSo() throws Exception {
        Menu menu = filled(post(video("7234567890123456")));
        tap(menu);

        String report = report();
        assertEquals("Couldn't save this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
        assertTrue(report, report.contains("Download to phone tapped"));
        assertTrue(report, report.contains("nothing to save: no player of this video was recorded and the post names no file"));
        assertFalse(report, report.contains("7234567890123456"));
    }

    /**
     * The player of the same video recorded its manifest, which lists a better track than the
     * post's single file: the save takes the manifest, through the same policy every save uses,
     * which refuses it here. It falls back to the single file, refused the same way. Neither an
     * address, a file name nor an id reaches the report.
     */
    @Test
    public void aTapTakesThePlayersManifestFirstAndNamesNoAddress() throws Exception {
        String id = "8234567890123456";
        String manifest = "<MPD><Period><AdaptationSet mimeType=\"video/mp4\">"
                + "<Representation codecs=\"avc1.64001f\" width=\"1280\" height=\"720\" bandwidth=\"900000\">"
                + "<BaseURL>" + TRACK.replace("&", "&amp;") + "</BaseURL></Representation>"
                + "</AdaptationSet></Period></MPD>";
        PlayerSources.rememberVideo(new Params(id, new Source(null, manifest)), "videoId", "hd", "manifest");
        GraphQLMedia media = video(id);
        media.with("playable_url", CLIP);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("saving the video from its DASH manifest"));
        assertTrue(report, report.contains("the DASH save ended with NETWORK_ERROR"));
        assertNoAddressIn(report);
    }

    /** No player, two single files named by the post: the better one, and only as a video. */
    @Test
    public void aTapWithOnlyThePostsFilesSavesTheBetterOne() throws Exception {
        GraphQLMedia media = video("9234567890123456");
        media.with("playable_url", CLIP).with("hd_playable_url", TRACK);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("saving video mp4 ("));
        assertTrue(report, report.contains("from 2 candidate(s)"));
        assertTrue(report, report.contains("save finished: NETWORK_ERROR"));
        assertNoAddressIn(report);
    }

    /** A post whose only reachable address is a picture never saves the picture from this item. */
    @Test
    public void aThumbnailIsNeverSavedInPlaceOfTheVideo() throws Exception {
        GraphQLMedia media = video("1034567890123456");
        media.with("playable_url", THUMBNAIL);

        tap(filled(post(media)));

        String report = report();
        assertTrue(report, report.contains("nothing to save: none of the 1 addresses was a video file"));
        assertFalse(report, report.contains("saving image"));
        assertEquals("Couldn't save this video. Play it for a moment, then try again.",
                ShadowToast.getTextOfLatestToast());
        assertNoAddressIn(report);
    }

    /** A tap after the switch went off, from a menu still open, saves nothing and says nothing. */
    @Test
    public void aTapAfterTheSwitchWentOffSavesNothing() throws Exception {
        Menu menu = filled(VideoMenuItemForTests.videoPost());
        Settings.DOWNLOAD_VIDEOS.save(false);
        tap(menu);

        String report = report();
        assertTrue(report, report.contains("Download to phone tapped after its switch went off"));
        assertFalse(report, report.contains("saving"));
        assertNull("a tap on a switched-off item showed a message", ShadowToast.getTextOfLatestToast());
    }

    private static void assertNoAddressIn(String report) {
        assertFalse(report, report.contains("https://"));
        assertFalse(report, report.contains("fbcdn"));
        assertFalse(report, report.contains("_n.mp4") || report.contains("_n.jpg"));
        assertFalse(report, Pattern.compile("\\d{15}").matcher(report).find());
    }

    /** A player's params: its video's id and its source, named as the patch passes them. */
    static final class Params {
        final String videoId;
        final VideoDataSource source;

        Params(String videoId, VideoDataSource source) {
            this.videoId = videoId;
            this.source = source;
        }
    }

    static final class Source extends VideoDataSource {
        final String hd;
        final String manifest;

        Source(String hd, String manifest) {
            this.hd = hd;
            this.manifest = manifest;
        }
    }
}
