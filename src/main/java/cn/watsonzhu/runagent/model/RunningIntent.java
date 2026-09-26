package cn.watsonzhu.runagent.model;

import java.time.LocalDate;

public record RunningIntent(
        RunningIntentType intent,
        LocalDate startDate,
        LocalDate endDate,
        String originalQuestion
) {
    public RunningIntent {
        // 缺失意图是模型可能给出的结果；归一化为 UNKNOWN，避免调用方处理 null。
        intent = intent == null ? RunningIntentType.UNKNOWN : intent;
    }

    public RunningIntent withOriginalQuestion(String question) {
        return new RunningIntent(intent, startDate, endDate, question);
    }
}
