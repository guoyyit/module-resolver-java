package com.github.guoyyit.moduleresolverjava.web;

/**
 * Request body for upserting a declaration file.
 */
public record UpsertFileRequest(String file, String content) {
}
