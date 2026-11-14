package io.vanillabp.cockpit.operaton.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoricTaskInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.model.bpmn.Bpmn;

import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.operaton.OperatonCockpitEvents;
import io.vanillabp.cockpit.operaton.OperatonCockpitWiring;
import io.vanillabp.cockpit.operaton.OperatonEventBeingReported;
import io.vanillabp.cockpit.operaton.OperatonScope;
import io.vanillabp.cockpit.operaton.OperatonWorkflowHistoryHandler;
import io.vanillabp.cockpit.operaton.OperatonWorkflowProcesses;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.operaton.api.OperatonEngineFacts;
import io.vanillabp.operaton.wiring.OperatonProcessVersions;
import io.vanillabp.operaton.wiring.OperatonTaskRegistry;

/**
 * Tests what an engine reports and what the cockpit is told about it, without an engine.
 * <p>
 * The integration tests of this repository run the happy path through a real engine. They cannot
 * cause the other cases: an event of a process this application never deployed or nobody claims,
 * an instance somebody started without a business key, a called process which is a step of its
 * caller's case. In these cases the right thing is to report nothing. A test which cannot tell
 * "reported nothing" apart from "was never asked" would not notice them.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OperatonCockpitEventsTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "a-module";

  private static final String BPMN_PROCESS_ID = "AProcess";

  /** The engine's id of the one deployed process definition of this test. */
  private static final String DEFINITION_ID = "AProcess:4:1";

  /** The version number the engine counted for that deployment. */
  private static final String COUNTED_VERSION = "4";

  /** The tag the modeller gave that deployment. A reference must NOT carry it. */
  private static final String VERSION_TAG = "release-1";

  private RecordingPublisher publisher;

  private OperatonWorkflowProcesses processes;

  private OperatonCockpitEvents events;

  @BeforeEach
  public void anEngineWithOneDeployedProcess() {

    publisher = new RecordingPublisher();
    processes = new OperatonWorkflowProcesses();
    processes.register(MODULE_ID, BPMN_PROCESS_ID);
    // the real name-clash avoidance of an application which configured none. It resolves to
    // 'by-adapter': the workflow module is a tenant of its own, and the process id stays plain
    final var scoping = new NameClashAvoidanceService(new MigrationAdapterProperties());
    // the adapter's own cache of what it deployed, filled the way a deployment fills it. The
    // extension reads the version from there and does not ask the engine for each event
    final var versions = new OperatonProcessVersions(
        ADAPTER_ID, null, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> workflowModuleId, null);
    versions
        .recordDeployed(MODULE_ID, BPMN_PROCESS_ID, DEFINITION_ID, Integer
            .parseInt(COUNTED_VERSION), VERSION_TAG);
    final var taskRegistry = new OperatonTaskRegistry();
    taskRegistry.setProcessVersions(versions);
    final var engine = new OperatonEngineFacts(
        ADAPTER_ID, scoping, workflowModuleId -> null, taskRegistry);
    events = new OperatonCockpitEvents(
        new OperatonScope(ADAPTER_ID, scoping, () -> engine), processes, new OperatonEventBeingReported(), () -> publisher, () -> EventTransaction.CURRENT);

  }

  private DelegateTask aTaskOf(
      final String tenantId,
      final String processDefinitionKey,
      final String businessKey) {

    final var definition = mock(ProcessDefinitionEntity.class);
    when(definition.getTenantId()).thenReturn(tenantId);
    when(definition.getKey()).thenReturn(processDefinitionKey);
    when(definition.getId()).thenReturn(DEFINITION_ID);

    final var execution = mock(ExecutionEntity.class);
    when(execution.getProcessDefinition()).thenReturn(definition);
    when(execution.getBusinessKey()).thenReturn(businessKey);
    when(execution.getProcessInstanceId()).thenReturn("the-instance");

    final var task = mock(DelegateTask.class);
    when(task.getExecution()).thenReturn(execution);
    when(task.getId()).thenReturn("the-task");
    when(task.getEventName()).thenReturn(TaskListener.EVENTNAME_CREATE);
    return task;

  }

  private HistoricProcessInstanceEventEntity anInstanceEvent(
      final HistoryEventTypes type,
      final String tenantId,
      final String processDefinitionKey,
      final String businessKey) {

    final var event = new HistoricProcessInstanceEventEntity();
    event.setId("history-event-1");
    event.setEventType(type.getEventName());
    event.setTenantId(tenantId);
    event.setProcessDefinitionKey(processDefinitionKey);
    event.setBusinessKey(businessKey);
    event.setProcessInstanceId("the-instance");
    event.setProcessDefinitionId(DEFINITION_ID);
    event.setStartTime(new Date());
    return event;

  }

  @Test
  @DisplayName("A user task of a deployed process is reported with the identifiers the cockpit addresses it by")
  public void aUserTaskIsReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, BPMN_PROCESS_ID, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertEquals(1, publisher.userTasks().size());
    final var reported = publisher.userTasks().getFirst();
    assertEquals(ADAPTER_ID, reported.adapterId());
    assertEquals(MODULE_ID, reported.workflowModuleId());
    assertEquals(BPMN_PROCESS_ID, reported.bpmnProcessId());
    assertEquals("4711", reported.workflowAggregateId());
    assertEquals("the-instance", reported.workflowId());
    assertEquals("the-task", reported.userTaskId());
    assertEquals("the-form", reported.taskDefinition());
    assertEquals("TheTask", reported.bpmnTaskId());
    assertEquals("the-task#create", publisher.userTaskEventIds().getFirst());

  }

  @Test
  @DisplayName("A task and a workflow carry the version the engine counted, and never its tag")
  public void theCountedVersionTravelsWithEveryReference() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, BPMN_PROCESS_ID, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);
    new OperatonWorkflowHistoryHandler(events)
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, "4711"));

    // an operator sees the tagged deployment as 'release-1:4'. The cockpit picks the details
    // provider of a version by the value checked here
    assertEquals(COUNTED_VERSION, publisher.userTasks().getFirst().processVersion());
    assertEquals(COUNTED_VERSION, publisher.workflows().getFirst().processVersion());

  }

  @Test
  @DisplayName("A user task of a process this application never deployed is passed over")
  public void aForeignUserTaskIsNotReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, "SomebodyElsesProcess", "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);
    events
        .reportUserTask(
            aTaskOf("another-tenant", BPMN_PROCESS_ID, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("A user task of an instance without a business key names no aggregate and is passed over")
  public void aUserTaskWithoutABusinessKeyIsNotReported() {

    events
        .reportUserTask(
            aTaskOf(MODULE_ID, BPMN_PROCESS_ID, null), "TheTask", "the-form",
            UserTaskEventKind.CREATED);

    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("A started workflow is reported as created, and carries the history event's own id")
  public void aStartedWorkflowIsReported() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, "4711");

    new OperatonWorkflowHistoryHandler(events).handleEvent(event);

    assertEquals(1, publisher.workflows().size());
    assertEquals(WorkflowEventKind.CREATED, publisher.workflowKinds().getFirst());
    assertEquals("history-event-1", publisher.workflowEventIds().getFirst());
    assertEquals("the-instance", publisher.workflows().getFirst().workflowId());
    assertEquals("4711", publisher.workflows().getFirst().workflowAggregateId());
    assertNotNull(publisher.workflowTimestamps().getFirst());

  }

  @Test
  @DisplayName("An ended workflow is completed, and cancelled where the engine terminated it, with or without a reason")
  public void anEndedWorkflowIsCompletedOrCancelled() {

    final var completed = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_END, MODULE_ID, BPMN_PROCESS_ID, "4711");
    completed.setEndTime(new Date());
    completed.setState(HistoricProcessInstance.STATE_COMPLETED);
    final var cancelled = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_END, MODULE_ID, BPMN_PROCESS_ID, "4712");
    cancelled.setEndTime(new Date());
    cancelled.setState(HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED);
    cancelled.setDeleteReason("an operator deleted it");
    final var withoutAReason = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_END, MODULE_ID, BPMN_PROCESS_ID, "4714");
    withoutAReason.setEndTime(new Date());
    withoutAReason.setState(HistoricProcessInstance.STATE_INTERNALLY_TERMINATED);
    final var updated = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_UPDATE, MODULE_ID, BPMN_PROCESS_ID, "4713");

    final var handler = new OperatonWorkflowHistoryHandler(events);
    handler.handleEvents(List.of(completed, cancelled, withoutAReason, updated));

    assertEquals(
        List
            .of(
                WorkflowEventKind.COMPLETED, WorkflowEventKind.CANCELLED, WorkflowEventKind.CANCELLED,
                WorkflowEventKind.UPDATED),
        publisher.workflowKinds());

  }

  @Test
  @DisplayName("A called process is a step of a case rather than a case, and is passed over")
  public void aCalledProcessIsNotReported() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, "4711");
    event.setSuperProcessInstanceId("the-calling-instance");

    new OperatonWorkflowHistoryHandler(events).handleEvent(event);

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());

  }

  @Test
  @DisplayName("A workflow of a foreign process or without a business key is passed over")
  public void aForeignWorkflowIsNotReported() {

    final var handler = new OperatonWorkflowHistoryHandler(events);
    handler
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, "SomebodyElsesProcess",
                "4711"));
    handler
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, BPMN_PROCESS_ID, null));

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());

  }

  @Test
  @DisplayName("Anything the engine reports which is not about a process instance is left alone")
  public void otherHistoryEventsAreLeftAlone() {

    new OperatonWorkflowHistoryHandler(events)
        .handleEvent(new HistoricTaskInstanceEventEntity());

    assertTrue(publisher.workflows().isEmpty(), publisher.workflows().toString());
    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());

  }

  @Test
  @DisplayName("The engine of this scope is the one the events name")
  public void theScopeIsTheEnginesOwn() {

    assertEquals(ADAPTER_ID, events.scope().adapterId());
    assertEquals(MODULE_ID, events.scope().tenantIdOf(MODULE_ID));
    assertEquals(BPMN_PROCESS_ID, events.scope().scopedProcessIdOf(MODULE_ID, BPMN_PROCESS_ID));

  }

  @Test
  @DisplayName("A process the deployment never mentioned resolves to nothing")
  public void anUnknownProcessResolvesToNothing() {

    assertTrue(processes.resolve(events.scope(), MODULE_ID, null).isEmpty());
    assertTrue(processes.resolve(events.scope(), null, BPMN_PROCESS_ID).isEmpty());
    // registering the same process again changes nothing. That is what happens when a second
    // adapter deploys the same workflow module
    processes.register(MODULE_ID, BPMN_PROCESS_ID);
    assertEquals(
        Optional.of(new OperatonWorkflowProcesses.WorkflowProcess(MODULE_ID, BPMN_PROCESS_ID)),
        processes.resolve(events.scope(), MODULE_ID, BPMN_PROCESS_ID));

  }

  @Test
  @DisplayName("A workflow event of an instance the engine timed differently still carries a timestamp")
  public void anEventWithoutATimeIsStillTimed() {

    final var event = anInstanceEvent(
        HistoryEventTypes.PROCESS_INSTANCE_UPDATE, MODULE_ID, BPMN_PROCESS_ID, "4711");
    event.setStartTime(null);

    new OperatonWorkflowHistoryHandler(events).handleEvent(event);

    final var reported = publisher.workflowTimestamps().getFirst();
    assertTrue(
        reported.isAfter(OffsetDateTime.now().minusMinutes(1)),
        "the timestamp was not filled in: %s".formatted(reported));

  }

  @Test
  @DisplayName("A process the module deploys but nobody claims is not remembered, so nothing of it is reported")
  public void anUnclaimedProcessIsNotReported() {

    final var unclaimed = "UnclaimedProcess";
    final var claimed = "AnotherClaimedProcess";
    final var workflowTaskWiring = mock(WorkflowTaskWiring.class);
    when(workflowTaskWiring.isClaimedByAWorkflowService(MODULE_ID, claimed)).thenReturn(true);
    when(workflowTaskWiring.isClaimedByAWorkflowService(MODULE_ID, unclaimed)).thenReturn(false);
    final var wiring = new OperatonCockpitWiring(processes, workflowTaskWiring, () -> publisher);

    wiring.wireBpmn(MODULE_ID, "unclaimed.bpmn", unclaimed, null, null);
    wiring.wireBpmn(MODULE_ID, "claimed.bpmn", claimed, null, null);

    assertFalse(events.reportsAbout(MODULE_ID, unclaimed));
    assertTrue(events.reportsAbout(MODULE_ID, claimed));
    events
        .reportUserTask(
            aTaskOf(MODULE_ID, unclaimed, "4711"), "TheTask", "the-form",
            UserTaskEventKind.CREATED);
    new OperatonWorkflowHistoryHandler(events)
        .handleEvent(
            anInstanceEvent(
                HistoryEventTypes.PROCESS_INSTANCE_START, MODULE_ID, unclaimed, "4711"));
    assertTrue(publisher.userTasks().isEmpty(), publisher.userTasks().toString());
    assertTrue(publisher.workflowTimestamps().isEmpty(), publisher.workflowTimestamps().toString());

  }

  @Test
  @DisplayName("Wiring a model names its user tasks to the extension, with the values the listener reports")
  public void wiringNamesTheUserTasksOfTheProcess() {

    final var workflowTaskWiring = mock(WorkflowTaskWiring.class);
    when(workflowTaskWiring.isClaimedByAWorkflowService(MODULE_ID, BPMN_PROCESS_ID)).thenReturn(true);
    final var wiring = new OperatonCockpitWiring(processes, workflowTaskWiring, () -> publisher);
    // the adapter put the workflow module in front of the id while it prepared the model
    final var model = Bpmn
        .createExecutableProcess(MODULE_ID
            + "__"
            + BPMN_PROCESS_ID)
        .startEvent()
        .userTask("Activity_review")
        .operatonFormKey("review-form")
        .subProcess()
        .embeddedSubProcess()
        .startEvent()
        .userTask("Activity_inner")
        .endEvent()
        .subProcessDone()
        .endEvent()
        .done();

    wiring.wireBpmn(MODULE_ID, "a.bpmn", BPMN_PROCESS_ID, model, null);

    assertEquals(
        List.of(
            List.of(MODULE_ID, BPMN_PROCESS_ID, "review-form", "Activity_review"),
            List.of(MODULE_ID, BPMN_PROCESS_ID, "Activity_inner", "Activity_inner")),
        publisher.wiredUserTasks());

  }

  @Test
  @DisplayName("A user task of a called process carries the version of the called process, not the one of its caller")
  public void aTaskOfACalledProcessCarriesTheVersionOfTheCalledProcess() {

    final var called = "ACalledProcess";
    final var calledDefinitionId = "ACalledProcess:2:9";
    processes.register(MODULE_ID, called);
    // the called process works on the caller's aggregate, so the task belongs to the caller's case
    final var core = mock(WorkflowTaskWiring.class);
    when(core.workflowsShareTheWorkflowAggregate(anyString(), anyString(), anyString())).thenReturn(true);
    processes.rememberTheCore(core);
    final var versions = new OperatonProcessVersions(
        ADAPTER_ID, null, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> workflowModuleId, null);
    versions.recordDeployed(MODULE_ID, BPMN_PROCESS_ID, DEFINITION_ID, Integer.parseInt(COUNTED_VERSION), null);
    versions.recordDeployed(MODULE_ID, called, calledDefinitionId, 2, null);
    final var taskRegistry = new OperatonTaskRegistry();
    taskRegistry.setProcessVersions(versions);
    final var scoping = new NameClashAvoidanceService(new MigrationAdapterProperties());
    final var engine = new OperatonEngineFacts(ADAPTER_ID, scoping, workflowModuleId -> null, taskRegistry);
    final var withTheCalledProcess = new OperatonCockpitEvents(
        new OperatonScope(ADAPTER_ID, scoping, () -> engine), processes, new OperatonEventBeingReported(), () -> publisher, () -> EventTransaction.CURRENT);

    final var callerDefinition = mock(ProcessDefinitionEntity.class);
    when(callerDefinition.getTenantId()).thenReturn(MODULE_ID);
    when(callerDefinition.getKey()).thenReturn(BPMN_PROCESS_ID);
    when(callerDefinition.getId()).thenReturn(DEFINITION_ID);
    final var caller = mock(ExecutionEntity.class);
    when(caller.getProcessDefinition()).thenReturn(callerDefinition);
    when(caller.getProcessInstanceId()).thenReturn("the-caller");
    when(caller.getProcessInstance()).thenReturn(caller);
    final var callActivity = mock(ExecutionEntity.class);
    when(callActivity.getProcessInstance()).thenReturn(caller);
    when(callActivity.getProcessInstanceId()).thenReturn("the-caller");

    final var calledDefinition = mock(ProcessDefinitionEntity.class);
    when(calledDefinition.getTenantId()).thenReturn(MODULE_ID);
    when(calledDefinition.getKey()).thenReturn(called);
    when(calledDefinition.getId()).thenReturn(calledDefinitionId);
    final var execution = mock(ExecutionEntity.class);
    when(execution.getProcessDefinition()).thenReturn(calledDefinition);
    when(execution.getBusinessKey()).thenReturn("4711");
    when(execution.getProcessInstanceId()).thenReturn("the-called");
    when(execution.getProcessInstance()).thenReturn(execution);
    when(execution.getSuperExecution()).thenReturn(callActivity);
    final var task = mock(DelegateTask.class);
    when(task.getExecution()).thenReturn(execution);
    when(task.getId()).thenReturn("the-task");
    when(task.getEventName()).thenReturn(TaskListener.EVENTNAME_CREATE);

    withTheCalledProcess.reportUserTask(task, "Activity_check", "check", UserTaskEventKind.CREATED);

    assertEquals(1, publisher.userTasks().size());
    final var reported = publisher.userTasks().getFirst();
    // the details provider of the task is picked by this pair, see decision 16 in the
    // Camunda 7 extension's DECISIONS.md. The task belongs to the model of the called process, so
    // that version counts
    assertEquals(called, reported.bpmnProcessId());
    assertEquals("2", reported.processVersion());
    // and the case is still the caller's
    assertEquals("the-caller", reported.workflowId());

  }

}
