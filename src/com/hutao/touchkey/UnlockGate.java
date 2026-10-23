package com.hutao.touchkey;

import java.util.concurrent.atomic.AtomicBoolean;

final class UnlockGate {
    static final AtomicBoolean OPENING=new AtomicBoolean(false);
    private UnlockGate(){}
}
