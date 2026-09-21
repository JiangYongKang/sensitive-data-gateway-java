package com.github.highcumontoa.sensitivedatagatewayjava.audit;

import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.model.AuditRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.EvaluationResult;
import com.github.highcumontoa.sensitivedatagatewayjava.model.FieldDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.highcumontoa.sensitivedatagatewayjava.limit.GatewayLimits;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 内存审计服务。
 *
 * <p>语义：
 * <ul>
 *   <li>所有判定（放行与各类拒绝）都写审计，记录原始输入、判定依据与策略版本；</li>
 *   <li>写入失败时抛出 {@link DecisionCode#AUDIT_WRITE_FAILED}，上层不得返回数据；</li>
 *   <li>按保留条数滚动淘汰最旧记录；{@code armNextWriteFailure} 供测试验证“审计失败不放行”。</li>
 * </ul>
 */
@Component
public class InMemoryAuditService implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryAuditService.class);

    private final Queue<AuditRecord> records = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile int retentionCount;
    private volatile boolean failNextWrite = false;

    public InMemoryAuditService(GatewayLimits limits) {
        this.retentionCount = Math.max(1, limits.getAuditRetentionCount());
    }

    /** 让下一次写入失败（测试用），并复位开关。 */
    public void armNextWriteFailure() {
        this.failNextWrite = true;
    }

    public List<AuditRecord> records() {
        return List.copyOf(records);
    }

    @Override
    public AuditRecord write(AccessRequest request, EvaluationResult result, String inputJson) {
        if (failNextWrite) {
            failNextWrite = false;
            log.error("审计写入失败（注入）callerId={} purpose={} 判定={}；本次不返回任何数据",
                    request.callerId(), request.purpose(), result.overallCode());
            throw new GatewayException(DecisionCode.AUDIT_WRITE_FAILED,
                    "审计写入失败，请求被拒绝以避免无痕迹访问");
        }

        String auditId = "aud-" + Long.toHexString(sequence.incrementAndGet())
                + "-" + Long.toHexString(System.nanoTime());
        String basis = buildBasis(result);
        AuditRecord record = new AuditRecord(
                auditId,
                Instant.now(),
                request.callerId(),
                request.purpose(),
                request.policyVersion(),
                result.versionResolution().resolvedVersion(),
                result.versionResolution().latestVersion(),
                result.overallCode(),
                result.reason(),
                inputJson,
                basis);

        boolean added = records.offer(record);
        if (!added) {
            throw new GatewayException(DecisionCode.AUDIT_WRITE_FAILED,
                    "审计写入失败，请求被拒绝以避免无痕迹访问");
        }
        enforceRetention();

        // 打印原始输入与判定依据，满足可审计/可复现
        log.info("AUDIT id={} callerId={} purpose={} requestedVersion={} resolvedVersion={} "
                        + "latestVersion={} decision={} reason={} basis=[{}] input={}",
                auditId, request.callerId(), request.purpose(),
                request.policyVersion(),
                result.versionResolution().resolvedVersion(),
                result.versionResolution().latestVersion(),
                result.overallCode(), result.reason(), basis, inputJson);
        return record;
    }

    @Override
    public List<AuditRecord> findAll() {
        return List.copyOf(records);
    }

    private String buildBasis(EvaluationResult result) {
        return result.fieldDecisions().stream()
                .map(this::describe)
                .collect(Collectors.joining("; "));
    }

    private String describe(FieldDecision f) {
        return f.fieldPath() + "(" + f.level() + ")=" + f.code()
                + "/" + f.transform() + ":" + f.reason();
    }

    private void enforceRetention() {
        int max = retentionCount;
        while (records.size() > max) {
            records.poll();
        }
    }
}
