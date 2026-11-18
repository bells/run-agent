package cn.watsonzhu.runagent.reliability;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import cn.watsonzhu.runagent.exception.*;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.client.RestClientResponseException;

/** Uses SDK types and status codes only; messages and provider response bodies are never inspected. */
public final class FailureClassifier {
    public FailureCategory classify(Throwable failure) {
        boolean networkIo = false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (Throwable current=failure; current!=null && seen.add(current); current=current.getCause()) {
            if(current instanceof ReliabilityFailure safe)return safe.category();
            if(current instanceof MultiAgentException domain)return switch(domain.kind()) {
                case BUDGET_EXCEEDED -> FailureCategory.BUDGET_EXCEEDED;
                case TIMEOUT -> FailureCategory.TIMEOUT;
                case INVALID_REQUEST -> FailureCategory.PERMANENT;
                case INVALID_SUPERVISOR_DECISION -> FailureCategory.INVALID_OUTPUT;
                case DEPENDENCY_UNAVAILABLE -> FailureCategory.DEPENDENCY_UNAVAILABLE;
                default -> FailureCategory.UNKNOWN;
            };
            if(current instanceof WorkflowException domain)return switch(domain.kind()) {
                case TIMEOUT -> FailureCategory.TIMEOUT;
                case INVALID_REQUEST -> FailureCategory.PERMANENT;
                case DEPENDENCY_UNAVAILABLE -> FailureCategory.DEPENDENCY_UNAVAILABLE;
                default -> FailureCategory.UNKNOWN;
            };
            if(current instanceof StructuredOutputException)return FailureCategory.INVALID_OUTPUT;
            if(current instanceof InterruptedException || current instanceof CancellationException)return FailureCategory.CANCELLED;
            if(current instanceof SocketTimeoutException || current instanceof HttpTimeoutException || current instanceof TimeoutException)return FailureCategory.TIMEOUT;
            if(current instanceof OpenAIServiceException http)return status(http.statusCode());
            if(current instanceof WebClientResponseException http)return status(http.getStatusCode().value());
            if(current instanceof RestClientResponseException http)return status(http.getStatusCode().value());
            if(current instanceof OpenAIIoException || current instanceof IOException) {
                if(current instanceof InterruptedIOException)return FailureCategory.CANCELLED;
                // Continue to a typed nested timeout before defaulting network IO to transient.
                networkIo=true;
                var cause=current.getCause();
                if(cause!=null && cause!=current)continue;
                return FailureCategory.TRANSIENT;
            }
            if(current instanceof IllegalArgumentException)return FailureCategory.PERMANENT;
        }
        return networkIo?FailureCategory.TRANSIENT:FailureCategory.UNKNOWN;
    }
    public static boolean controlFailure(Throwable error) {
        return error instanceof MultiAgentException domain && (domain.kind()==MultiAgentException.Kind.TIMEOUT
                || domain.kind()==MultiAgentException.Kind.BUDGET_EXCEEDED)
                || error instanceof WorkflowException workflow && workflow.kind()==WorkflowException.Kind.TIMEOUT;
    }
    private static FailureCategory status(int status) {
        return switch(status) {
            case 429 -> FailureCategory.RATE_LIMITED;
            case 502,503,504 -> FailureCategory.TRANSIENT;
            default -> status>=400 && status<500 ? FailureCategory.PERMANENT : FailureCategory.UNKNOWN;
        };
    }
    public static boolean circuitRecordable(FailureCategory category) {
        return retryable(category) || category==FailureCategory.DEPENDENCY_UNAVAILABLE;
    }
    public static boolean retryable(FailureCategory category) {
        return category==FailureCategory.TRANSIENT || category==FailureCategory.RATE_LIMITED
                || category==FailureCategory.TIMEOUT;
    }
}
