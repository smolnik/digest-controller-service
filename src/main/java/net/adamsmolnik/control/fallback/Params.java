package net.adamsmolnik.control.fallback;

import java.util.Optional;
import javax.validation.constraints.NotNull;

/**
 * @author ASmolnik
 *
 */
public class Params implements ParamsView {

    private String serviceName;

    private String instanceType;

    private String imageId;

    private String serviceContext;

    private String servicePath;

    private Optional<String> elb = Optional.empty();

    private Optional<String> dnsName = Optional.empty();

    private boolean waitForOOMAlarm;

    public Params withServiceName(String value) {
        this.serviceName = value;
        return this;
    }

    public Params withInstanceType(String value) {
        this.instanceType = value;
        return this;
    }

    public Params withImageId(String value) {
        this.imageId = value;
        return this;
    }

    @NotNull
    public Params withElbAndDnsName(String elb, String dnsName) {
        this.elb = Optional.of(elb);
        this.dnsName = Optional.of(dnsName);
        return this;
    }

    public Params withServiceContext(String value) {
        this.serviceContext = value;
        return this;
    }

    public Params withServicePath(String value) {
        this.servicePath = value;
        return this;
    }

    public Params withWaitForOOMAlarm(boolean value) {
        this.waitForOOMAlarm = value;
        return this;
    }

    @Override
    public String getServiceName() {
        return serviceName;
    }

    @Override
    public String getInstanceType() {
        return instanceType;
    }

    @Override
    public String getImageId() {
        return imageId;
    }

    @Override
    public Optional<String> getElb() {
        return elb;
    }

    @Override
    public Optional<String> getDnsName() {
        return dnsName;
    }

    @Override
    public String getServiceContext() {
        return serviceContext;
    }

    @Override
    public String getServicePath() {
        return servicePath;
    }

    @Override
    public String getServiceFullPath() {
        return serviceContext + servicePath;
    }

    @Override
    public boolean waitForOOMAlarm() {
        return waitForOOMAlarm;
    }

}
