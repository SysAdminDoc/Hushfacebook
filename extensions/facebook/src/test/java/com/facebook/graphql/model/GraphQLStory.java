/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A test stand-in for Facebook's kept post model. The GenAI rule looks the real class up by name
 * and hands a story to the accessor the patch fills in, which a test replaces. The video save
 * reads its attachment list and the post a share wraps through getters named the way Redex names
 * them; the patch hands the extension the real names.
 */
public class GraphQLStory {
    private final List<GraphQLStoryAttachment> attachments;
    private final GraphQLStory shared;

    /** A post with no attachments and no shared post, all the GenAI rule needs. */
    public GraphQLStory() {
        this(null);
    }

    public GraphQLStory(GraphQLStory shared, GraphQLStoryAttachment... attachments) {
        this.shared = shared;
        this.attachments = new ArrayList<>(Arrays.asList(attachments));
    }

    public List<GraphQLStoryAttachment> A0n() {
        return attachments;
    }

    public GraphQLStory A04() {
        return shared;
    }
}