package com.phinvader.libjdcpp.hub;

import java.io.IOException;

/**
 * The hub refused us and asked us not to reconnect (ADC QUI with TL-1), e.g. "too few upload slots".
 * Retrying automatically can't help until the user changes something, so the app stops retrying.
 */
public class HubRefusedException extends IOException {
    public HubRefusedException(String message) {
        super(message);
    }
}
