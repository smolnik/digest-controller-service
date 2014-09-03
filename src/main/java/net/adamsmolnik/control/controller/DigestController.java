package net.adamsmolnik.control.controller;

import java.util.concurrent.TimeUnit;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import net.adamsmolnik.control.fallback.Fallback;
import net.adamsmolnik.control.fallback.Params;
import net.adamsmolnik.exceptions.ServiceException;
import net.adamsmolnik.model.digest.DigestRequest;
import net.adamsmolnik.model.digest.DigestResponse;
import net.adamsmolnik.util.LocalServiceUrlCache;
import net.adamsmolnik.util.Log;

/**
 * @author ASmolnik
 *
 */
@Dependent
public class DigestController {

    @Inject
    private Fallback fallback;

    @Inject
    private LocalServiceUrlCache cache;

    @Inject
    private Log log;

    private final String serviceContext = "/digest-service-no-limit";

    private final String servicePath = "/ds/digest";

    private final String basicServerAddress = "http://digest.adamsmolnik.com";

    public DigestResponse execute(DigestRequest digestRequest) {
        try {
            String serviceFullPath = serviceContext + servicePath;
            String url = basicServerAddress + serviceFullPath;
            String serviceUrl = cache.getUrl(url);
            if (serviceUrl != null) {
                try {
                    return trySending(digestRequest, serviceUrl, 2, 5);
                } catch (Exception ex) {
                    log.err("exception after giving the url being already cached a try");
                    log.err(ex);
                }
            }
            return trySending(digestRequest, url, 7, 10);
        } catch (Exception ex) {
            log.err(ex);
            Params params = new Params().withInstanceType("t2.micro").withAmiId("ami-e4ba1d8c").withServiceContext(serviceContext)
                    .withServicePath(servicePath);
            return fallback.perform(params, (urlNext) -> {
                try {
                    return trySending(digestRequest, urlNext, 7, 5);
                } catch (Exception exNext) {
                    log.err(exNext);
                    params.withInstanceType("t2.small").withAmiId("ami-2e0ea946");
                    return fallback.perform(params, (urlNextNext) -> {
                        try {
                            return trySending(digestRequest, urlNextNext, 7, 5);
                        } catch (Exception exNextNext) {
                            log.err(exNextNext);
                            throw new ServiceException(exNextNext);
                        }
                    });
                }
            });
        }
    }

    private DigestResponse trySending(DigestRequest digestRequest, String digestServiceUrl, int numberOfAttempts, int attemptIntervalInSecs)
            throws Exception {
        int attemptCounter = 0;
        Exception exception = null;
        while (attemptCounter < numberOfAttempts) {
            ++attemptCounter;
            try {
                return send(digestRequest, digestServiceUrl);
            } catch (Exception ex) {
                if (attemptCounter == numberOfAttempts) {
                    throw ex;
                } else {
                    log.info("Attempt (" + attemptCounter + ") to send failed for " + digestRequest + " with reason: " + ex.getLocalizedMessage());
                }
                exception = ex;
            }
            TimeUnit.SECONDS.sleep(attemptIntervalInSecs);
        }
        throw exception;
    }

    private DigestResponse send(DigestRequest digestRequest, String digestServiceUrl) {
        Client client = ClientBuilder.newClient();
        Entity<DigestRequest> request = Entity.json(digestRequest);
        Response response = client.target(digestServiceUrl).request().post(request);
        return response.readEntity(DigestResponse.class);
    }
}
