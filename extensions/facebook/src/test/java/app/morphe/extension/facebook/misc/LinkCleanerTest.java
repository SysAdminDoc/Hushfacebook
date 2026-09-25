/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/** What the link cleaner takes out of a link, and everything it leaves exactly as it was. */
public class LinkCleanerTest {

    private static void cleansTo(String expected, String url) {
        assertEquals(url, expected, LinkCleaner.clean(url));
        // Idempotent: a cleaned link has nothing left to take out.
        assertEquals("cleaning twice changed " + expected, expected, LinkCleaner.clean(expected));
    }

    private static void untouched(String url) {
        assertSame(url + " was rewritten", url, LinkCleaner.clean(url));
    }

    @Test
    public void fbclidGoesFromAnyLink() {
        cleansTo("https://example.org/a?b=1#top", "https://example.org/a?b=1&fbclid=IwAR0x#top");
        cleansTo("https://example.org/a?b=1", "https://example.org/a?fbclid=IwAR0x&b=1");
        cleansTo("https://example.org/", "https://example.org/?fbclid=IwAR0x");
        cleansTo("https://example.org/#top", "https://example.org/?fbclid=IwAR0x#top");
        cleansTo("https://example.org/", "https://example.org/?fbclid");
        cleansTo("https://example.org/", "https://example.org/?fbclid=");
    }

    /** The other pairs keep their order, a key given twice keeps both, and values keep their encoding. */
    @Test
    public void everythingElseInTheQueryStaysAsWritten() {
        cleansTo("https://example.org/?a=1&a=2&b=%20c+d&e", "https://example.org/?a=1&a=2&fbclid=x&b=%20c+d&e");
        cleansTo("https://example.org/?a=1&&b=3", "https://example.org/?a=1&&fbclid=2&b=3");
        cleansTo("https://example.org/?q=%2525", "https://example.org/?q=%2525&fbclid=1");
        untouched("https://example.org/?a=1&b=2");
        untouched("https://example.org/?fbclidx=1&xfbclid=2&FBCLID=3");
    }

    @Test
    public void anEncodedKeyIsStillTheKey() {
        cleansTo("https://example.org/?a=1", "https://example.org/?%66bclid=x&a=1");
        cleansTo("https://www.facebook.com/p?a=1", "https://www.facebook.com/p?__cft__%5B0%5D=AZX&a=1");
    }

    /** What the app adds to the links it shares comes out of Facebook's links, and only those. */
    @Test
    public void facebooksOwnKeysGoFromFacebooksLinksOnly() {
        cleansTo("https://www.facebook.com/share/p/1AbCdEf/", "https://www.facebook.com/share/p/1AbCdEf/?mibextid=WC7FNe");
        cleansTo("https://www.facebook.com/share/r/1AbCdEf/", "https://www.facebook.com/share/r/1AbCdEf/?sfnsn=mo");
        cleansTo("https://m.facebook.com/story.php?story_fbid=1&id=2",
                "https://m.facebook.com/story.php?story_fbid=1&__cft__[0]=AZX&id=2&__xts__[0]=68.ARB");
        cleansTo("https://fb.watch/abc/", "https://fb.watch/abc/?mibextid=Nif5oz");
        cleansTo("https://www.facebook.com/groups/1/permalink/2/", "https://www.facebook.com/groups/1/permalink/2/?extid=Ab3dE5gH7jK9mN1p");
        // Facebook removes mibextid from outside links itself, so it goes there too, and nothing else.
        cleansTo("https://example.org/?sfnsn=mo&__cft__[0]=AZX", "https://example.org/?mibextid=WC7FNe&sfnsn=mo&__cft__[0]=AZX");
    }

    /** Where a facebook.com/share/ link lands: the redirect adds rdid and share_url beside the post. */
    @Test
    public void aShareLinksLandingLosesWhatTheRedirectAdded() {
        cleansTo("https://www.facebook.com/someone/posts/pfbid0XAsn/",
                "https://www.facebook.com/someone/posts/pfbid0XAsn/?rdid=Ab3dE&share_url=https%3A%2F%2Fwww.facebook.com%2Fshare%2Fp%2F19oiH7iTxz%2F");
        cleansTo("https://www.facebook.com/groups/1/permalink/2/", "https://www.facebook.com/groups/1/permalink/2/?ref=share&mibextid=irwG9G");
    }

    /** Only ref=share, the app's own label, goes: ref with another value can carry what a link opens. */
    @Test
    public void refShareIsTheOnlyRefThatGoes() {
        untouched("https://www.facebook.com/msg/20531316728/?ref=payload");
        untouched("https://www.facebook.com/groups/1/?ref=bookmarks");
        untouched("https://example.org/?ref=share");
    }

    /** The legacy Open Graph keys Facebook put on outside links. */
    @Test
    public void theOpenGraphKeysGoFromAnyLink() {
        cleansTo("https://example.org/a?lang=en", "https://example.org/a?fb_action_ids=1&fb_action_types=og.likes"
                + "&fb_source=other&fb_ref=Default&action_object_map=%5B1%5D&action_type_map=%5B%22og.likes%22%5D"
                + "&action_ref_map=%5B%5D&lang=en");
    }

    /** What picks the post, photo, video, comment or file a link opens is never taken. */
    @Test
    public void aKeyThatPicksWhatOpensStays() {
        untouched("https://www.facebook.com/permalink.php?story_fbid=1&id=2&comment_id=3&reply_comment_id=4");
        untouched("https://www.facebook.com/photo/?fbid=1&set=gm.2");
        untouched("https://www.facebook.com/watch/?v=748893124646347");
        untouched("https://www.facebook.com/download/1/file.pdf?av=1&eav=AfZ&hash=AcX");
        untouched("https://www.facebook.com/plugins/post.php?href=https%3A%2F%2Fexample.org%2F&__s=abc");
        untouched("https://www.facebook.com/event.php?eid=1546893846301884");
        untouched("https://www.facebook.com/nd/?medium=email&mid=1&bcode=2.1734715026&aref=1734715026");
        untouched("https://www.facebook.com/ads/preferences?tracking=abc&id=1");
        untouched("https://www.facebook.com/creator_programs/signup/?referral_code=ABC123");
        untouched("https://www.facebook.com/messenger_kids/redirect/invite?child_id=1&referrer=x");
        untouched("https://lm.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2F&h=AT0x");
    }

    @Test
    public void onlyFacebooksRealHostsCount() {
        untouched("https://facebook.com.example.org/?sfnsn=mo");
        untouched("https://notfacebook.com/?sfnsn=mo");
        cleansTo("HTTPS://WWW.FACEBOOK.COM/x", "HTTPS://WWW.FACEBOOK.COM/x?sfnsn=mo");
        cleansTo("https://user@www.facebook.com:443/x", "https://user@www.facebook.com:443/x?sfnsn=mo");
        cleansTo("https://www.facebook.com./x", "https://www.facebook.com./x?sfnsn=mo");
        // A lookalike host in another script is someone else's, whatever it looks like.
        String lookalike = "https://f" + (char) 0x0430 + "cebook.com/x?sfnsn=mo";
        untouched(lookalike);
    }

    /** A host and a query in other scripts are left in the characters they came in. */
    @Test
    public void unicodeHostsAndValuesSurvive() {
        String host = "https://b" + (char) 0x00FC + "cher.example/";
        cleansTo(host + "?q=" + (char) 0x00FC, host + "?q=" + (char) 0x00FC + "&fbclid=1");
    }

    @Test
    public void theFragmentIsNeverTheQuery() {
        untouched("https://example.org/a#x?fbclid=1");
        cleansTo("https://example.org/a?b=1#x?fbclid=1", "https://example.org/a?b=1&fbclid=2#x?fbclid=1");
    }

    /** Anything that isn't a web link, or can't be read, goes out as it came. */
    @Test
    public void whatItCantReadItLeavesAlone() {
        assertNull(LinkCleaner.clean(null));
        untouched("");
        untouched("not a link?fbclid=1");
        untouched("mailto:someone@example.org?fbclid=1");
        untouched("intent://x?fbclid=1#Intent;end");
        untouched("https://example.org/no-query");
        cleansTo("https://example.org/?%zz=1", "https://example.org/?%zz=1&fbclid=2");
        cleansTo("https://[2001:db8::1]:8443/?a=1", "https://[2001:db8::1]:8443/?a=1&fbclid=2");
    }
}
