package net.adamsmolnik.control.fallback;

import java.util.Optional;

/**
 * @author ASmolnik
 *
 */
public interface ParamsView {

    String getInstanceType();

    String getAmiId();

    String getServiceContext();

    String getServicePath();

    String getServiceFullPath();

    Optional<String> getElb();

    Optional<String> getDnsName();

    boolean waitForOOMAlarm();

}