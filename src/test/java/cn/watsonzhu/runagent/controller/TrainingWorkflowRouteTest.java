package cn.watsonzhu.runagent.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(properties = {"spring.ai.openai.api-key=test-key", "run-agent.rag.enabled=false"})
class TrainingWorkflowRouteTest {
    @Autowired ApplicationContext context;

    @Test void workflowRejectsMissingModeBeforeCallingAnyDependency() {
        WebTestClient.bindToApplicationContext(context).build().post()
                .uri("/api/workflows/training-analysis")
                .header("Content-Type", "application/json").bodyValue("{\"question\":\"analyze\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_INVALID_REQUEST");
    }

    @Test void requiredDatesAreValidatedInsideTheActualGraph() {
        WebTestClient.bindToApplicationContext(context).build().post()
                .uri("/api/workflows/training-analysis")
                .header("Content-Type", "application/json").bodyValue("{\"question\":\"analyze\",\"mode\":\"COMBINED\"}")
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_INVALID_REQUEST");
    }

    @Test void disabledKnowledgeFailsWithoutInvokingTheConfiguredRealChatProvider() {
        WebTestClient.bindToApplicationContext(context).build().post()
                .uri("/api/workflows/training-analysis")
                .header("Content-Type", "application/json").bodyValue("{\"question\":\"recovery\",\"mode\":\"KNOWLEDGE_ONLY\"}")
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo("WORKFLOW_DEPENDENCY_UNAVAILABLE");
    }
}
