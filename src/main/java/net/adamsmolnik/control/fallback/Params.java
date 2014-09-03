package net.adamsmolnik.control.fallback;

/**
 * @author ASmolnik
 *
 */
public class Params implements ParamsView {

    private String instanceType;

    private String amiId;

    private String serviceContext;

    private String servicePath;

    public Params withInstanceType(String type) {
        this.instanceType = type;
        return this;
    }

    public Params withAmiId(String amiId) {
        this.amiId = amiId;
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

    @Override
    public String getInstanceType() {
        return instanceType;
    }

    @Override
    public String getAmiId() {
        return amiId;
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

}
