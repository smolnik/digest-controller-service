package net.adamsmolnik.control.fallback;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.enterprise.context.Dependent;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.Response;
import net.adamsmolnik.exceptions.ServiceException;
import net.adamsmolnik.model.digest.DigestRequest;
import net.adamsmolnik.model.digest.DigestResponse;
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
    private OutOfMemoryAlarm oooAlarm;

    @Inject
    private Log log;

    private final AmazonEC2Client ec2Client = new AmazonEC2Client();

    public <T, R> R perform(ParamsView pv, Function<String, R> send) {
        Instance instance = null;
        String newDigestAppUrl = null;
        try {
            waitUntilOutOfMemoryAlarmReported();
            instance = setupNewDigestInstance(pv.getInstanceType(), pv.getAmiId());
            waitUntilNewInstanceGetsReady(instance, 600);
            String newInstanceUrl = fetchInstanceUrl(instance);
            newDigestAppUrl = buildAppUrl(newInstanceUrl, pv.getServiceContext());
            sendHealthCheckUntilGetHealthy(newDigestAppUrl);
        } catch (Exception ex) {
            log(ex);
            if (instance != null) {
                cleanup(instance);
            }
        }
        return send.apply(newDigestAppUrl + pv.getServicePath());
    }

    private void waitUntilOutOfMemoryAlarmReported() {
        int timeout = 300;
        TimeUnit unit = TimeUnit.SECONDS;
        scheduler.schedule(() -> {
            boolean oaReported = oooAlarm.isReported();
            if (oaReported) {
                log.info("OutOfMemoryAlarmReported just arrived");
                oooAlarm.reset();
                return Optional.of(oaReported);

            }
            return Optional.empty();
        }, 15, timeout, unit);
        log.info("OOO alarm report has arrived");
    }

    private void sendHealthCheckUntilGetHealthy(String newDigestAppUrl) {
        String healthCheckUrl = newDigestAppUrl + "/hc";
        scheduler.schedule(() -> {
            try {
                URL url = new URL(healthCheckUrl);
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                con.setRequestMethod("GET");
                con.connect();
                int rc = con.getResponseCode();
                log.info("Healthcheck response code of " + rc + " received for " + healthCheckUrl);
                return HttpURLConnection.HTTP_OK == rc ? Optional.of(rc) : Optional.empty();
            } catch (Exception ex) {
                throw new ServiceException(ex);
            }
        }, 15, 300, TimeUnit.SECONDS);
    }

    private String fetchInstanceUrl(Instance instance) {
        String newInstancePublicIpAddress = instance.getPublicIpAddress();
        if (newInstancePublicIpAddress == null) {
            newInstancePublicIpAddress = ec2Client.describeInstances(new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId()))
                    .getReservations().get(0).getInstances().get(0).getPublicIpAddress();
        }
        return newInstancePublicIpAddress;
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
        return scheduler.schedule(() -> {
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
