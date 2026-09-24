/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The one colour rule the resolver hooks and the parseColor reroute share. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class AmoledThemeTest {
    /** Stands in for Facebook's colour token enums: only the constant names matter. */
    enum Token { CARD_BACKGROUND, WASH, DIVIDER, PRIMARY_TEXT }

    private static final int BLACK = 0xFF000000;

    @Test
    public void aDarkGreyBackgroundTurnsBlack() {
        assertEquals(BLACK, AmoledTheme.apply(0xFF252728, Token.CARD_BACKGROUND));
        assertEquals(BLACK, AmoledTheme.apply(0xFF101011, Token.WASH));
    }

    /** The mutation controls: a divider, a lighter grey, a tinted banner and text keep theirs. */
    @Test
    public void everythingElseKeepsItsColour() {
        assertEquals("a divider token", 0xFF252728, AmoledTheme.apply(0xFF252728, Token.DIVIDER));
        assertEquals("above the dark threshold", 0xFF3A3B3C, AmoledTheme.apply(0xFF3A3B3C, Token.CARD_BACKGROUND));
        assertEquals("a dark colour with a hue", 0xFF1A2A10, AmoledTheme.apply(0xFF1A2A10, Token.CARD_BACKGROUND));
        assertEquals("light mode's white card", 0xFFFFFFFF, AmoledTheme.apply(0xFFFFFFFF, Token.CARD_BACKGROUND));
        assertEquals("no token to go on", 0xFF252728, AmoledTheme.apply(0xFF252728, "CARD_BACKGROUND"));
    }

    @Test
    public void aServerColourStringIsJudgedByItsValue() {
        assertEquals(BLACK, AmoledTheme.parseColor("#FF252728"));
        assertEquals(0xFF3A3B3C, AmoledTheme.parseColor("#3A3B3C"));
        assertEquals("a translucent scrim stays", 0x80252728, AmoledTheme.parseColor("#80252728"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void aStringThatIsNoColourThrowsAsBefore() {
        AmoledTheme.parseColor("not a colour");
    }
}
