// QH-P05-09: Shizuku UserService fixed probe interface (ADR-006).
// Only version/probe operations. Sensor suppression and jump control
// operations will be versioned separately after P10/P12 safety gate validation.
// No arbitrary shell/script/Intent forwarding capability.
package top.hnwen17.guard;

interface IGuardUserService {
    /** Interface contract version (not app version). */
    int interfaceVersion();

    /** Self-test: device fingerprint probe summary (read-only, sanitized). */
    String selfTest();
}
