package cn.watsonzhu.runagent.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** A dedicated Boot handler group runs before its tracing group; plain handler @Order is insufficient. */
@Component
public final class AiObservationErrorSanitizer implements ObservationHandler<Observation.Context>, Ordered,
        org.springframework.boot.micrometer.observation.autoconfigure.ObservationHandlerGroup {
    @Override public Class<?> handlerType() { return AiObservationErrorSanitizer.class; }
    @Override public int compareTo(org.springframework.boot.micrometer.observation.autoconfigure.ObservationHandlerGroup other) {
        return other.handlerType() == handlerType() ? 0 : -1;
    }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsContext(Observation.Context context) {
        // Convention-backed observations select handlers before their name is assigned.
        return context.getClass().getPackageName().startsWith("org.springframework.ai.");
    }
    @Override public void onError(Observation.Context context) { sanitize(context); }
    @Override public void onStop(Observation.Context context) { sanitize(context); }
    private void sanitize(Observation.Context context) {
        if (context.getError() != null) context.setError(new AiOperationFailed());
    }
    private static final class AiOperationFailed extends RuntimeException {
        private AiOperationFailed() { super("AI operation failed", null, false, false); }
    }
}
