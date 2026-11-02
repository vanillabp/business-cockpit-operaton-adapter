package io.vanillabp.cockpit.operaton.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.ProcessEngine;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests that a called process is a case of its own exactly when it has a workflow aggregate of
 * its own.
 * <p>
 * One calling case starts two processes at once through call activities. The step works on the
 * caller's aggregate and belongs to the caller's case. The other process works on an aggregate of
 * its own, and the engine starts it as a case of its own. See decision 3 in the Camunda 7 extension's DECISIONS.md.
 * <p>
 * Each case is also changed and read through the <code>BusinessCockpitService</code> of the
 * application. Its three methods used here first ask VanillaBP what it noted at the start of the
 * case, under the primary process of the aggregate. So they show that the note is found for both
 * kinds of case.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine which outlives its test keeps its job executor
// running against a database the next class works on
@DirtiesContext
public class OperatonCalledProcessCaseTest {

  private static final String MODULE_ID = "c7-cockpit";

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private CaseCallingWorkflowService callingService;

  @Autowired
  private CaseCallingAggregateRepository callingAggregates;

  @Autowired
  private OwnCaseWorkflowService ownCaseService;

  @Autowired
  private OwnCaseAggregateRepository ownCaseAggregates;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private ProcessEngine engine;

  @Autowired
  private ObjectProvider<BusinessCockpitBpmsBridge> bridges;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  private static String idOf(
      final CockpitServer.Request request,
      final String field) {

    final var matcher = Pattern
        .compile("\"%s\"\\s*:\\s*\"([^\"]+)\"".formatted(field))
        .matcher(request.body());
    assertTrue(matcher.find(), "no '%s' in %s".formatted(field, request.body()));
    return matcher.group(1);

  }

  private static boolean namesASubWorkflow(
      final CockpitServer.Request request) {

    return Pattern.compile("\"subWorkflowId\"\\s*:\\s*\"").matcher(request.body()).find();

  }

  private static String marker(
      final String field,
      final String value) {

    return "\"%s\":\"%s\"".formatted(field, value);

  }

  private CaseCallingAggregate aStartedCallingCase(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new CaseCallingAggregate();
          aggregate.setCustomer(customer);
          return callingService.processes().startWorkflow(aggregate);
        });

  }

  private String instanceOf(
      final String bpmnProcessId,
      final String businessKey) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var instance = engine
          .getHistoryService()
          .createHistoricProcessInstanceQuery()
          .processDefinitionKey(bpmnProcessId)
          .processInstanceBusinessKey(businessKey)
          .singleResult();
      if (instance != null) {
        return instance.getId();
      }
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the engine", e);
      }
    }
    throw new AssertionError("No instance of '%s' for case %s".formatted(bpmnProcessId, businessKey));

  }

  private OwnCaseAggregate theOwnCaseOf(
      final CaseCallingAggregate caller) {

    final var customer = OwnCaseWorkflowService.customerOf(String.valueOf(caller.getId()));
    return ownCaseAggregates
        .findAll()
        .stream()
        .filter(aggregate -> customer.equals(aggregate.getCustomer()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no case of its own was built for "
            + customer));

  }

  @Test
  @DisplayName("A called process with an aggregate of its own is a case of its own, and a step stays in the caller's case")
  public void aCalledProcessWithItsOwnAggregateIsACaseOfItsOwn() {

    final var caller = aStartedCallingCase("Ulla");
    final var callerMarker = marker(CaseCallingWorkflowService.CALLER, "Ulla");
    final var ownMarker = marker(
        OwnCaseWorkflowService.OWN_CASE, OwnCaseWorkflowService.customerOf(String.valueOf(caller.getId())));

    // the caller's case and its step
    final var callerCase = CockpitServer.awaitRequest("/workflow/created", callerMarker);
    final var callerId = idOf(callerCase, "workflowId");
    assertEquals(
        instanceOf(CaseCallingWorkflowService.BPMN_PROCESS_ID, String.valueOf(caller.getId())), callerId);
    final var step = CockpitServer.awaitRequest("/usertask/created", callerMarker);
    assertEquals(callerId, idOf(step, "workflowId"), step.body());
    assertNotEquals(callerId, idOf(step, "subWorkflowId"), step.body());

    // the case of its own, reported at its start like a case which nobody called
    final var ownCase = CockpitServer.awaitRequest("/workflow/created", ownMarker);
    final var ownId = idOf(ownCase, "workflowId");
    final var ownAggregate = transactions.execute(status -> theOwnCaseOf(caller));
    assertEquals(
        instanceOf(OwnCaseWorkflowService.BPMN_PROCESS_ID, String.valueOf(ownAggregate.getId())), ownId);
    assertNotEquals(callerId, ownId);
    final var ownTask = CockpitServer.awaitRequest("/usertask/created", ownMarker);
    assertEquals(ownId, idOf(ownTask, "workflowId"), ownTask.body());
    assertFalse(namesASubWorkflow(ownTask), ownTask.body());

    // the step did not become a case: the caller's marker names only the caller's case
    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(callerId),
        CockpitServer
            .matching("/workflow/created")
            .stream()
            .filter(request -> request.body().contains(callerMarker))
            .map(request -> idOf(request, "workflowId"))
            .toList());

    // the bridge names the called instance as the workflow of its own aggregate
    assertEquals(
        List.of(ownId),
        bridges
            .getObject()
            .workflowsOfAggregate(MODULE_ID, OwnCaseWorkflowService.BPMN_PROCESS_ID, String.valueOf(ownAggregate
                .getId()))
            .stream()
            .map(WorkflowReference::workflowId)
            .toList());

    // the three methods which first read VanillaBP's note from the start, for both kinds of case
    final var stepId = idOf(step, "userTaskId");
    final var ownTaskId = idOf(ownTask, "userTaskId");
    CockpitServer.forgetRequests();
    transactions
        .executeWithoutResult(status -> {
          final var attachedCaller = callingAggregates.findById(caller.getId()).orElseThrow();
          callingService.businessCockpit().aggregateChanged(attachedCaller);
          callingService.businessCockpit().aggregateChanged(attachedCaller, stepId);
          final var attachedOwn = ownCaseAggregates.findById(ownAggregate.getId()).orElseThrow();
          ownCaseService.businessCockpit().aggregateChanged(attachedOwn);
          ownCaseService.businessCockpit().aggregateChanged(attachedOwn, ownTaskId);
        });
    CockpitServer.awaitRequest("/workflow/%s/updated".formatted(callerId), callerMarker);
    final var stepUpdated = CockpitServer.awaitRequest("/usertask/%s/updated".formatted(stepId), callerMarker);
    assertEquals(callerId, idOf(stepUpdated, "workflowId"), stepUpdated.body());
    CockpitServer.awaitRequest("/workflow/%s/updated".formatted(ownId), ownMarker);
    final var ownTaskUpdated = CockpitServer
        .awaitRequest("/usertask/%s/updated".formatted(ownTaskId), ownMarker);
    assertEquals(ownId, idOf(ownTaskUpdated, "workflowId"), ownTaskUpdated.body());

    final var read = transactions
        .execute(
            status -> List
                .of(
                    callingService
                        .businessCockpit()
                        .getUserTask(callingAggregates.findById(caller.getId()).orElseThrow(), stepId),
                    ownCaseService
                        .businessCockpit()
                        .getUserTask(ownCaseAggregates.findById(ownAggregate.getId()).orElseThrow(), ownTaskId),
                    // a task of the case of its own is not a task of the caller's case
                    callingService
                        .businessCockpit()
                        .getUserTask(callingAggregates.findById(caller.getId()).orElseThrow(), ownTaskId)));
    assertEquals(
        CaseCallingWorkflowService.CALLED_STEP_PROCESS_ID,
        read.get(0).orElseThrow(() -> new AssertionError("the step was not read")).getBpmnProcessId());
    assertEquals(
        OwnCaseWorkflowService.BPMN_PROCESS_ID,
        read.get(1).orElseThrow(() -> new AssertionError("the task of the own case was not read")).getBpmnProcessId());
    assertTrue(read.get(2).isEmpty(), "a task of a case of its own was read as a task of the caller's case");

  }


  @Test
  @DisplayName("A suspension reports the tasks of the suspended instance only, and a workflow only for a case")
  public void aSuspensionStaysWithTheInstanceWhichWasSuspended() {

    final var caller = aStartedCallingCase("Wanda");
    final var callerMarker = marker(CaseCallingWorkflowService.CALLER, "Wanda");
    final var ownMarker = marker(
        OwnCaseWorkflowService.OWN_CASE, OwnCaseWorkflowService.customerOf(String.valueOf(caller.getId())));
    final var callerId = idOf(CockpitServer.awaitRequest("/workflow/created", callerMarker), "workflowId");
    final var stepTaskId = idOf(CockpitServer.awaitRequest("/usertask/created", callerMarker), "userTaskId");
    final var ownId = idOf(CockpitServer.awaitRequest("/workflow/created", ownMarker), "workflowId");
    final var ownTaskId = idOf(CockpitServer.awaitRequest("/usertask/created", ownMarker), "userTaskId");
    final var stepId = instanceOf(CaseCallingWorkflowService.CALLED_STEP_PROCESS_ID, String.valueOf(caller.getId()));

    // the engine does not suspend a called instance together with its caller, so its tasks can
    // still be worked on and are not reported
    CockpitServer.forgetRequests();
    engine.getRuntimeService().suspendProcessInstanceById(callerId);
    CockpitServer.awaitAnyRequest("/workflow/%s/suspended".formatted(callerId));
    CockpitServer.awaitQuiet();
    assertEquals(List.of(), CockpitServer.matching("/usertask/%s/suspended".formatted(stepTaskId)));
    assertEquals(List.of(), CockpitServer.matching("/usertask/%s/suspended".formatted(ownTaskId)));
    assertEquals(List.of(), CockpitServer.matching("/workflow/%s/suspended".formatted(ownId)));

    // a step reports its task, which belongs to the caller's case. The case itself is not
    // suspended by it
    CockpitServer.forgetRequests();
    engine.getRuntimeService().suspendProcessInstanceById(stepId);
    CockpitServer.awaitAnyRequest("/usertask/%s/suspended".formatted(stepTaskId));
    CockpitServer.awaitQuiet();
    assertEquals(List.of(), CockpitServer.matching("/workflow/%s/suspended".formatted(stepId)));
    assertEquals(List.of(), CockpitServer.matching("/workflow/%s/suspended".formatted(callerId)));

    // a called process with an aggregate of its own is a case of its own
    CockpitServer.forgetRequests();
    engine.getRuntimeService().suspendProcessInstanceById(ownId);
    CockpitServer.awaitAnyRequest("/usertask/%s/suspended".formatted(ownTaskId));
    CockpitServer.awaitAnyRequest("/workflow/%s/suspended".formatted(ownId));

  }

}
