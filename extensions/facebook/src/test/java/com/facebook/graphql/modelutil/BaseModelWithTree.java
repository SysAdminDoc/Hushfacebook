/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphql.modelutil;

import com.facebook.graphservice.tree.TreeJNI;

import java.util.HashMap;
import java.util.Map;

/**
 * A test stand-in for the base of Facebook's GraphQL models. The real one reads a field out of a
 * native tree by the hash of the field's GraphQL name, and its type name out of the same tree.
 * These members are the ones the extension calls, under the names Facebook keeps: the GenAI rule
 * reads a flag through the public {@code getCachedBoolean(int)} by reflection, and the video save
 * reads strings and the type name. Like Facebook's, a boolean key the model doesn't hold reads
 * false. Every boolean read is counted, so a test can show that a switched-off rule reads nothing.
 */
public class BaseModelWithTree extends TreeJNI {
    private final Map<Integer, Boolean> booleans = new HashMap<>();
    private final Map<Integer, String> strings = new HashMap<>();
    private final String typeName;
    private boolean valid = true;
    public int reads;

    /** A model tagged with a type's hash, as the GenAI rule checks it. */
    public BaseModelWithTree(int typeTag) {
        this(typeTag, null);
    }

    /** A model with a GraphQL type name, as the video save reads it. */
    protected BaseModelWithTree(String typeName) {
        this(0, typeName);
    }

    private BaseModelWithTree(int typeTag, String typeName) {
        super(typeTag);
        this.typeName = typeName;
    }

    public BaseModelWithTree with(String field, boolean value) {
        booleans.put(field.hashCode(), value);
        return this;
    }

    /** Sets the string field named [field], the way a fetched tree holds it. */
    public BaseModelWithTree with(String field, String value) {
        strings.put(field.hashCode(), value);
        return this;
    }

    /** A model whose native tree is gone, as after Facebook releases it. */
    public BaseModelWithTree released() {
        valid = false;
        return this;
    }

    public final boolean getCachedBoolean(int key) {
        reads++;
        Boolean value = booleans.get(key);
        return value != null && value;
    }

    public final String getCachedString(int field) {
        return strings.get(field);
    }

    public final boolean isValidGraphServicesJNIModel() {
        return valid;
    }

    /**
     * Whether the type name was read after the tree was released. On a phone that read goes to
     * native code with nothing behind it, which no try block catches, so a test asks this flag
     * rather than waiting for an exception.
     */
    public boolean readAfterRelease;

    public String getTypeName() {
        if (!valid) readAfterRelease = true;
        return typeName;
    }
}