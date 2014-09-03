package net.adamsmolnik.control.fallback;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import net.adamsmolnik.exceptions.ServiceException;
import net.adamsmolnik.util.LocalServiceUrlCache;
import net.adamsmolnik.util.Log;
import net.adamsmolnik.util.OutOfMemoryAlarm;
import net.adamsmolnik.util.Scheduler;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.IamInstanceProfileSpecification;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.InstanceStatus;
import com.amazonaws.services.ec2.model.InstanceStatusSummary;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;

/**
 * @author ASmolnik
 *
 */
@Dependent
public class Fallback {

    @Inject
    private Scheduler scheduler;

    @Inject
    private OutOfMemoryAlarm oomAlarm;

    @Inject
    private LocalServiceUrlCache cache;

    @Inject
    private Log log;

    private final AmazonEC2Client ec2Client = new AmazonEC2Client();

    public <T, R> R perform(ParamsView pv, Function<String, R> send) {
        String serviceFullPath = pv.getServiceFullPath();
        Instance instance = null;
        String newDigestAppUrl = null;
        try {
            waitUntilOutOfMemoryAlarmReported();
            String type = pv.getInstanceType();
            instance = setupNewDigestInstance(type, pv.getAmiId());
            waitUntilNewInstanceGetsReady(instance, 600);
            String newInstanceUrl = fetchInstanceUrl(instance);
            newDigestAppUrl = buildAppUrl(newInstanceUrl, pv.getServiceContext());
            sendHealthCheckUntilGetHealthy(newDigestAppUrl);
            String serviceUrl = newDigestAppUrl + pv.getServicePath();
            R response = send.apply(serviceUrl);
            cache.put(serviceFullPath, serviceUrl);
            scheduleCleanup(instance, serviceFullPath);
            return response;
        } catch (Exception ex) {
            log(ex);
            if (instance != null) {
                cleanup(instance);
            }
            throw new ServiceException(ex);
        }
    }

    private void waitUntilOutOfMemoryAlarmReported() {
        int timeout = 300;
        TimeUnit unit = TimeUnit.SECONDS;
        scheduler.scheduleAndWaitFor(() -> {
            boolean oaReported = oomAlarm.isReported();
            if (oaReported) {
                log.info("OutOfMemoryAlarmReported has just arrived");
                oomAlarm.reset();
                return Optional.of(oaReported);

            }
            return Optional.empty();
        }, 15, timeout, unit);
        log.info("OOM alarm report has arrived");
    }

    private void sendHealthCheckUntilGetHealthy(String newDigestAppUrl) {
        String healthCheckUrl = newDigestAppUrl + "/hc";
        AtomicInteger hcExceptionCounter = new AtomicInteger();
        scheduler.scheduleAndWaitFor(() -> {
            try {
                URL url = new URL(healthCheckUrl);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.connect();
                int rc = con.getResponseCode();
                log.info("Healthcheck response code of " + rc + " received for " + healthCheckUrl);
                return HttpURLConnection.HTTP_OK == rc ? Optional.of(rc) : Optional.empty();
            } catch (Exception ex) {
                int c = hcExceptionCounter.incrementAndGet();
                log.err("HC attempt (" + c + ") has failed due to " + ex.getLocalizedMessage());
                log.err(ex);
                if (c > 2) {
                    throw new ServiceException(ex);
                }
                return Optional.empty();
            }
        }, 15, 300, TimeUnit.SECONDS);
    }

    private String fetchInstanceUrl(Instance instance) {
        String newInstanceUrl = instance.getPublicIpAddress();
        if (newInstanceUrl == null) {
            newInstanceUrl = ec2Client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId())).getReservations()
                    .get(0).getInstances().get(0).getPublicIpAddress();
        }
        return newInstanceUrl;
    }

    private Instance setupNewDigestInstance(String type, String imageId) {
        RunInstancesRequest request = new RunInstancesRequest();
        request.withImageId(imageId)
                .withInstanceType(type)
                .withMinCount(1)
                .withMaxCount(1)
                .withKeyName("adamsmolnik-net-key-pair")
                .withSecurityGroupIds("sg-7be68f1e")
                .withSecurityGroups("adamsmolnik.com")
                .withIamInstanceProfile(
                        new IamInstanceProfileSpecification()
                                .withArn("arn:aws:iam::542175458111:instance-profile/glassfish-40-x-java8-InstanceProfile-7HFPC4EC3Z0V"));
        RunInstancesResult result = ec2Client.runInstances(request);
        Instance instance = result.getReservation().getInstances().get(0);

        List<Tag> tags = new ArrayList<>();
        Tag t = new Tag();
        t.setKey("Name");
        t.setValue("temporary fallback server for digest-service");
        tags.add(t);
        CreateTagsRequest ctr = new CreateTagsRequest();
        ctr.setTags(tags);
        ctr.withResources(instance.getInstanceId());
        ec2Client.createTags(ctr);
        return instance;
    }

    private InstanceStatus waitUntilNewInstanceGetsReady(Instance instance, int timeoutSec) {
        return scheduler.scheduleAndWaitFor(() -> {
            String instanceId = instance.getInstanceId();
            List<InstanceStatus> instanceStatuses = ec2Client.describeInstanceStatus(new DescribeInstanceStatusRequest().withInstanceIds(instanceId))
                    .getInstanceStatuses();
            if (!instanceStatuses.isEmpty()) {
                InstanceStatus is = instanceStatuses.get(0);
                return isReady(is.getInstanceStatus(), is.getSystemStatus()) ? Optional.of(is) : Optional.empty();
            }
            return Optional.empty();
        }, 15, timeoutSec, TimeUnit.SECONDS);
    }

    private void scheduleCleanup(Instance instance, String serviceLogicalPath) {
        scheduler.schedule(() -> {
            cache.remove(serviceLogicalPath);
            cleanup(instance);
        }, 10, TimeUnit.MINUTES);
    }

    private void cleanup(Instance instance) {
        ec2Client.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instance.getInstanceId()));
    }

    private String buildAppUrl(String newInstanceUrl, String serviceContext) {
        return "http://" + newInstanceUrl + ":8080" + serviceContext;
    }

    private static boolean isReady(InstanceStatusSummary isSummary, InstanceStatusSummary ssSummary) {
        return "ok".equals(isSummary.getStatus()) && "ok".equals(ssSummary.getStatus());
    }

    private void log(Exception ex) {
        ex.printStackTrace();
    }

}
