package cn.watsonzhu.runagent.model;

import java.time.LocalDate;

public record RunningIntent(
        RunningIntentType intent,
        LocalDate startDate,
        LocalDate endDate,
        String originalQuestion
) {
    public RunningIntent {
        intent = intent == null ? RunningIntentType.UNKNOWN : intent;
    }

    public RunningIntent withOriginalQuestion(String question) {
        return new RunningIntent(intent, startDate, endDate, question);
    }
}
