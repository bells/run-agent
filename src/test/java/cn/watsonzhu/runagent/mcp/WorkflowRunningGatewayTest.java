package cn.watsonzhu.runagent.mcp;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import cn.watsonzhu.runagent.config.RunningDataProperties;
import cn.watsonzhu.runagent.exception.WorkflowException;
import cn.watsonzhu.runagent.service.RunningDataService;
import cn.watsonzhu.runagent.workflow.LocalRunningCapabilityGateway;
import cn.watsonzhu.runagent.workflow.McpRunningCapabilityGateway;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkflowRunningGatewayTest {
    private final LocalDate start = LocalDate.of(2026, 9, 1);
    private final LocalDate end = LocalDate.of(2026, 9, 30);
    private final JsonMapper json = new JsonMapper();
    private final String summary = "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"runCount\":2,\"totalDistanceKm\":12.5,\"totalDurationSeconds\":4500}";

    @Test void localGatewayUsesExistingReadOnlyDataService() throws Exception {
        var fixture = new ClassPathResource("fixtures/activities.json").getFile().getAbsolutePath();
        var data = new RunningDataService(new RunningDataProperties(fixture), json);
        var result = new LocalRunningCapabilityGateway(data).getRunningSummary(
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertThat(result.runCount()).isEqualTo(3);
        assertThat(result.totalDistanceKm()).isEqualTo(18.0005);
    }

    @Test void deterministicInvocationUsesAcceptedRunningCallbackAndEmptyMetadataWithoutRediscovery() {
        var client = McpCatalogTest.client("running", "running-mcp-server", "getRunningSummary", "deniedTool");
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent(summary).build());
        var catalog = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        var gateway = gateway(catalog);
        assertThat(gateway.getRunningSummary(start, end).totalDistanceKm()).isEqualTo(12.5);
        gateway.getRunningSummary(start, end);
        var request = org.mockito.ArgumentCaptor.forClass(McpSchema.CallToolRequest.class);
        verify(client, times(2)).callTool(request.capture());
        assertThat(request.getValue().name()).isEqualTo("getRunningSummary");
        assertThat(request.getValue().arguments()).containsExactlyInAnyOrderEntriesOf(
                Map.of("startDate", "2026-09-01", "endDate", "2026-09-30"));
        assertThat(request.getValue().meta()).isEmpty();
        assertThat(catalog.findTool("running", "deniedTool")).isEmpty();
        assertThat(catalog.findTool("weather", "getRunningSummary")).isEmpty();
        verify(client).initialize();
        verify(client).listTools();
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"error\":\"RUNNING_DATA_UNAVAILABLE\",\"message\":\"private payload\"}", "{}", "null", "private payload",
            "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"runCount\":2}",
            "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"runCount\":-1,\"totalDistanceKm\":1,\"totalDurationSeconds\":1}",
            "{\"startDate\":\"2026-09-02\",\"endDate\":\"2026-09-30\",\"runCount\":1,\"totalDistanceKm\":1,\"totalDurationSeconds\":1}",
            "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"runCount\":1.5,\"totalDistanceKm\":1,\"totalDurationSeconds\":1}",
            "{\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-30\",\"runCount\":1,\"totalDistanceKm\":\"1\",\"totalDurationSeconds\":1}"
    }) void errorAndMalformedToolPayloadsNeverBecomeEvidence(String payload) {
        var client = McpCatalogTest.client("running", "running-mcp-server", "getRunningSummary");
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().addTextContent(payload).build());
        assertUnavailable(gateway(new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp())));
    }

    @Test void mcpIsErrorTransportFailureAndMissingCapabilityFailClosed() {
        var client = McpCatalogTest.client("running", "running-mcp-server", "getRunningSummary");
        var catalog = new McpCatalog(List.of(client), new McpPolicy("mcp").filter(), ToolContextToMcpMetaConverter.noOp());
        when(client.callTool(any())).thenReturn(McpSchema.CallToolResult.builder().isError(true).addTextContent("private").build());
        assertUnavailable(gateway(catalog));
        when(client.callTool(any())).thenThrow(new IllegalStateException("private"));
        assertUnavailable(gateway(catalog));
        assertUnavailable(gateway(new McpCatalog(List.of(client), new McpPolicy("local").filter(), ToolContextToMcpMetaConverter.noOp())));
        assertUnavailable(new McpRunningCapabilityGateway(new DefaultListableBeanFactory().getBeanProvider(McpCatalog.class), json));
    }

    private McpRunningCapabilityGateway gateway(McpCatalog catalog) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("remoteTools", catalog);
        return new McpRunningCapabilityGateway(beans.getBeanProvider(McpCatalog.class), json);
    }

    private void assertUnavailable(McpRunningCapabilityGateway gateway) {
        assertThatThrownBy(() -> gateway.getRunningSummary(start, end)).isInstanceOf(WorkflowException.class)
                .hasMessageNotContaining("private").extracting("kind").isEqualTo(WorkflowException.Kind.DEPENDENCY_UNAVAILABLE);
    }
}
