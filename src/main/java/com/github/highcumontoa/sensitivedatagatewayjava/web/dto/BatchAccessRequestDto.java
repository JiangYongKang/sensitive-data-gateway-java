package com.github.highcumontoa.sensitivedatagatewayjava.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 批量访问请求入参。整批共用调用方、用途与可选版本要求；
 * records 为多条业务记录，每条可以是含多层数组和对象的 JSON 对象。
 */
public class BatchAccessRequestDto {

    @JsonProperty("callerId")
    private String callerId;
    @JsonProperty("purpose")
    private String purpose;
    @JsonProperty("requestedPaths")
    private List<String> requestedPaths;
    @JsonProperty("policyVersion")
    private String policyVersion;
    @JsonProperty("classificationVersion")
    private String classificationVersion;
    @JsonProperty("records")
    private List<Object> records;

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

    public String getClassificationVersion() {
        return classificationVersion;
    }

    public void setClassificationVersion(String classificationVersion) {
        this.classificationVersion = classificationVersion;
    }

    public List<Object> getRecords() {
        return records;
    }

    public void setRecords(List<Object> records) {
        this.records = records;
    }
}
