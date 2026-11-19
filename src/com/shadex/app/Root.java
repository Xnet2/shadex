package com.shadex.app;

import java.util.concurrent.TimeUnit;

/**
 * Root-only: turns the system notification shade off with
 * `cmd statusbar send-disable-flag statusbar-expansion`, so only ShadeX opens from the top.
 * The flag lasts until it is cleared or the phone restarts; the service applies it again every
 * time it starts and clears it when it stops, so the phone is never left without any shade.
 *
 * Only used in the root build (Variant.ROOT). Call off the main thread: `su` may wait for the
 * user to grant root.
 */
final class Root {
    private Root() {}

    /** Runs one command as root; true if it finished with exit code 0 within 15 seconds. */
    static boolean exec(String cmd) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                ShadeService.log("root: timed out (" + cmd + ")");
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            ShadeService.log("root: " + e.getClass().getSimpleName());
            return false;
        } finally {
            if (p != null) p.destroy();
        }
    }

    /** Blocks (true) or allows (false) the system shade. */
    static boolean blockShade(boolean block) {
        boolean ok = exec(block
                ? "cmd statusbar send-disable-flag statusbar-expansion"
                : "cmd statusbar send-disable-flag");
        ShadeService.log("root: system shade " + (block ? "blocked" : "allowed") + (ok ? "" : " - FAILED"));
        return ok;
    }
}
