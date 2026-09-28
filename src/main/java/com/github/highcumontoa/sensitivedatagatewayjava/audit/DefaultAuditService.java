package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * 默认审计服务。
 * 审计记录包含判定依据与所用策略/分级版本；写存储失败一律归一化为
 * AUDIT_WRITE_FAILED（fail-closed），调用方不得在无审计痕迹的情况下返回数据。
 */
@Component
public class DefaultAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAuditService.class);

    private final AuditStore store;

    public DefaultAuditService(AuditStore store) {
        this.store = store;
    }

    @Override
    public AuditRecord record(AccessRequest request, String policyVersion, String classificationVersion,
                              boolean allowed, GatewayErrorCode denyReason, String decisionBasis,
                              List<FieldResult> fields, String requestHash) {
        return buildAndStore(request.callerId(), request.purpose(), policyVersion, classificationVersion,
                allowed, denyReason, decisionBasis, fields, requestHash,
                false, 1, null, null);
    }

    @Override
    public AuditRecord recordBatch(String callerId, String purpose, String policyVersion,
                                   String classificationVersion, boolean allowed,
                                   GatewayErrorCode denyReason, String decisionBasis,
                                   List<FieldResult> fields, String requestHash,
                                   int recordCount, Integer recordIndex, String fieldPath) {
        return buildAndStore(callerId, purpose, policyVersion, classificationVersion,
                allowed, denyReason, decisionBasis, fields, requestHash,
                true, recordCount, recordIndex, fieldPath);
    }

    private AuditRecord buildAndStore(String callerId, String purpose, String policyVersion,
                                      String classificationVersion, boolean allowed,
                                      GatewayErrorCode denyReason, String decisionBasis,
                                      List<FieldResult> fields, String requestHash,
                                      boolean batch, int recordCount,
                                      Integer recordIndex, String fieldPath) {
        AuditRecord record = new AuditRecord(
                UUID.randomUUID().toString(),
                Instant.now(),
                callerId,
                purpose,
                policyVersion,
                classificationVersion,
                allowed,
                denyReason,
                decisionBasis,
                fields == null ? List.of() : List.copyOf(fields),
                requestHash,
                batch,
                recordCount,
                recordIndex,
                fieldPath);
        try {
            store.append(record);
        } catch (GatewayException ge) {
            throw ge;
        } catch (Exception e) {
            throw new GatewayException(GatewayErrorCode.AUDIT_WRITE_FAILED,
                    "audit record could not be persisted", e);
        }
        log.info("AUDIT {} batch={} caller={} purpose={} policyVersion={} classificationVersion={} allowed={} "
                        + "denyReason={} recordIndex={} fieldPath={} basis={} requestHash={}",
                record.auditId(), batch, record.callerId(), record.purpose(),
                record.policyVersion(), record.classificationVersion(),
                allowed, denyReason, recordIndex, fieldPath, decisionBasis, requestHash);
        return record;
    }

    /**
     * 对原始输入生成稳定指纹（不存储原始负载，避免审计侧泄漏）。
     */
    public static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new GatewayException(GatewayErrorCode.INTERNAL_ERROR, "digest unavailable", e);
        }
    }
}
