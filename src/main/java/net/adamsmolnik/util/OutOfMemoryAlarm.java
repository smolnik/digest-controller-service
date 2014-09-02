package net.adamsmolnik.util;

import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Singleton;

/**
 * @author ASmolnik
 *
 */
@Singleton
public class OutOfMemoryAlarm {

    private final AtomicBoolean reported = new AtomicBoolean();

    public void setAsReported() {
        reported.set(true);
    }

    public boolean isReported() {
        return reported.get();
    }

    public void reset() {
        reported.set(false);
    }

}
