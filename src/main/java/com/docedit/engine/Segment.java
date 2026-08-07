package com.docedit.engine;

/** A run of text sharing one redline state; the unit the frontend renders. */
public record Segment(String text, SegmentType type) {
}
