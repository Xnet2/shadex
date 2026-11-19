package com.shadex.app;

/**
 * Which build this is. build.sh rewrites this file in its work folder for the root build
 * (VARIANT=root); the source in the repo is always the regular build.
 */
final class Variant {
    private Variant() {}

    static final boolean ROOT = false;
}
