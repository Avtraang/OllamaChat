package io.noties.markwon;

/**
 * Stub for the resource R class that Markwon's AAR would normally generate.
 * Only the (image-async) AsyncDrawableScheduler references these ids, and that
 * path is never exercised by text-only rendering — but providing the class
 * keeps compilation and dexing fully resolved.
 */
public final class R {
    public static final class id {
        public static final int markwon_drawables_scheduler = 0x7f080001;
        public static final int markwon_drawables_scheduler_last_text_hashcode = 0x7f080002;
        private id() {}
    }
    private R() {}
}
