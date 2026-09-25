/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.model;

import com.facebook.graphql.modelutil.BaseModelWithTree;

/** A test stand-in for Facebook's kept media model: a video or a photo of a post. */
public class GraphQLMedia extends BaseModelWithTree {
    public GraphQLMedia(String typeName) {
        super(typeName);
    }
}
