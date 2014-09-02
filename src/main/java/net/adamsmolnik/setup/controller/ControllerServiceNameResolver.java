package net.adamsmolnik.setup.controller;

import javax.inject.Singleton;
import net.adamsmolnik.setup.ServiceNameResolver;

/**
 * @author ASmolnik
 *
 */
@Singleton
public class ControllerServiceNameResolver implements ServiceNameResolver {

    @Override
    public String getServiceName() {
        return "digest-controller-service";
    }

}
