package com.docedit.payload.response;

import javax.annotation.Nullable;

/**
 * A lightweight listing entry: identity plus a short text preview, without the
 * full redline segments — so listing many documents stays cheap.
 */
public record DocumentSummary(String id, @Nullable String title, String preview) {
}
