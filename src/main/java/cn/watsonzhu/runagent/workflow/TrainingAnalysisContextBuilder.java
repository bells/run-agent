package cn.watsonzhu.runagent.workflow;

import org.springframework.stereotype.Component;

@Component
public class TrainingAnalysisContextBuilder {
    public String build(TrainingAnalysisState state) {
        StringBuilder context = new StringBuilder("[USER QUESTION]\n").append(state.question()).append('\n');
        if (state.mode() != WorkflowMode.KNOWLEDGE_ONLY) {
            var summary = state.runningSummary();
            context.append("\n[RUNNING DATA]\nPeriod (inclusive): ").append(summary.startDate()).append(" to ").append(summary.endDate())
                    .append("\nRun count: ").append(summary.runCount())
                    .append("\nDistance (km): ").append(summary.totalDistanceKm())
                    .append("\nDuration (seconds): ").append(summary.totalDurationSeconds()).append('\n');
        }
        if (state.mode() != WorkflowMode.RUNNING_ONLY) {
            context.append("\n[KNOWLEDGE EVIDENCE]\n");
            for (var evidence : state.knowledgeEvidence()) {
                context.append("Source: ").append(evidence.sourceTitle()).append("\nEvidence:\n")
                        .append(evidence.text()).append("\n\n");
            }
        }
        return context.toString();
    }
}
