/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package com.facebook.video.engine.api;

/**
 * Stands in for Facebook's player params, whose name survives Redex. Facebook's own toString
 * says "VideoId: " and the video's id on 577 and 580, and this one says what a test gives it.
 */
public class VideoPlayerParams {
    private final String said;
    final VideoDataSource source;

    public VideoPlayerParams(String said, VideoDataSource source) {
        this.said = said;
        this.source = source;
    }

    @Override
    public String toString() {
        return said;
    }
}
