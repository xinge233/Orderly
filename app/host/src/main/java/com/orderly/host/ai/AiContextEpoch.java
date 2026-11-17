package com.orderly.host.ai;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.Supplier;

/** AI 供应商配置代次；切换或清除设置后，旧会话不得进入新供应商请求。 */
@Component
public class AiContextEpoch {

    private long value;

    public synchronized long current() {
        return value;
    }

    public synchronized long advance() {
        return ++value;
    }

    public synchronized void change(Runnable change) {
        change.run();
        value++;
    }

    public synchronized <T> Optional<T> runIfCurrent(long expected, Supplier<T> action) {
        if (value != expected) {
            return Optional.empty();
        }
        return Optional.ofNullable(action.get());
    }
}
