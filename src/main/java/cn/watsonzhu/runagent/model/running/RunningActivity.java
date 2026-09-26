package cn.watsonzhu.runagent.model.running;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * activities.json 的最小读取模型：只映射聚合需要的字段。
 * 原文件可能含位置和轨迹，ignoreUnknown 确保那些字段不进入 Tool 的数据模型。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RunningActivity(
        @JsonProperty("run_id") Long runId,
        String type,
        Double distance,
        @JsonProperty("moving_time") String movingTime,
        @JsonProperty("start_date_local") String startDateLocal
) {
}
