package com.marcp.directauth.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TotpEngineTest {
    @Test
    void recoveryCodesAreOneTimeValues() {
        List<String> codes = TotpEngine.generateRecoveryCodes();
        assertEquals(8, codes.size());

        String stored = TotpEngine.hashRecoveryCodes(codes);
        String remaining = TotpEngine.consumeRecoveryCodeAndGetRemaining(stored, codes.getFirst());

        assertNotNull(remaining);
        assertNull(TotpEngine.consumeRecoveryCodeAndGetRemaining(remaining, codes.getFirst()));
        assertNotNull(TotpEngine.consumeRecoveryCodeAndGetRemaining(remaining, codes.get(1)));
    }

    @Test
    void malformedTotpInputIsRejected() {
        assertFalse(TotpEngine.verify(null, "000000", 1, 30));
        assertThrows(IllegalArgumentException.class,
                () -> TotpEngine.verify("not-base32", "000000", 1, 30));
        assertFalse(TotpEngine.verify("JBSWY3DPEHPK3PXP", "12345", 1, 30));
    }
}
