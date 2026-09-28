package com.github.highcumontoa.sensitivedatagatewayjava.engine;

import com.github.highcumontoa.sensitivedatagatewayjava.classification.ClassificationDefinition;
import com.github.highcumontoa.sensitivedatagatewayjava.config.GatewayProperties;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.AccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchAccessRequest;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.BatchProcessedData;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.FieldResult;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayErrorCode;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.domain.ProcessedRecord;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.AccessPolicy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量处理引擎：全有或全无（all-or-nothing）。
 *
 * <p>处理流程：
 * <ol>
 *   <li><b>处理前静态检查整批</b>：批大小（BATCH_SIZE_LIMIT_EXCEEDED）、
 *       每条记录的深度（DEPTH_LIMIT_EXCEEDED）与规模（RECORD_SIZE_LIMIT_EXCEEDED）。
 *       任何一项超限都在转换任何字段之前拒绝整批，不留下部分结果；</li>
 *   <li><b>顺序处理每条记录</b>：复用 {@link RecordProcessor}，输出保持原顺序、
 *       层级、数组元素个数与类型族；fieldResults 的路径以 {@code records[i].} 前缀
 *       写回，可还原到具体记录；</li>
 *   <li>任一记录抛出数据/权限/用途类错误，立即以携带记录序号与字段位置的异常拒绝整批；</li>
 *   <li>整批共用一个起始时刻做耗时预算（BATCH_TIMEOUT_EXCEEDED）。</li>
 * </ol>
 *
 * <p>令牌稳定性：转换器密钥由“根盐 + 策略版本 + 字段路径”派生，批内各记录同一相对路径
 * 与同一策略版本派生出同一密钥，因此同一原始值在同一字段、同一策略下跨记录输出恒定，
 * 与记录顺序、所在数组位置无关；不同字段/策略仍彼此隔离。
 */
@Component
public class BatchDataProcessingEngine {

    private final RecordProcessor recordProcessor;
    private final GatewayProperties properties;

    public BatchDataProcessingEngine(RecordProcessor recordProcessor,
                                     GatewayProperties properties) {
        this.recordProcessor = recordProcessor;
        this.properties = properties;
    }

    public static final class BatchResult {
        private final List<ProcessedRecord> records;
        private final List<FieldResult> allFieldResults;
        private final String decisionBasis;

        BatchResult(List<ProcessedRecord> records, List<FieldResult> allFieldResults,
                    String decisionBasis) {
            this.records = records;
            this.allFieldResults = allFieldResults;
            this.decisionBasis = decisionBasis;
        }

        public List<ProcessedRecord> records() {
            return records;
        }

        public List<FieldResult> allFieldResults() {
            return allFieldResults;
        }

        public String decisionBasis() {
            return decisionBasis;
        }
    }

    public BatchResult process(BatchAccessRequest batch, AccessPolicy policy,
                               ClassificationDefinition classification) {
        long startNanos = System.nanoTime();
        List<Object> records = batch.records();
        if (records == null || records.isEmpty()) {
            throw new GatewayException(GatewayErrorCode.BAD_REQUEST,
                    "batch must contain at least one record");
        }
        if (records.size() > properties.getMaxBatchRecords()) {
            throw new GatewayException(GatewayErrorCode.BATCH_SIZE_LIMIT_EXCEEDED,
                    "batch size " + records.size() + " exceeds limit "
                            + properties.getMaxBatchRecords(), null, null);
        }

        // 处理前对整批做深度/规模静态检查：绝不转换任何字段，超限直接整批拒绝
        preCheckAll(records);

        // 逐字段判定与转换使用一个单条视角的 AccessRequest（调用方/用途/版本整批相同）
        AccessRequest perRecord = new AccessRequest(batch.callerId(), batch.purpose(),
                batch.requestedPaths() == null ? List.of() : batch.requestedPaths(),
                batch.policyVersion(), null);

        List<ProcessedRecord> processedRecords = new ArrayList<>(records.size());
        List<FieldResult> allFieldResults = new ArrayList<>();
        List<String> allBasis = new ArrayList<>();

        for (int i = 0; i < records.size(); i++) {
            RecordProcessor.Outcome outcome;
            try {
                outcome = recordProcessor.process(records.get(i), perRecord, policy, classification,
                        properties.getMaxDepth(), properties.getMaxBatchRecordNodes(),
                        startNanos, properties.getBatchTimeoutMs(),
                        GatewayErrorCode.RECORD_SIZE_LIMIT_EXCEEDED,
                        GatewayErrorCode.BATCH_TIMEOUT_EXCEEDED);
            } catch (GatewayException ge) {
                // 静态规模/深度错误来自预检查时未带序号；此处统一补上记录序号
                throw withBatchLocation(ge, i);
            }
            List<FieldResult> prefixed = prefixPaths(outcome.fieldResults(), i);
            // 记录内说明保留该记录内的相对路径；批次审计字段使用 records[i]. 全局路径
            processedRecords.add(new ProcessedRecord(i, outcome.data(), outcome.fieldResults()));
            allFieldResults.addAll(prefixed);
            allBasis.add("record[" + i + "]: " + outcome.decisionBasis());
        }

        return new BatchResult(List.copyOf(processedRecords),
                List.copyOf(allFieldResults), String.join(" | ", allBasis));
    }

    private void preCheckAll(List<Object> records) {
        for (int i = 0; i < records.size(); i++) {
            Object record = records.get(i);
            if (!(record instanceof java.util.Map)) {
                throw new GatewayException(GatewayErrorCode.DATA_MALFORMED,
                        "each batch record must be a JSON object", i, null);
            }
            RecordProcessor.Counter counter = new RecordProcessor.Counter();
            try {
                RecordProcessor.Limits.check(record, 1, properties.getMaxDepth(),
                        properties.getMaxBatchRecordNodes(), counter,
                        GatewayErrorCode.RECORD_SIZE_LIMIT_EXCEEDED);
            } catch (GatewayException ge) {
                throw new GatewayException(ge.getCode(),
                        ge.getMessage() + " (at batch record index " + i + ")",
                        i, ge.getFieldPath(), ge.getCause());
            }
        }
    }

    private GatewayException withBatchLocation(GatewayException ge, int index) {
        return new GatewayException(ge.getCode(),
                ge.getMessage() + " (at batch record index " + index
                        + (ge.getFieldPath() == null ? "" : ", field " + ge.getFieldPath()) + ")",
                index, ge.getFieldPath(), ge.getCause());
    }

    private List<FieldResult> prefixPaths(List<FieldResult> fields, int index) {
        List<FieldResult> out = new ArrayList<>(fields.size());
        for (FieldResult f : fields) {
            out.add(new FieldResult("records[" + index + "]." + f.path(),
                    f.level(), f.transform(), f.reversible(), f.transformed()));
        }
        return out;
    }
}
