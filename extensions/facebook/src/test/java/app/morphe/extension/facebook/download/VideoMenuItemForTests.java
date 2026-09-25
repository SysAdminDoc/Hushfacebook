/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import android.content.Context;
import android.view.Menu;
import android.view.View;
import android.widget.PopupMenu;

import com.facebook.graphql.model.GraphQLMedia;
import com.facebook.graphql.model.GraphQLStory;
import com.facebook.graphql.model.GraphQLStoryAttachment;

import org.robolectric.RuntimeEnvironment;

/**
 * The video menu hook, for a test outside this package: one post menu Facebook has filled, handed
 * to the hook the way the patch hands it over.
 */
public final class VideoMenuItemForTests {
    private VideoMenuItemForTests() {
    }

    /** The getter names the fakes carry, standing in for the ones the patch reads from a build. */
    static final String ATTACHMENTS = "A0n";
    static final String MEDIA = "A01";
    static final String ATTACHED_STORY = "A04";

    /** The icon the patch borrows from Facebook's own Download video row. */
    static final int ICON = 0x7f1a02de;

    /** A menu as Facebook fills it for a video post: its Save row and its own Download row. */
    static Menu facebooksMenu(Context context) {
        Menu menu = new PopupMenu(context, new View(context)).getMenu();
        menu.add(Menu.NONE, 1, Menu.NONE, "Save video");
        menu.add(Menu.NONE, 2, Menu.NONE, "Download video");
        return menu;
    }

    /** A post holding one video whose post names its single file. */
    static GraphQLStory videoPost() {
        GraphQLMedia video = new GraphQLMedia("Video");
        video.with("id", "1234567890123456")
                .with("playable_url", "https://video.xx.fbcdn.net/v/t42.1790-2/sd.mp4?_nc_cat=1");
        return new GraphQLStory(null, new GraphQLStoryAttachment(video));
    }

    /** Fills a post menu for a video post and says whether the hook added its item. */
    public static boolean addsAnItem() {
        Context context = RuntimeEnvironment.getApplication();
        Menu menu = facebooksMenu(context);
        int before = menu.size();
        VideoMenuItem.add(menu, new View(context), videoPost(), ICON, ATTACHMENTS, MEDIA, ATTACHED_STORY);
        return menu.size() > before;
    }
}
