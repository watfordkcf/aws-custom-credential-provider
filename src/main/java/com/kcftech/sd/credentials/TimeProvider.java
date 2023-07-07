package com.kcftech.sd.credentials;

/**
 * Abstraction of system time function for testing purpose
 */
interface TimeProvider {

    /**
     * See {@link System#currentTimeMillis}
     */
    long currentTimeMillis();
}
