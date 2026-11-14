package io.vanillabp.cockpit.operaton.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.task.Task;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.cockpit.operaton.OperatonUserTaskListener;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.operaton.quarkus.runtime.OperatonQuarkusEngineRegistry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Tests the Operaton half of the Business Cockpit inside a booted Quarkus application. The
 * extension is enabled, its listeners sit on the deployed user tasks, what a workflow does reaches
 * the cockpit server, and the engine answers what the cockpit reads back.
 * <p>
 * It runs the same steps as the Spring Boot test of this repository. It is needed because a
 * correct platform-neutral half does not prove that the glue code of a platform ever calls it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OperatonCockpitTest {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "c7-cockpit";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication
      .forTestClass(OperatonCockpitTest.class, "business-cockpit.yaml")
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

  @Inject
  TestAggregatePersistence aggregates;

  @Inject
  OperatonQuarkusEngineRegistry engines;

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Inject
  UserTransaction transaction;

  private ProcessEngine engine() {

    return engines.engineFor(ADAPTER_ID).getProcessEngine();

  }

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> ADAPTER_ID.equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  private TestAggregate aStartedWorkflow(
      final String customer) throws Exception {

    transaction.begin();
    try {
      final var aggregate = new TestAggregate();
      aggregate.setCustomer(customer);
      final var started = workflowService.processes().startWorkflow(aggregate);
      transaction.commit();
      return started;
    } catch (final RuntimeException e) {
      transaction.rollback();
      throw e;
    }

  }

  private String userTaskIdOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine()
          .getTaskService()
          .createTaskQuery()
          .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
          .singleResult();
      if (task != null) {
        return task.getId();
      }
      sleep();
    }
    throw new AssertionError(
        "The user task of aggregate %s never appeared".formatted(aggregate.getId()));

  }

  private String workflowIdOf(
      final TestAggregate aggregate) {

    return engine()
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
        .singleResult()
        .getId();

  }

  private static void sleep() {

    try {
      Thread.sleep(200);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the engine", e);
    }

  }

  /**
   * When the report says its user task was created. Only the report of an end carries this.
   *
   * @param report The report as the cockpit server received it
   * @return The time the report names, as an instant
   */
  private static Instant startNamedBy(
      final CockpitServer.Request report) {

    final var createdAt = Pattern.compile("\"createdAt\":\"([^\"]+)\"").matcher(report.body());
    assertTrue(createdAt.find(), "the report of an end did not say when its task was created: "
        + report.body());
    return OffsetDateTime.parse(createdAt.group(1)).toInstant();

  }

  /**
   * When the engine created a user task, read before the end.
   *
   * @param userTaskId The user task
   * @return The time of its creation
   */
  private Instant creationOf(
      final String userTaskId) {

    return engine().getTaskService().createTaskQuery().taskId(userTaskId).singleResult().getCreateTime().toInstant();

  }

  @Test
  @DisplayName("The cockpit's task listeners are built-in and run behind VanillaBP's own")
  public void theCockpitListenersRunLastAndAreBuiltIn() {

    final var definitionId = engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
        .latestVersion()
        .singleResult()
        .getId();
    final var deployed = (ProcessDefinitionEntity) engine()
        .getRepositoryService()
        .getProcessDefinition(definitionId);
    final var taskDefinition = ((UserTaskActivityBehavior) deployed
        .findActivity(TestWorkflowService.BPMN_TASK_ID)
        .getActivityBehavior()).getTaskDefinition();

    Stream
        .of(
            TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE,
            TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE)
        .forEach(eventName -> {
          final var listeners = taskDefinition
              .getBuiltinTaskListeners()
              .getOrDefault(eventName, List.of());
          assertFalse(listeners.isEmpty(), "no built-in listener for '%s'".formatted(eventName));
          assertInstanceOf(
              OperatonUserTaskListener.class,
              listeners.getLast(),
              "the cockpit's listener is not the last one for '%s': %s"
                  .formatted(eventName, listeners));
        });

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() throws Exception {

    CockpitServer.forgetRequests();

    final var started = aStartedWorkflow("Anna");
    userTaskIdOf(started);

    // both reports are awaited by the id of this case. Every case reports on '/workflow/created'
    // and '/usertask/created'. Waiting on the path alone takes whichever report arrived first,
    // and then asserts against a case nobody asked about
    final var thisCase = "\"businessId\":\"%s\"".formatted(started.getId());

    final var workflow = CockpitServer.awaitRequest("/workflow/created", thisCase);
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", thisCase);
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(
        userTask
            .body()
            .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("Approve the order"), userTask.body());

    assertEquals(
        TestWorkflowService.APPROVE_NOTE,
        aggregates.byId(started.getId()).getNote(),
        "the details provider did not change the workflow aggregate");

  }

  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);

    final var created = creationOf(userTaskId);
    engine().getTaskService().complete(userTaskId);

    // the report of an end says when its task was created, for a cockpit which gets the end
    // before the creation
    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/completed".formatted(userTaskId));
    assertEquals(created, startNamedBy(task), task.body());
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/completed".formatted(workflowId));
    // the end of a case says nothing about its start. The engine writes the start time only into
    // the event of the start, and this end comes in a command of its own
    assertTrue(workflow.body().contains("\"createdAt\":null"), workflow.body());

  }

  @Test
  @DisplayName("Deleting the workflow reports the task and the workflow as cancelled")
  public void cancellingTheWorkflowIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);

    final var created = creationOf(userTaskId);
    engine().getRuntimeService().deleteProcessInstance(workflowId, "the test cancelled it");

    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    assertEquals(created, startNamedBy(task), task.body());
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));
    // the end of a case says nothing about its start. The engine writes the start time only into
    // the event of the start, and this end comes in a command of its own
    assertTrue(workflow.body().contains("\"createdAt\":null"), workflow.body());
    // the reason of the deletion is the comment of both, because no details provider set one
    assertTrue(task.body().contains("\"comment\":\"the test cancelled it\""), task.body());
    assertTrue(workflow.body().contains("\"comment\":\"the test cancelled it\""), workflow.body());

  }

  @Test
  @DisplayName("Suspending and activating the workflow reports the task and the workflow, with the person who did it")
  public void aSuspensionIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Ulla");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    final var identity = engine().getIdentityService();

    // the user belongs to the tenant of the workflow module, or the engine refuses the call
    identity.setAuthentication("ulla", null, List.of(MODULE_ID));
    try {
      engine().getRuntimeService().suspendProcessInstanceById(workflowId);
    } finally {
      identity.clearAuthentication();
    }
    assertTrue(
        CockpitServer.awaitAnyRequest("/usertask/%s/suspended".formatted(userTaskId)).body()
            .contains("\"initiator\":\"ulla\""));
    assertTrue(
        CockpitServer.awaitAnyRequest("/workflow/%s/suspended".formatted(workflowId)).body()
            .contains("\"initiator\":\"ulla\""));

    identity.setAuthentication("viktor", null, List.of(MODULE_ID));
    try {
      engine().getRuntimeService().activateProcessInstanceById(workflowId);
    } finally {
      identity.clearAuthentication();
    }
    assertTrue(
        CockpitServer.awaitAnyRequest("/usertask/%s/activated".formatted(userTaskId)).body()
            .contains("\"initiator\":\"viktor\""));
    assertTrue(
        CockpitServer.awaitAnyRequest("/workflow/%s/activated".formatted(workflowId)).body()
            .contains("\"initiator\":\"viktor\""));

  }

  @Test
  @DisplayName("An operator changing the task through the engine API alone still produces an update")
  public void aManualOperationIsReported() throws Exception {

    final var aggregate = aStartedWorkflow("Dora");
    final var userTaskId = userTaskIdOf(aggregate);

    engine().getTaskService().setAssignee(userTaskId, "anna");

    final var updated = CockpitServer.awaitAnyRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("\"assignee\":\"anna\""), updated.body());

  }

  /**
   * The two rounds which the caller of the expression-named call activity runs, one string each.
   * So both ways into the bridge are compared as values.
   */
  private static final Set<String> THE_TWO_ROUNDS_OF_THE_CALLER = Set.of("anna#0/2", "bert#1/2");

  /**
   * Starts a case whose caller loops over its signers and calls the signing process once per
   * round. The call activity names that process in an expression.
   *
   * @param customer What the case is called
   * @return The saved case, whose id is the business key of every instance started here
   */
  private TestAggregate aCallerOverTwoSigners(
      final String customer) throws Exception {

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer(customer);
    aggregate.setSigners(List.of("anna", "bert"));
    aggregates.save(aggregate);
    transaction.commit();

    engine()
        .getRuntimeService()
        .createProcessInstanceByKey(TestWorkflowService.EXPRESSION_CALL_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        // the deployment could not read this: each instance chooses the process to call
        .setVariable(
            TestWorkflowService.CALLED_PROCESS_VARIABLE, TestWorkflowService.CALLED_SIGN_PROCESS_ID)
        .execute();
    return aggregate;

  }

  /**
   * The round of the caller as the cockpit reports it when the task is created. This is the way
   * in through the event.
   */
  @Test
  @DisplayName("The round of a caller reaches the cockpit although the call activity names the called process in an expression")
  public void theLevelOfACallerReachesTheCockpit() throws Exception {

    CockpitServer.forgetRequests();
    aCallerOverTwoSigners("Nadia");

    final var bodies = awaitTheSigningOfTheCaller();

    THE_TWO_ROUNDS_OF_THE_CALLER
        .forEach(
            round -> assertTrue(
                bodies.stream().anyMatch(body -> body.contains("\"callerRound\":\"%s\"".formatted(round))),
                "the details of the task do not name the round '%s' the model runs, %s"
                    .formatted(round, bodies)));

  }

  /**
   * The same round, read from the engine instead. This is the other way in. The two ways give the
   * walk different things, an execution and an id. So if one of them answers, that does not mean
   * the other one does.
   */
  @Test
  @DisplayName("The same round is answered when the task is read instead of reported")
  public void theLevelOfACallerIsAnsweredWhenTheTaskIsRead() throws Exception {

    final var aggregate = aCallerOverTwoSigners("Olivia");
    final var signing = awaitTheSigningTasks(aggregate);

    final var answered = signing
        .stream()
        .map(
            task -> bridge()
                .prefilledUserTaskDetails(signingReferenceOf(aggregate, task))
                .orElseThrow()
                .multiInstances()
                .get(TestWorkflowService.CALL_ACTIVITY_ELEMENT))
        .map(
            level -> level == null
                ? "nothing"
                : "%s#%s/%s".formatted(level.element(), level.index(), level.total()))
        .collect(Collectors.toSet());

    assertEquals(
        THE_TWO_ROUNDS_OF_THE_CALLER,
        answered,
        "reading the task answers other rounds than the model runs");

  }

  /** The user tasks of the called process, one for each round of the caller. */
  private List<Task> awaitTheSigningTasks(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var tasks = engine()
          .getTaskService()
          .createTaskQuery()
          .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
          .taskDefinitionKey(TestWorkflowService.CALLER_SIGN_TASK_ID)
          .list();
      if (tasks.size() >= 2) {
        return tasks;
      }
      sleep();
    }
    throw new AssertionError(
        "Fewer than two signing tasks of the called process appeared for case %s"
            .formatted(aggregate.getId()));

  }

  /**
   * One of those tasks as the cockpit addresses it. The workflow is the called instance and not
   * the business case, because a reference built by the extension carries that for a task of a
   * called process.
   */
  private UserTaskReference signingReferenceOf(
      final TestAggregate aggregate,
      final Task task) {

    return new UserTaskReference(
        ADAPTER_ID, MODULE_ID, TestWorkflowService.CALLED_SIGN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, String
            .valueOf(aggregate.getId()), task.getProcessInstanceId(), task
                .getId(), TestWorkflowService.CALLER_SIGN_TASK_DEFINITION, TestWorkflowService.CALLER_SIGN_TASK_ID);

  }

  /** The reports of the two signing tasks of the called process. */
  private List<String> awaitTheSigningOfTheCaller() {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var reported = CockpitServer
          .received()
          .stream()
          .filter(request -> request.path().endsWith("/usertask/created"))
          .map(CockpitServer.Request::body)
          .filter(
              body -> body
                  .contains(
                      "\"taskDefinition\":\"%s\""
                          .formatted(TestWorkflowService.CALLER_SIGN_TASK_DEFINITION)))
          .toList();
      if (reported.size() >= 2) {
        return reported;
      }
      sleep();
    }
    throw new AssertionError(
        "Fewer than two signing tasks were reported: %s"
            .formatted(CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

  @Test
  @DisplayName("A multi-instance user task carries the item, the index and the total")
  public void aMultiInstanceUserTaskCarriesItsContext() throws Exception {

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Jonas");
    aggregate.setSigners(List.of("anna", "bert", "cleo"));
    aggregates.save(aggregate);
    transaction.commit();

    // started through the engine and not through VanillaBP: the multi-instance process is a
    // secondary process of this aggregate, and the test is about what the listener reports
    engine()
        .getRuntimeService()
        .createProcessInstanceByKey(TestWorkflowService.MULTI_INSTANCE_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        // a variable of the process instance, which is the parent of the multi-instance executions
        .setVariable(TestWorkflowService.ORDER_KIND_VARIABLE, "express")
        .execute();

    final var bodies = awaitSignedTasks();
    assertTrue(
        bodies.stream().anyMatch(body -> body.contains("\"signer\":\"anna\"")), bodies.toString());
    assertTrue(
        bodies.stream().anyMatch(body -> body.contains("\"signer\":\"cleo\"")), bodies.toString());
    bodies.forEach(body -> assertTrue(body.contains("\"total\":\"3\""), body));

    // '@TaskParam' is bound from every variable the task can see: the one of its own execution,
    // and the one of the enclosing process instance
    assertTrue(
        bodies.stream().anyMatch(body -> body.contains("\"signerVariable\":\"anna\"")),
        bodies.toString());
    bodies.forEach(body -> assertTrue(body.contains("\"orderKind\":\"express\""), body));

  }

  /**
   * The reports of the multi-instance task. They are told apart from what a workflow of another
   * test may report while this one waits.
   */
  private List<String> awaitSignedTasks() {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var signed = CockpitServer
          .received()
          .stream()
          .filter(request -> request.path().endsWith("/usertask/created"))
          .map(CockpitServer.Request::body)
          .filter(
              body -> body
                  .contains(
                      "\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.SIGN_TASK_DEFINITION)))
          .toList();
      if (signed.size() >= 3) {
        return signed;
      }
      sleep();
    }
    throw new AssertionError(
        "Fewer than three signing tasks were reported: %s"
            .formatted(CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

  @Test
  @DisplayName("The engine answers what the cockpit reads back about a case")
  public void theEngineAnswersWhatTheCockpitReadsBack() throws Exception {

    final var aggregate = aStartedWorkflow("Mira");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    assertEquals(ADAPTER_ID, bridge().adapterId());
    assertEquals("operaton", bridge().adapterType());

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(userTaskId, userTasks.getFirst().userTaskId());

    final var workflows = bridge()
        .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId);
    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(workflowId, workflows.getFirst().workflowId());
    assertTrue(bridge().prefilledWorkflowDetails(workflows.getFirst()).isPresent());

    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, userTaskId)
            .isPresent());

    // and a task the engine has finished with is no longer answered. Its end was reported while
    // the task was still there, so the cockpit has nothing left to ask about
    engine().getTaskService().complete(userTaskId);
    assertTrue(
        bridge()
            .prefilledUserTaskDetails(
                new UserTaskReference(
                    ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, aggregateId, workflowId, userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID))
            .isEmpty(),
        "a task the engine has finished with was answered from somewhere");
    final var completed = CockpitServer
        .awaitAnyRequest("/usertask/%s/completed".formatted(userTaskId));
    assertTrue(completed.body().contains("Approve the order"), completed.body());

  }

  @Test
  @DisplayName("What the engine never knew is answered as empty, not as a failure")
  public void whatTheEngineDoesNotKnowIsEmpty() throws Exception {

    final var aggregate = aStartedWorkflow("Nils");
    userTaskIdOf(aggregate);
    final var aggregateId = String.valueOf(aggregate.getId());

    assertTrue(
        bridge()
            .prefilledUserTaskDetails(
                new UserTaskReference(
                    ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, aggregateId, workflowIdOf(
                        aggregate), "no-such-task", TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID))
            .isEmpty());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    ADAPTER_ID, MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, aggregateId, "no-such-workflow"))
            .isEmpty());
    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, aggregateId, "no-such-task")
            .isEmpty());
    assertEquals(
        List.of(),
        bridge()
            .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, "no-such-case"));

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();
    assertTrue(registration.path().endsWith("/workflow-module/%s".formatted(MODULE_ID)), registration.path());

  }

}
