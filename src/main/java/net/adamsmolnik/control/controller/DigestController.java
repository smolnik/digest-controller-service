package net.adamsmolnik.control.controller;

import java.util.concurrent.TimeUnit;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import net.adamsmolnik.exceptions.ServiceException;
import net.adamsmolnik.fallback.FallbackServerInstance;
import net.adamsmolnik.fallback.FallbackServerInstanceBuilder;
import net.adamsmolnik.fallback.FallbackSetupParams;
import net.adamsmolnik.model.digest.DigestRequest;
import net.adamsmolnik.model.digest.DigestResponse;
import net.adamsmolnik.newinstance.SenderException;
import net.adamsmolnik.sender.Sender;
import net.adamsmolnik.sender.SendingParams;
import net.adamsmolnik.util.Configuration;
import net.adamsmolnik.util.LocalServiceUrlCache;
import net.adamsmolnik.util.Log;
import net.adamsmolnik.util.Util;

/**
 * @author ASmolnik
 *
 */
@Dependent
public class DigestController {

    @Inject
    private Configuration conf;

    @Inject
    private FallbackServerInstanceBuilder fsib;

    @Inject
    private LocalServiceUrlCache cache;

    @Inject
    private Log log;

    @Inject
    private Sender<DigestRequest, DigestResponse> sender;

    private final String serviceContext = "/digest-service-no-limit";

    private final String servicePath = "/ds/digest";

    private final String serviceFullPath = serviceContext + servicePath;

    private final String basicServerDomain = "digest.adamsmolnik.com";

    private final Class<DigestResponse> responseClass = DigestResponse.class;

    private String serviceName;

    void init() {
        serviceName = conf.getServiceName();
    }

    public DigestResponse execute(DigestRequest digestRequest) {
        try {
            String basicServiceUrl = buildServiceUrl(basicServerDomain);
            String cachedServiceUrl = cache.getUrl(serviceFullPath);
            if (cachedServiceUrl != null) {
                try {
                    return sender.send(cachedServiceUrl, digestRequest, responseClass);
                } catch (Exception ex) {
                    log.err("Exception raised after giving the url (" + cachedServiceUrl + ") being already cached a try");
                    log.err(ex);
                }
            }
            return sender.trySending(basicServiceUrl, digestRequest, responseClass, new SendingParams().withNumberOfAttempts(7)
                    .withAttemptIntervalSecs(10).withLogExceptiomAttemptConsumer(log::err));
        } catch (SenderException ex) {
            log.err(ex);
            String mediumServerDomain = "medium.digest.adamsmolnik.com";
            FallbackSetupParams fsp = new FallbackSetupParams()
                    .withLabel("fallback server instance (spawn by " + Util.getLocalHost() + ") for " + serviceName).withWaitForOOMAlarm(true)
                    .withInstanceType("t2.small").withImageId("ami-7623811e")
                    .withLoadBalancerAndDnsNames("digest-service-elb-medium", mediumServerDomain).withServiceContext(serviceContext);
            FallbackServerInstance fallback = fsib.build(fsp);
            String mediumServiceUrl = buildServiceUrl(mediumServerDomain);
            try {
                DigestResponse dr = sender.trySending(mediumServiceUrl, digestRequest, responseClass, new SendingParams().withNumberOfAttempts(7)
                        .withAttemptIntervalSecs(10).withLogExceptiomAttemptConsumer(log::err));
                cache.put(serviceFullPath, mediumServiceUrl);
                fallback.scheduleCleanup(15, TimeUnit.MINUTES);
                return dr;
            } catch (Exception exNext) {
                log.err(exNext);
                fallback.scheduleCleanup(0, TimeUnit.SECONDS);
                throw new ServiceException(exNext);
            }
        }
    }

    private String buildServiceUrl(String serverDomain) {
        return "http://" + serverDomain + serviceFullPath;
    }
}
