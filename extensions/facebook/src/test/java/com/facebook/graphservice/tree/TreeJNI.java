/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.graphservice.tree;

/**
 * A test stand-in for Facebook's JNI tree. The GenAI rule reads its public {@code mTypeTag} by
 * reflection, so a field of that name and type is all it needs.
 */
public class TreeJNI {
    public final int mTypeTag;

    public TreeJNI(int typeTag) {
        this.mTypeTag = typeTag;
    }
}
