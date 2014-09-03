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
import javax.naming.spi.DirStateFactory.Result;
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
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancing;
import com.amazonaws.services.elasticloadbalancing.AmazonElasticLoadBalancingClient;
import com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerRequest;
import com.amazonaws.services.elasticloadbalancing.model.DeregisterInstancesFromLoadBalancerResult;
import com.amazonaws.services.elasticloadbalancing.model.RegisterInstancesWithLoadBalancerRequest;

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

    private final AmazonEC2Client ec2 = new AmazonEC2Client();

    private final AmazonElasticLoadBalancing elb = new AmazonElasticLoadBalancingClient();

    public <T, R> R perform(ParamsView pv, Function<String, R> send) {
        String serviceFullPath = pv.getServiceFullPath();
        Instance newInstance = null;
        String newDigestAppUrl = null;
        try {
            if (pv.waitForOOMAlarm()) {
                waitUntilOutOfMemoryAlarmReported();
            }
            String type = pv.getInstanceType();
            newInstance = setupNewDigestInstance(type, pv.getAmiId());
            waitUntilNewInstanceGetsReady(newInstance, 600);
            newInstance = fetchInstance(newInstance);
            attachInstanceToElb(newInstance, pv);
            newDigestAppUrl = buildAppUrl(newInstance, pv);
            sendHealthCheckUntilGetHealthy(newDigestAppUrl);
            String serviceUrl = newDigestAppUrl + pv.getServicePath();
            R response = send.apply(serviceUrl);
            cache.put(serviceFullPath, serviceUrl);
            scheduleCleanup(newInstance, pv);
            return response;
        } catch (Exception ex) {
            log(ex);
            if (newInstance != null) {
                cleanup(newInstance, pv);
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

    private void attachInstanceToElb(Instance instance, ParamsView pv) {
        Optional<String> elbParam = pv.getElb();
        if (elbParam.isPresent()) {
            elb.registerInstancesWithLoadBalancer(new RegisterInstancesWithLoadBalancerRequest().withLoadBalancerName(elbParam.get()).withInstances(
                    mapModel(instance)));
        }
    }

    private Instance fetchInstance(Instance instance) {
        return ec2.describeInstances(new DescribeInstancesRequest().withInstanceIds(instance.getInstanceId())).getReservations().get(0)
                .getInstances().get(0);
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
        RunInstancesResult result = ec2.runInstances(request);
        Instance instance = result.getReservation().getInstances().get(0);

        List<Tag> tags = new ArrayList<>();
        Tag t = new Tag();
        t.setKey("Name");
        t.setValue("temporary fallback server for digest-service");
        tags.add(t);
        CreateTagsRequest ctr = new CreateTagsRequest();
        ctr.setTags(tags);
        ctr.withResources(instance.getInstanceId());
        ec2.createTags(ctr);
        return instance;
    }

    private InstanceStatus waitUntilNewInstanceGetsReady(Instance instance, int timeoutSec) {
        return scheduler.scheduleAndWaitFor(() -> {
            String instanceId = instance.getInstanceId();
            List<InstanceStatus> instanceStatuses = ec2.describeInstanceStatus(new DescribeInstanceStatusRequest().withInstanceIds(instanceId))
                    .getInstanceStatuses();
            if (!instanceStatuses.isEmpty()) {
                InstanceStatus is = instanceStatuses.get(0);
                return isReady(is.getInstanceStatus(), is.getSystemStatus()) ? Optional.of(is) : Optional.empty();
            }
            return Optional.empty();
        }, 15, timeoutSec, TimeUnit.SECONDS);
    }

    private void scheduleCleanup(Instance instance, ParamsView pv) {
        scheduler.schedule(() -> {
            cache.remove(pv.getServiceFullPath());
            cleanup(instance, pv);
        }, 15, TimeUnit.MINUTES);
    }

    private void cleanup(Instance instance, ParamsView pv) {
        Optional<String> elbParam = pv.getElb();
        if (elbParam.isPresent()) {
            DeregisterInstancesFromLoadBalancerResult result = elb
                    .deregisterInstancesFromLoadBalancer(new DeregisterInstancesFromLoadBalancerRequest().withLoadBalancerName(elbParam.get())
                            .withInstances(mapModel(instance)));
            log.info("Instance " + instance.getInstanceId() + " deregistered from elb " + elbParam.get() + " with result " + result);
        }
        ec2.terminateInstances(new TerminateInstancesRequest().withInstanceIds(instance.getInstanceId()));
    }

    private String buildAppUrl(Instance newInstance, ParamsView pv) {
        String serviceContext = pv.getServiceContext();
        Optional<String> dnsName = pv.getDnsName();
        String serverUrl = dnsName.isPresent() ? dnsName.get() : (newInstance.getPublicIpAddress() + ":8080");
        return "http://" + serverUrl + serviceContext;
    }

    private com.amazonaws.services.elasticloadbalancing.model.Instance mapModel(Instance instance) {
        return new com.amazonaws.services.elasticloadbalancing.model.Instance().withInstanceId(instance.getInstanceId());
    }

    private static boolean isReady(InstanceStatusSummary isSummary, InstanceStatusSummary ssSummary) {
        return "ok".equals(isSummary.getStatus()) && "ok".equals(ssSummary.getStatus());
    }

    private void log(Exception ex) {
        ex.printStackTrace();
    }

}
