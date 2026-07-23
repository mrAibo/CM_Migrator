package com.ibm.ecm.migration;

import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic WebGUI run reservation around the existing shared slot. */
final class WebGuiRunSlot {
    private WebGuiRunSlot() {
    }

    static boolean reserve(AtomicBoolean slot) {
        if (!slot.compareAndSet(false, true)) {
            return false;
        }
        // Reset is safe because the WebGUI run slot was exclusively reserved.
        ShutdownCoordinator.reset();
        return true;
    }

    static void rollbackBeforeThreadStart(AtomicBoolean slot) {
        slot.set(false);
    }

    static void releaseIfConfirmed(AtomicBoolean slot, boolean terminationConfirmed) {
        if (terminationConfirmed) {
            slot.set(false);
        }
    }
}
