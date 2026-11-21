package io.vanillabp.cockpit.operaton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.HistoryService;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.operaton.api.OperatonEngineFacts;
import io.vanillabp.operaton.wiring.OperatonTaskRegistry;

/**
 * Tests which instance of a call hierarchy is the business case. Whether two processes share the
 * workflow aggregate is for the core to answer, so this test answers it with a mock.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OperatonBusinessCasesTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "a-module";

  /** Works on an aggregate of its own. */
  private static final String ORDER = "Order";

  /** Works on another aggregate. */
  private static final String SHIPPING = "Shipping";

  /** A step of Shipping, on Shipping's aggregate. */
  private static final String PACKING = "Packing";

  private static final Set<String> SHIPPING_AGGREGATE = Set.of(SHIPPING, PACKING);

  private OperatonScope scope;

  private OperatonWorkflowProcesses processes;

  @BeforeEach
  public void threeClaimedProcesses() {

    processes = new OperatonWorkflowProcesses();
    processes.register(MODULE_ID, ORDER);
    processes.register(MODULE_ID, SHIPPING);
    processes.register(MODULE_ID, PACKING);
    final var core = mock(WorkflowTaskClaims.class);
    for (final var calling : Set.of(ORDER, SHIPPING, PACKING)) {
      for (final var called : Set.of(ORDER, SHIPPING, PACKING)) {
        when(core.workflowsShareTheWorkflowAggregate(MODULE_ID, calling, called))
            .thenReturn(calling.equals(called) || (SHIPPING_AGGREGATE.contains(calling) && SHIPPING_AGGREGATE
                .contains(called)));
      }
    }
    processes.rememberTheCore(core);
    // 'by-adapter': the workflow module is a tenant of its own and the process id stays plain
    final var scoping = new NameClashAvoidanceService(new MigrationAdapterProperties());
    final var engine = new OperatonEngineFacts(ADAPTER_ID, scoping, workflowModuleId -> null, new OperatonTaskRegistry());
    scope = new OperatonScope(ADAPTER_ID, scoping, () -> engine);

  }

  private static ExecutionEntity anInstanceOf(
      final String id,
      final String processId,
      final ExecutionEntity caller) {

    final var definition = mock(ProcessDefinitionEntity.class);
    when(definition.getTenantId()).thenReturn(MODULE_ID);
    when(definition.getKey()).thenReturn(processId);
    final var instance = mock(ExecutionEntity.class);
    when(instance.getProcessInstance()).thenReturn(instance);
    when(instance.getProcessInstanceId()).thenReturn(id);
    when(instance.getProcessDefinition()).thenReturn(definition);
    if (caller != null) {
      // the super execution is the call activity's, which belongs to the caller's instance
      final var callActivity = mock(ExecutionEntity.class);
      when(callActivity.getProcessInstance()).thenReturn(caller);
      when(instance.getSuperExecution()).thenReturn(callActivity);
    }
    return instance;

  }

  @Test
  @DisplayName("The case is the highest caller reached by calls which share the aggregate")
  public void theWalkStopsAtTheFirstCallerWithAnotherAggregate() {

    final var order = anInstanceOf("1", ORDER, null);
    final var shipping = anInstanceOf("2", SHIPPING, order);
    final var packing = anInstanceOf("3", PACKING, shipping);

    assertEquals("2", OperatonBusinessCases.caseOf(scope, processes, packing));
    assertEquals("2", OperatonBusinessCases.caseOf(scope, processes, shipping));
    assertEquals("1", OperatonBusinessCases.caseOf(scope, processes, order));

  }

  @Test
  @DisplayName("The walk along the history stops at the first caller with another aggregate as well")
  public void theWalkAlongTheHistoryStopsAtTheSameCaller() {

    final var history = mock(HistoryService.class, RETURNS_DEEP_STUBS);
    final var order = aHistoricInstance(history, "1", ORDER, null);
    aHistoricInstance(history, "2", SHIPPING, "1");
    final var packing = aHistoricInstance(history, "3", PACKING, "2");

    assertEquals("2", OperatonBusinessCases.caseOf(scope, processes, history, packing));
    assertEquals("1", OperatonBusinessCases.caseOf(scope, processes, history, order));

  }

  private static HistoricProcessInstance aHistoricInstance(
      final HistoryService history,
      final String id,
      final String processId,
      final String callerId) {

    final var instance = mock(HistoricProcessInstance.class);
    when(instance.getId()).thenReturn(id);
    when(instance.getTenantId()).thenReturn(MODULE_ID);
    when(instance.getProcessDefinitionKey()).thenReturn(processId);
    when(instance.getSuperProcessInstanceId()).thenReturn(callerId);
    when(history.createHistoricProcessInstanceQuery().processInstanceId(id).singleResult()).thenReturn(instance);
    return instance;

  }

  @Test
  @DisplayName("A call from a process nobody claims starts a case of its own")
  public void aCallFromAnUnclaimedProcessStartsACase() {

    final var foreign = anInstanceOf("1", "SomebodyElses", null);
    final var packing = anInstanceOf("2", PACKING, foreign);

    assertEquals("2", OperatonBusinessCases.caseOf(scope, processes, packing));

  }

  @Test
  @DisplayName("A called instance is a case where it does not share its caller's aggregate")
  public void aCalledInstanceIsACaseWhereTheAggregatesDiffer() {

    final var order = new OperatonBusinessCases.Instance("1", MODULE_ID, ORDER, null);
    final var shipping = new OperatonBusinessCases.Instance("2", MODULE_ID, SHIPPING, "1");
    final var packing = new OperatonBusinessCases.Instance("3", MODULE_ID, PACKING, "2");

    assertTrue(OperatonBusinessCases.isACase(scope, processes, shipping, order));
    assertFalse(OperatonBusinessCases.isACase(scope, processes, packing, shipping));
    assertTrue(OperatonBusinessCases.isACase(scope, processes, order, null));

  }

}
