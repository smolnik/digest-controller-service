package net.adamsmolnik.control.fallback;

import java.util.Optional;

/**
 * @author ASmolnik
 *
 */
public interface ParamsView {

    String getServiceName();

    String getInstanceType();

    String getImageId();

    String getServiceContext();

    String getServicePath();

    String getServiceFullPath();

    Optional<String> getElb();

    Optional<String> getDnsName();

    boolean waitForOOMAlarm();

}