package com.docedit.payload.request;

import javax.annotation.Nullable;

/** The POST body for creating a document. Both fields are optional. */
public record DocumentCreate(
        @Nullable String text,
        @Nullable String title) {
}
