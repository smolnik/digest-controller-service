package net.adamsmolnik.setup.controller;

import java.util.Map;
import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import net.adamsmolnik.boundary.controller.SimpleSqsEndpoint;
import net.adamsmolnik.control.controller.DigestController;
import net.adamsmolnik.model.digest.DigestRequest;
import net.adamsmolnik.setup.ServiceNameResolver;
import net.adamsmolnik.util.Configuration;
import net.adamsmolnik.util.OutOfMemoryAlarm;
import net.adamsmolnik.util.Scheduler;

/**
 * @author ASmolnik
 *
 */
@WebListener("dcsSetup")
public class WebSetup implements ServletContextListener {

    @Inject
    private ServiceNameResolver snr;

    @Inject
    private Configuration conf;

    @Inject
    private OutOfMemoryAlarm oomAlarm;

    @Inject
    private SimpleSqsEndpoint endpoint;

    @Inject
    private DigestController dc;

    @Inject
    private Scheduler scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        Map<String, String> confMap = conf.getServiceConfMap(snr.getServiceName());
        endpoint.handleJson((request) -> {
            return dc.execute(request);
        }, DigestRequest.class, confMap.get("queueIn"), confMap.get("queueOut"));
        endpoint.handleVoid((request) -> {
            oomAlarm.setAsReported();
        }, confMap.get("oomExceptionsQueue"));

    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        endpoint.shutdown();
        scheduler.shutdown();
    }

}
