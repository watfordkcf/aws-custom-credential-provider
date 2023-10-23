package com.kcftech.sd.credentials;

class SystemTimeProvider implements TimeProvider {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
