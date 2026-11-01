package cn.watsonzhu.runagent.observability;

import io.micrometer.common.KeyValues;
import org.springframework.ai.vectorstore.observation.DefaultVectorStoreObservationConvention;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationContext;

/** The upstream convention exports query text even when query-response logging is disabled. */
public final class PrivacySafeVectorStoreConvention extends DefaultVectorStoreObservationConvention {
    @Override public KeyValues getHighCardinalityKeyValues(VectorStoreObservationContext context) {
        return KeyValues.empty();
    }
}
