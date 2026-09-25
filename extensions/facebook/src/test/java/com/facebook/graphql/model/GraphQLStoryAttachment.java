/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.model;

/**
 * A test stand-in for Facebook's kept attachment model. Its media getter carries a name of the
 * shape Redex gives it; the patch reads the real one out of each build and hands it over.
 */
public class GraphQLStoryAttachment {
    private final GraphQLMedia media;

    public GraphQLStoryAttachment(GraphQLMedia media) {
        this.media = media;
    }

    public GraphQLMedia A01() {
        return media;
    }
}
