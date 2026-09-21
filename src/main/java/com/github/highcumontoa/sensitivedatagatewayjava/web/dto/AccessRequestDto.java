package com.github.highcumontoa.sensitivedatagatewayjava.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 访问请求入参。
 */
public class AccessRequestDto {

    @JsonProperty("callerId")
    private String callerId;
    @JsonProperty("purpose")
    private String purpose;
    @JsonProperty("requestedPaths")
    private List<String> requestedPaths;
    @JsonProperty("policyVersion")
    private String policyVersion;
    @JsonProperty("data")
    private Object data;

    public String getCallerId() {
        return callerId;
    }

    public void setCallerId(String callerId) {
        this.callerId = callerId;
    }

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public List<String> getRequestedPaths() {
        return requestedPaths;
    }

    public void setRequestedPaths(List<String> requestedPaths) {
        this.requestedPaths = requestedPaths;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Object getData() {
        return data;
    }

    public void setData(Object data) {
        this.data = data;
    }
}
