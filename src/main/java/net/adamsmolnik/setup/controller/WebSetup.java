package net.adamsmolnik.setup.controller;

import java.util.Map;
import javax.inject.Inject;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import net.adamsmolnik.control.controller.DigestController;
import net.adamsmolnik.endpoint.QueueEndpoint;
import net.adamsmolnik.model.digest.DigestRequest;
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
    private Configuration conf;

    @Inject
    private OutOfMemoryAlarm oomAlarm;

    @Inject
    private QueueEndpoint queueEndpoint;

    @Inject
    private DigestController dc;

    @Inject
    private Scheduler scheduler;

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        Map<String, String> confMap = conf.getServiceConfMap();
        queueEndpoint.handleJson(request -> dc.execute(request), DigestRequest.class, confMap.get("queueIn"), confMap.get("queueOut"));
        queueEndpoint.handleVoid(request -> oomAlarm.setAsReported(), confMap.get("oomExceptionsQueue"));

    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        queueEndpoint.shutdown();
        scheduler.shutdown();
    }

}
