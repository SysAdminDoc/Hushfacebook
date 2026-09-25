/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.feed;

/**
 * Stands in for a Facebook feed unit whose class Redex renamed: all the guard can read from it is
 * the GraphQL type name its {@code getTypeName()} answers, as {@code LX/3zk;} and {@code LX/3zj;}
 * answer "PaginatedPeopleYouMayKnowFeedUnit" and "StoriesTrayFeedUnit" in Facebook 580.
 */
public class TypedFeedUnit {
    private final String typeName;

    public TypedFeedUnit(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeName() {
        return typeName;
    }

    /** The "People you may know" row. */
    public static TypedFeedUnit peopleYouMayKnow() {
        return new TypedFeedUnit("PaginatedPeopleYouMayKnowFeedUnit");
    }

    /** The row of stories at the top of the feed. */
    public static TypedFeedUnit storiesTray() {
        return new TypedFeedUnit("StoriesTrayFeedUnit");
    }

    /** A unit whose type name can't be read: the call itself fails. */
    public static final class Unreadable {
        public String getTypeName() {
            throw new IllegalStateException("not materialised");
        }
    }
}
