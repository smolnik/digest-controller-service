package net.adamsmolnik.control.fallback;

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

}