package com.github.highcumontoa.sensitivedatagatewayjava.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.highcumontoa.sensitivedatagatewayjava.error.GatewayException;
import com.github.highcumontoa.sensitivedatagatewayjava.model.DecisionCode;
import com.github.highcumontoa.sensitivedatagatewayjava.model.PolicyVersion;
import com.github.highcumontoa.sensitivedatagatewayjava.policy.PolicyStore;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 classpath:gateway/*.json 加载全部策略版本并整体原子装载。
 *
 * <p>任一字段缺失/格式异常都判定为加载失败：在首次启动时失败即拒绝启动；
 * 在运行期重载时不触碰在线快照（避免新旧版本静默混用）。
 */
@Component
public class PolicyLoader {

    private static final String LOCATION = "classpath:gateway/*.json";

    private final ObjectMapper objectMapper;
    private final PathMatchingResourcePatternResolver resolver =
            new PathMatchingResourcePatternResolver();

    public PolicyLoader() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 启动加载：无策略或解析失败直接抛出（快速失败，不带病运行）。 */
    public synchronized void loadOnStartup(PolicyStore store) throws Exception {
        List<PolicyVersion> versions = readAll();
        if (versions.isEmpty()) {
            throw new GatewayException(DecisionCode.POLICY_MISSING,
                    "未找到任何策略文件 (" + LOCATION + ")，拒绝在无策略状态下启动");
        }
        store.replaceAll(versions, null);
    }

    /**
     * 运行期重载：先完整读取并解析全部版本，全部成功才原子替换；
     * 任何异常都保留当前在线快照，不发生半更新或混用。
     *
     * @return 重载后最新版本号
     */
    public synchronized String reload(PolicyStore store) {
        try {
            List<PolicyVersion> versions = readAll();
            if (versions.isEmpty()) {
                throw new GatewayException(DecisionCode.POLICY_MISSING,
                        "重载内容为空，已保留当前在线策略");
            }
            store.replaceAll(versions, null);
            return store.snapshot().latestVersion();
        } catch (GatewayException ge) {
            throw ge;
        } catch (Exception e) {
            throw new GatewayException(DecisionCode.DOWNSTREAM_FAILURE,
                    "策略重载失败，已保留当前在线策略，未发生版本混用", e);
        }
    }

    private List<PolicyVersion> readAll() throws Exception {
        Resource[] resources = resolver.getResources(LOCATION);
        List<PolicyVersion> versions = new ArrayList<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            PolicyVersion version;
            try {
                version = objectMapper.readValue(resource.getInputStream(), PolicyVersion.class);
            } catch (Exception parseError) {
                throw new GatewayException(DecisionCode.MALFORMED_DATA,
                        "策略文件格式异常，加载被拒绝: " + resource.getFilename(), parseError);
            }
            versions.add(version);
        }
        versions.sort(Comparator.comparing(PolicyVersion::version));
        return versions;
    }
}
