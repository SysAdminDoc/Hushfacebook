/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.modelutil;

import com.facebook.graphservice.tree.TreeJNI;

import java.util.HashMap;
import java.util.Map;

/**
 * A test stand-in for Facebook's tree-backed model. The GenAI rule reads a flag through the public
 * {@code getCachedBoolean(int)} by reflection. Like Facebook's, a key the model doesn't hold reads
 * false. Every read is counted, so a test can show that a switched-off rule reads nothing.
 */
public class BaseModelWithTree extends TreeJNI {
    private final Map<Integer, Boolean> booleans = new HashMap<>();
    public int reads;

    public BaseModelWithTree(int typeTag) {
        super(typeTag);
    }

    public BaseModelWithTree with(String field, boolean value) {
        booleans.put(field.hashCode(), value);
        return this;
    }

    public final boolean getCachedBoolean(int key) {
        reads++;
        Boolean value = booleans.get(key);
        return value != null && value;
    }
}
