package net.adamsmolnik.control.fallback;

import java.util.Optional;
import javax.validation.constraints.NotNull;

/**
 * @author ASmolnik
 *
 */
public class Params implements ParamsView {

    private String instanceType;

    private String amiId;

    private String serviceContext;

    private String servicePath;

    private Optional<String> elb = Optional.empty();

    private Optional<String> dnsName = Optional.empty();

    private boolean waitForOOMAlarm;

    public Params withInstanceType(String type) {
        this.instanceType = type;
        return this;
    }

    public Params withAmiId(String amiId) {
        this.amiId = amiId;
        return this;
    }

    @NotNull
    public Params withElbAndDnsName(String elb, String dnsName) {
        this.elb = Optional.of(elb);
        this.dnsName = Optional.of(dnsName);
        return this;
    }

    public Params withServiceContext(String serviceContext) {
        this.serviceContext = serviceContext;
        return this;
    }

    public Params withServicePath(String servicePath) {
        this.servicePath = servicePath;
        return this;
    }

    public Params withWaitForOOMAlarm(boolean waitForOOMAlarm) {
        this.waitForOOMAlarm = waitForOOMAlarm;
        return this;
    }

    @Override
    public String getInstanceType() {
        return instanceType;
    }

    @Override
    public String getAmiId() {
        return amiId;
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
