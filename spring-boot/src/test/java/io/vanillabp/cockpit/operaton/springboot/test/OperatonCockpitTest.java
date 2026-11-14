package io.vanillabp.cockpit.operaton.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.cockpit.operaton.OperatonUserTaskListener;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests the Business Cockpit on a real embedded Operaton engine, from what the engine did to the
 * request the cockpit server receives.
 * <p>
 * Only the cockpit server is faked. The engine runs the workflow. Its built-in task listeners and
 * its history handler report what happened. The report is built from that event, and the
 * application's details provider runs on it. The outbox entry which carries the report is written
 * inside the engine's own transaction. The test asserts what arrives at the server once that
 * transaction has committed.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine which outlives its test keeps its job executor
// running against a database the next class works on
@DirtiesContext
public class OperatonCockpitTest {

  private static final String MODULE_ID = "c7-cockpit";

  /**
   * The user to whom the test about a refused report assigns its task. Every test class of this
   * module shares the cockpit server, and a report is recognized by what it carries. So only that
   * test uses this name.
   */
  private static final String THE_ASSIGNEE_OF_THIS_TEST = "quirins-approver";

  /**
   * How often the application repeats a transaction which ran into a conflict. Two would be
   * enough for the one other writer. The third makes sure that a repetition which itself runs into
   * the next report does not end the test.
   */
  private static final int ATTEMPTS_OF_THE_APPLICATION = 3;

  /**
   * The two rounds which the caller of the expression-named call activity runs, one string each.
   * So both ways into the bridge are compared as values.
   */
  private static final Set<String> THE_TWO_ROUNDS_OF_THE_CALLER = Set.of("anna#0/2", "bert#1/2");

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestWorkflowService workflowService;

  @Autowired
  private TestAggregateRepository aggregates;

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

  /**
   * Changes one case and reports the change, as an application whose workflow aggregate has a
   * version attribute has to do it: in a transaction which is repeated if somebody else wrote the
   * same case in between.
   * <p>
   * That somebody is the Business Cockpit itself. Its details provider reads the case whenever
   * the engine reports something, and it writes the case back. If that report runs on a thread of
   * the engine, above all a job executor, the two transactions overlap. Without the version
   * attribute, the later writer wins without a word. With it, one of them gets a conflict and
   * repeats.
   *
   * @param aggregateId The case to change
   * @param changeAndReport Changes the attached case and reports it to the cockpit
   */
  private void changeTheCase(
      final Long aggregateId,
      final Consumer<TestAggregate> changeAndReport) {

    for (var attempt = 1;; attempt++) {
      try {
        transactions
            .executeWithoutResult(status -> {
              final var attached = aggregates.findById(aggregateId).orElseThrow();
              changeAndReport.accept(attached);
              aggregates.save(attached);
            });
        return;
      } catch (final OptimisticLockingFailureException e) {
        if (attempt >= ATTEMPTS_OF_THE_APPLICATION) {
          throw new AssertionError(
              "The application gave up after %d attempts at changing case %s"
                  .formatted(attempt, aggregateId), e);
        }
      }
    }

  }

  /**
   * Waits for a report of one kind which carries the value the application wrote.
   * <p>
   * A report with the older value has two possible causes, and each needs a different fix: the
   * report read the case too early, or the case itself lost the change. So a failure also names
   * what the case holds now. See the version attribute of {@link TestAggregate}.
   *
   * @param pathSuffix What the report's path has to end with
   * @param expected What its body has to carry
   * @param aggregate The case the report is about
   * @return The report
   */
  private CockpitServer.Request awaitReportCarrying(
      final String pathSuffix,
      final String expected,
      final TestAggregate aggregate) {

    try {
      return CockpitServer.awaitRequest(pathSuffix, expected);
    } catch (final AssertionError e) {
      throw new AssertionError(
          "%s And the stored case now carries the customer '%s'."
              .formatted(
                  e.getMessage(),
                  transactions
                      .execute(
                          status -> aggregates
                              .findById(aggregate.getId())
                              .orElseThrow()
                              .getCustomer())), e);
    }

  }

  private TestAggregate aStartedWorkflow(
      final String customer) {

    return transactions
        .execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setCustomer(customer);
          return workflowService.processes().startWorkflow(aggregate);
        });

  }

  /**
   * What the reports of one case carry and the reports of all other cases do not. Every case
   * reports on the paths {@code /workflow/created} and {@code /usertask/created}, so a wait on one
   * of them has to say which case it is about.
   *
   * @param aggregate The case under test
   * @return What a report of that case carries
   */
  private static String reportsOf(
      final TestAggregate aggregate) {

    return "\"businessId\":\"%s\"".formatted(aggregate.getId());

  }

  private String userTaskIdOf(
      final TestAggregate aggregate) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var task = engine
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

    return engine
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

    return engine.getTaskService().createTaskQuery().taskId(userTaskId).singleResult().getCreateTime().toInstant();

  }

  @Test
  @DisplayName("A started workflow and its user task reach the cockpit, enriched by the application")
  public void aStartedWorkflowReachesTheCockpit() {

    final var aggregate = aStartedWorkflow("Anna");
    userTaskIdOf(aggregate);

    // both reports are awaited by the id of this case. Every case reports on '/workflow/created'
    // and '/usertask/created'. Waiting on the path alone takes whichever report arrived first,
    // and then asserts against a case nobody asked about
    final var thisCase = reportsOf(aggregate);

    final var workflow = CockpitServer.awaitRequest("/workflow/created", thisCase);
    assertTrue(workflow.body().contains("\"customer\":\"Anna\""), workflow.body());
    assertTrue(workflow.body().contains(TestWorkflowService.BPMN_PROCESS_ID), workflow.body());

    final var userTask = CockpitServer.awaitRequest("/usertask/created", thisCase);
    assertTrue(userTask.body().contains("\"customer\":\"Anna\""), userTask.body());
    assertTrue(userTask.body().contains("\"event\":\"CREATED\""), userTask.body());
    assertTrue(
        userTask.body().contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.TASK_DEFINITION)),
        userTask.body());
    assertTrue(userTask.body().contains("\"candidateGroups\":[\"approvers\"]"), userTask.body());
    // the cockpit falls back to the BPMN name when nothing else gave a title
    assertTrue(userTask.body().contains("Approve the order"), userTask.body());

    // The details provider read the customer in that body from the case. So the report shows
    // that the provider ran on the real aggregate. Whether the case KEEPS what the provider wrote
    // into it is not asserted, on purpose. The write is part of the transaction the engine
    // reported in, and whoever runs that transaction can still roll it back. Then the entry is
    // gone, and so is the write.

  }

  @Test
  @DisplayName("Completing the user task reports the task and the workflow as completed")
  public void completingTheUserTaskIsReported() {

    final var aggregate = aStartedWorkflow("Bert");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);

    final var created = creationOf(userTaskId);
    engine.getTaskService().complete(userTaskId);

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
  public void cancellingTheWorkflowIsReported() {

    final var aggregate = aStartedWorkflow("Cleo");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    final var created = creationOf(userTaskId);
    engine.getRuntimeService().deleteProcessInstance(workflowId, "the test cancelled it");

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
  @DisplayName("A comment the details providers set wins over the reason of the deletion")
  public void theCommentOfTheModuleWinsOverTheDeleteReason() {

    final var aggregate = aStartedWorkflow(TestWorkflowService.CUSTOMER_WITH_A_COMMENT);
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    engine.getRuntimeService().deleteProcessInstance(workflowId, "an operator deleted it");

    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));
    final var ofTheModule = "\"comment\":\"%s\"".formatted(TestWorkflowService.COMMENT_OF_THE_MODULE);
    assertTrue(task.body().contains(ofTheModule), task.body());
    assertTrue(workflow.body().contains(ofTheModule), workflow.body());
    assertFalse(task.body().contains("an operator deleted it"), task.body());
    assertFalse(workflow.body().contains("an operator deleted it"), workflow.body());

  }

  @Test
  @DisplayName("A deletion without a reason cancels the task and the workflow and leaves the comment empty")
  public void aDeletionWithoutAReasonLeavesTheCommentEmpty() {

    final var aggregate = aStartedWorkflow("Nora");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    engine.getRuntimeService().deleteProcessInstance(workflowId, null);

    // the engine gives the task the delete reason 'deleted' here, its stand-in for "none"
    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    assertTrue(task.body().contains("\"comment\":null"), task.body());
    // the engine writes no delete reason for the instance, and it is cancelled all the same
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));
    assertTrue(workflow.body().contains("\"comment\":null"), workflow.body());

  }

  @Test
  @DisplayName("A batch which deletes instances hands the reason to the task and the workflow")
  public void aBatchDeletionHandsOnTheReason() {

    final var aggregate = aStartedWorkflow("Rita");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    final var batch = engine
        .getRuntimeService()
        .deleteProcessInstancesAsync(List.of(workflowId), "an operator deleted a batch");
    runTheJobsOf(batch.getSeedJobDefinitionId());
    runTheJobsOf(batch.getBatchJobDefinitionId());

    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    assertTrue(task.body().contains("\"comment\":\"an operator deleted a batch\""), task.body());
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));
    assertTrue(workflow.body().contains("\"comment\":\"an operator deleted a batch\""), workflow.body());

  }

  @Test
  @DisplayName("A modification which cancels the only task cancels the workflow, but it is no deletion and gives no comment")
  public void aModificationIsNoDeletion() {

    final var aggregate = aStartedWorkflow("Sven");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    engine
        .getRuntimeService()
        .createProcessInstanceModification(workflowId)
        .cancelAllForActivity(TestWorkflowService.BPMN_TASK_ID)
        .execute();

    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    assertTrue(task.body().contains("\"comment\":null"), task.body());
    // nothing is left to run, so the engine tears the instance down: it did not reach an end event
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));
    assertTrue(workflow.body().contains("\"comment\":null"), workflow.body());

  }

  /**
   * Runs the jobs of one job definition, next to the job executor. Whichever of the two takes a
   * job first runs it. The other one finds it gone, half gone or still busy, which is no failure
   * here.
   */
  private void runTheJobsOf(
      final String jobDefinitionId) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var jobs = engine.getManagementService().createJobQuery().jobDefinitionId(jobDefinitionId).list();
      if (jobs.isEmpty()) {
        return;
      }
      jobs
          .forEach(job -> {
            try {
              engine.getManagementService().executeJob(job.getId());
            } catch (final RuntimeException e) {
              // The job executor ran it first, or at the same moment. Only a job which is still
              // there failed for real, and the next round looks at it again.
              if (engine.getManagementService().createJobQuery().jobId(job.getId()).count() == 0) {
                return;
              }
            }
          });
    }
    throw new AssertionError("The jobs of the job definition %s did not run".formatted(jobDefinitionId));

  }

  @Test
  @DisplayName("A task a boundary event takes away carries no comment, the engine's words are no reason")
  public void aModelledWayOutLeavesTheCommentEmpty() {

    final var aggregate = aStartedWorkflow("Paul");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    engine.getRuntimeService().createMessageCorrelation("Withdrawn").processInstanceId(workflowId).correlate();

    final var task = CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    assertTrue(task.body().contains("\"comment\":null"), task.body());
    // the modelled way out is a normal end of the case
    final var workflow = CockpitServer.awaitAnyRequest("/workflow/%s/completed".formatted(workflowId));
    assertTrue(workflow.body().contains("\"comment\":null"), workflow.body());

  }

  @Test
  @DisplayName("An operator changing the task through the engine API alone still produces an update")
  public void aManualOperationIsReported() {

    final var aggregate = aStartedWorkflow("Dora");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));
    CockpitServer.forgetRequests();

    // VanillaBP is not involved here. A task list or the Operaton web application does this, and
    // the cockpit has to hear about it
    engine.getTaskService().setAssignee(userTaskId, "anna");

    final var updated = CockpitServer.awaitAnyRequest("/usertask/%s/updated".formatted(userTaskId));
    assertTrue(updated.body().contains("\"assignee\":\"anna\""), updated.body());

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates its workflow")
  public void aggregateChangedUpdatesTheWorkflow() {

    final var aggregate = aStartedWorkflow("Emil");
    userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/workflow/created", reportsOf(aggregate));
    final var workflowId = workflowIdOf(aggregate);
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        attached -> {
          attached.setCustomer("Emil the second");
          workflowService.businessCockpit().aggregateChanged(attached);
        });

    // the report carries what the application wrote, not the old value of the case. It is built
    // inside this transaction, so the details provider reads the case which the application has
    // just changed but not written yet
    awaitReportCarrying(
        "/workflow/%s/updated".formatted(workflowId), "Emil the second", aggregate);

  }

  @Test
  @DisplayName("An application reporting a changed aggregate updates the named user task")
  public void aggregateChangedUpdatesTheNamedUserTask() {

    final var aggregate = aStartedWorkflow("Frida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));
    CockpitServer.forgetRequests();

    changeTheCase(
        aggregate.getId(),
        attached -> {
          attached.setCustomer("Frida the second");
          workflowService.businessCockpit().aggregateChanged(attached, userTaskId);
        });

    awaitReportCarrying(
        "/usertask/%s/updated".formatted(userTaskId), "Frida the second", aggregate);

  }

  @Test
  @DisplayName("An application can read one user task of its own case, and no other")
  public void getUserTaskAnswersOnlyForItsOwnAggregate() {

    final var aggregate = aStartedWorkflow("Gustl");
    final var userTaskId = userTaskIdOf(aggregate);
    final var otherAggregate = aStartedWorkflow("Heidi");
    userTaskIdOf(otherAggregate);

    final var userTask = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(userTask.isPresent(), "the case's own task was not answered");
    assertEquals(userTaskId, userTask.get().getId());
    assertEquals(TestWorkflowService.BPMN_TASK_ID, userTask.get().getBpmnTaskId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTask.get().getTaskDefinition());

    final var foreign = transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(
                    aggregates.findById(otherAggregate.getId()).orElseThrow(), userTaskId));
    assertTrue(foreign.isEmpty(), "a task of another case was answered");

  }

  @Test
  @DisplayName("Reading a user task reports nothing to the cockpit")
  public void readingAUserTaskReportsNothing() {

    final var aggregate = aStartedWorkflow("Ida");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));
    CockpitServer.forgetRequests();

    transactions
        .execute(
            status -> workflowService
                .businessCockpit()
                .getUserTask(aggregates.findById(aggregate.getId()).orElseThrow(), userTaskId));

    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer.matching("/usertask/%s/updated".formatted(userTaskId)),
        "reading a task reported it as changed");

  }

  private BusinessCockpitBpmsBridge bridge() {

    return bridges
        .stream()
        .filter(candidate -> "c7".equals(candidate.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  private UserTaskReference referenceOf(
      final TestAggregate aggregate,
      final String userTaskId) {

    return new UserTaskReference(
        "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, String
            .valueOf(aggregate.getId()), workflowIdOf(
                aggregate), userTaskId, TestWorkflowService.TASK_DEFINITION, TestWorkflowService.BPMN_TASK_ID);

  }

  @Test
  @DisplayName("A multi-instance user task carries the item, the index and the total")
  public void aMultiInstanceUserTaskCarriesItsContext() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Jonas");
          fresh.setSigners(List.of("anna", "bert", "cleo"));
          return aggregates.save(fresh);
        });
    // started through the engine and not through VanillaBP: the multi-instance process is a
    // secondary process of this aggregate, and the test is about what the listener reports
    engine
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
   * Starts a case whose caller loops over its signers and calls the signing process once per
   * round. The call activity names that process in an expression.
   *
   * @param customer What the case is called
   * @return The saved case, whose id is the business key of every instance started here
   */
  private TestAggregate aCallerOverTwoSigners(
      final String customer) {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer(customer);
          fresh.setSigners(List.of("anna", "bert"));
          return aggregates.save(fresh);
        });
    engine
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
  public void theLevelOfACallerReachesTheCockpit() {

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
  public void theLevelOfACallerIsAnsweredWhenTheTaskIsRead() {

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
      final var tasks = engine
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
        "c7", MODULE_ID, TestWorkflowService.CALLED_SIGN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, String
            .valueOf(aggregate.getId()), task.getProcessInstanceId(), task
                .getId(), TestWorkflowService.CALLER_SIGN_TASK_DEFINITION, TestWorkflowService.CALLER_SIGN_TASK_ID);

  }

  /** The reports of the two signing tasks of the called process. */
  private List<String> awaitTheSigningOfTheCaller() {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var reported = CockpitServer
          .matching("/usertask/created")
          .stream()
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
  @DisplayName("The end of a task reports the variables that task saw, which nothing could read afterwards")
  public void aCompletedTaskReportsWhatItSaw() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Rosa");
          fresh.setSigners(List.of("rosa"));
          return aggregates.save(fresh);
        });
    engine
        .getRuntimeService()
        .createProcessInstanceByKey(TestWorkflowService.MULTI_INSTANCE_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        .setVariable(TestWorkflowService.ORDER_KIND_VARIABLE, "overnight")
        .execute();

    final var signing = userTaskIdOf(aggregate);
    engine.getTaskService().complete(signing);

    // that call ends the signing task, and the variables it could see are gone too: an engine
    // keeps none for a task it has finished with. The details provider bound them from the task
    // which the listener was given. That is the reason to build the report there
    final var completed = CockpitServer
        .awaitAnyRequest("/usertask/%s/completed".formatted(signing));
    assertTrue(completed.body().contains("\"signerVariable\":\"rosa\""), completed.body());
    assertTrue(completed.body().contains("\"orderKind\":\"overnight\""), completed.body());

  }

  /**
   * The reports of the multi-instance task. They are told apart from what a workflow of another
   * test may report while this one waits.
   */
  private List<String> awaitSignedTasks() {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var signed = CockpitServer
          .matching("/usertask/created")
          .stream()
          .map(CockpitServer.Request::body)
          .filter(
              body -> body
                  .contains("\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.SIGN_TASK_DEFINITION)))
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

  /**
   * Starts a case whose workflow the engine started, not VanillaBP. These BPMN processes are
   * secondary processes of the aggregate. The test is about the name of their user task, not
   * about how a workflow is started.
   *
   * @param bpmnProcessId The process to start
   * @param customer What the case is called
   * @param variables What the instance carries
   * @return The saved case, whose id is the business key of the started workflow
   */
  private TestAggregate aWorkflowStartedByTheEngine(
      final String bpmnProcessId,
      final String customer,
      final Map<String, Object> variables) {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer(customer);
          return aggregates.save(fresh);
        });
    var start = engine
        .getRuntimeService()
        .createProcessInstanceByKey(bpmnProcessId)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()));
    for (final var variable : variables.entrySet()) {
      start = start.setVariable(variable.getKey(), variable.getValue());
    }
    start.execute();
    return aggregate;

  }

  /**
   * What the bridge calls the one user task of a case.
   *
   * @param bpmnProcessId The BPMN process the case runs on
   * @param aggregate The case
   * @return The task definition by which the cockpit addresses that task
   */
  private String taskDefinitionReadBackFor(
      final String bpmnProcessId,
      final TestAggregate aggregate) {

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, bpmnProcessId, String.valueOf(aggregate.getId()), List.of());
    assertEquals(1, userTasks.size(), userTasks.toString());
    return userTasks.getFirst().taskDefinition();

  }

  @Test
  @DisplayName("A user task the modeller gave no form is called by its element id")
  public void aTaskWithoutAFormKeyIsCalledByItsElementId() {

    final var aggregate = aWorkflowStartedByTheEngine(
        TestWorkflowService.NO_FORM_KEY_PROCESS_ID, "Xaver", Map.of());

    final var reported = CockpitServer
        .awaitRequest(
            "/usertask/created",
            "\"bpmnTaskId\":\"%s\"".formatted(TestWorkflowService.NO_FORM_KEY_TASK_ID));
    assertTrue(
        reported
            .body()
            .contains(
                "\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.NO_FORM_KEY_TASK_ID)),
        reported.body());

    assertEquals(
        TestWorkflowService.NO_FORM_KEY_TASK_ID,
        taskDefinitionReadBackFor(TestWorkflowService.NO_FORM_KEY_PROCESS_ID, aggregate),
        "the task the cockpit reads back is called something else than the task it was told about");

  }

  @Test
  @DisplayName("A form key which is an expression names one task, not one per workflow instance")
  public void anExpressionFormKeyNamesOneTask() {

    final var firstCase = aWorkflowStartedByTheEngine(
        TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID, "Yvonne",
        Map.of(TestWorkflowService.FORM_NAME_VARIABLE, "the-express-form"));
    final var secondCase = aWorkflowStartedByTheEngine(
        TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID, "Zeno",
        Map.of(TestWorkflowService.FORM_NAME_VARIABLE, "the-standard-form"));

    // the engine really computes two different strings from that one form key. So a reading
    // which asks the task and not the model would report two tasks here
    assertEquals(
        Set.of("the-express-form", "the-standard-form"),
        engine
            .getTaskService()
            .createTaskQuery()
            .taskDefinitionKey(TestWorkflowService.EXPRESSION_FORM_KEY_TASK_ID)
            .initializeFormKeys()
            .list()
            .stream()
            .map(Task::getFormKey)
            .collect(java.util.stream.Collectors.toSet()),
        "the engine did not evaluate the form key, so this test proves nothing");

    assertEquals(
        TestWorkflowService.EXPRESSION_FORM_KEY,
        taskDefinitionReadBackFor(
            TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID, firstCase));
    assertEquals(
        TestWorkflowService.EXPRESSION_FORM_KEY,
        taskDefinitionReadBackFor(
            TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID, secondCase));

    final var reported = CockpitServer
        .awaitRequest(
            "/usertask/created",
            "\"bpmnTaskId\":\"%s\"".formatted(TestWorkflowService.EXPRESSION_FORM_KEY_TASK_ID));
    assertTrue(
        reported
            .body()
            .contains(
                "\"taskDefinition\":\"%s\"".formatted(TestWorkflowService.EXPRESSION_FORM_KEY)),
        reported.body());

  }

  @Test
  @DisplayName("A task the engine has finished with is answered no more, and its end was reported anyway")
  public void aFinishedTaskIsNotReadAgain() {

    final var aggregate = aStartedWorkflow("Klara");
    final var userTaskId = userTaskIdOf(aggregate);
    final var reference = referenceOf(aggregate, userTaskId);
    engine.getTaskService().complete(userTaskId);

    // the report of the completion was built while the task was still there. So nothing is left
    // to read afterwards, and the cockpit does not ask for anything afterwards either
    assertTrue(
        bridge().prefilledUserTaskDetails(reference).isEmpty(),
        "a task the engine has finished with was answered from somewhere");

    // and the end was reported anyway, with the BPMN name of the task and with what the details
    // provider added
    final var completed = CockpitServer
        .awaitAnyRequest("/usertask/%s/completed".formatted(userTaskId));
    assertTrue(completed.body().contains("Approve the order"), completed.body());
    assertTrue(completed.body().contains("\"customer\":\"Klara\""), completed.body());

  }

  @Test
  @DisplayName("A report waiting for a cockpit server carries the state of its event, not of its dispatch")
  public void aReportCarriesTheStateOfItsEvent() {

    final var aggregate = aStartedWorkflow("Quirin");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    // the first attempt is refused. So this report leaves the application only after the engine
    // changed the task and finished with it. Whenever the outbox sends it, it says what the
    // engine said at the moment of the assignment
    CockpitServer.refuseRequestsAbout(THE_ASSIGNEE_OF_THIS_TEST, 1);
    engine.getTaskService().setAssignee(userTaskId, THE_ASSIGNEE_OF_THIS_TEST);

    engine.getTaskService().complete(userTaskId);

    // the assertion is that this report arrives at all. When the outbox tries it again, the
    // engine no longer has the task. A report built at the dispatch would find no running task
    // to talk about, and it would be dropped for good
    final var updated = CockpitServer
        .awaitRequest("/usertask/%s/updated".formatted(userTaskId), THE_ASSIGNEE_OF_THIS_TEST);
    assertTrue(
        updated.body().contains("\"assignee\":\"%s\"".formatted(THE_ASSIGNEE_OF_THIS_TEST)),
        updated.body());
    assertTrue(updated.body().contains("\"customer\":\"Quirin\""), updated.body());

  }

  @Test
  @DisplayName("A workflow and its user task carry the version Operaton counted for the deployed process")
  public void theCountedVersionIsWhatIsReported() {

    final var aggregate = aStartedWorkflow("Vera");
    final var userTaskId = userTaskIdOf(aggregate);
    final var countedByCamunda = String
        .valueOf(
            engine
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
                .latestVersion()
                .singleResult()
                .getVersion());

    final var workflows = bridge()
        .workflowsOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()));
    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(
        countedByCamunda,
        bridge().prefilledWorkflowDetails(workflows.getFirst()).orElseThrow().bpmnProcessVersion(),
        "the workflow carries a version Operaton did not count");
    assertEquals(
        countedByCamunda,
        bridge()
            .prefilledUserTaskDetails(referenceOf(aggregate, userTaskId))
            .orElseThrow()
            .bpmnProcessVersion(),
        "the user task carries a version Operaton did not count");

  }

  @Test
  @DisplayName("The workflows of an aggregate include the one which already ended")
  public void theWorkflowsOfAnAggregateIncludeTheFinishedOne() {

    final var aggregate = aStartedWorkflow("Lena");
    final var userTaskId = userTaskIdOf(aggregate);
    final var workflowId = workflowIdOf(aggregate);
    engine.getTaskService().complete(userTaskId);

    final var workflows = bridge()
        .workflowsOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()));

    assertEquals(1, workflows.size(), workflows.toString());
    assertEquals(workflowId, workflows.getFirst().workflowId());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(workflows.getFirst())
            .isPresent(),
        "the finished workflow was not readable");

  }

  @Test
  @DisplayName("The user tasks of an aggregate are answered without naming any of them")
  public void theUserTasksOfAnAggregateAreAnsweredWithoutNamingThem() {

    final var aggregate = aStartedWorkflow("Mira");
    final var userTaskId = userTaskIdOf(aggregate);

    final var userTasks = bridge()
        .userTasksOfAggregate(
            MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
            List.of());

    assertEquals(1, userTasks.size(), userTasks.toString());
    assertEquals(userTaskId, userTasks.getFirst().userTaskId());
    assertEquals(TestWorkflowService.TASK_DEFINITION, userTasks.getFirst().taskDefinition());
    assertEquals("c7", bridge().adapterId());
    assertEquals("operaton", bridge().adapterType());

  }

  @Test
  @DisplayName("What the engine never knew is answered as empty, not as a failure")
  public void whatTheEngineDoesNotKnowIsEmpty() {

    final var aggregate = aStartedWorkflow("Nils");
    userTaskIdOf(aggregate);

    assertTrue(
        bridge().prefilledUserTaskDetails(referenceOf(aggregate, "no-such-task")).isEmpty());
    assertTrue(
        bridge()
            .prefilledWorkflowDetails(
                new WorkflowReference(
                    "c7", MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, TestWorkflowService.DEPLOYED_VERSION, String
                        .valueOf(aggregate.getId()), "no-such-workflow"))
            .isEmpty());
    assertTrue(
        bridge()
            .userTaskOfAggregate(
                MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, String.valueOf(aggregate.getId()),
                "no-such-task")
            .isEmpty());
    assertEquals(
        List.of(),
        bridge()
            .workflowsOfAggregate(MODULE_ID, TestWorkflowService.BPMN_PROCESS_ID, "no-such-case"));

  }

  @Test
  @DisplayName("The cockpit's task listeners are built-in and run behind VanillaBP's own")
  public void theCockpitListenersRunLastAndAreBuiltIn() {

    final var definitionId = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(TestWorkflowService.BPMN_PROCESS_ID)
        .latestVersion()
        .singleResult()
        .getId();
    final var deployed = (ProcessDefinitionEntity) engine
        .getRepositoryService()
        .getProcessDefinition(definitionId);
    final var behavior = (UserTaskActivityBehavior) deployed
        .findActivity(TestWorkflowService.BPMN_TASK_ID)
        .getActivityBehavior();
    final var taskDefinition = behavior.getTaskDefinition();

    // built-in and not custom: when an operator asks the engine to skip the custom listeners of
    // the model, the cockpit still gets the events it needs
    Stream
        .of(
            TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE,
            TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE)
        .forEach(eventName -> {
          final var listeners = taskDefinition
              .getBuiltinTaskListeners()
              .getOrDefault(eventName, List.of());
          assertFalse(
              listeners.isEmpty(), "no built-in listener for '%s'".formatted(eventName));
          assertInstanceOf(
              OperatonUserTaskListener.class,
              listeners.getLast(),
              "the cockpit's listener is not the last one for '%s': %s"
                  .formatted(eventName, listeners));
        });

    // and VanillaBP wired the task before the cockpit did. A details provider which reads an
    // aggregate that a completed task has just changed depends on this order
    final var created = taskDefinition
        .getBuiltinTaskListeners()
        .getOrDefault(TaskListener.EVENTNAME_CREATE, List.of());
    assertTrue(created.size() > 1, "VanillaBP's own CREATE listener is missing: %s".formatted(created));
    assertFalse(
        created.getFirst() instanceof OperatonUserTaskListener,
        "the cockpit's listener runs first: %s".formatted(created));

  }

  @Test
  @DisplayName("An operator skipping the custom listeners still gets the task and the workflow cancelled")
  public void skippingTheCustomListenersStillReportsTheCancellation() {

    final var aggregate = aStartedWorkflow("Olga");
    final var userTaskId = userTaskIdOf(aggregate);
    CockpitServer.awaitRequestOf("/usertask/created", reportsOf(aggregate));

    final var workflowId = workflowIdOf(aggregate);
    // this is what the Operaton web application does when an operator deletes a case. It skips
    // the listeners of the model, because they belong to the process. The cockpit is not part of
    // the process, so its own listeners are built-in and run anyway
    engine
        .getRuntimeService()
        .deleteProcessInstance(workflowId, "an operator deleted it", true);

    CockpitServer.awaitAnyRequest("/usertask/%s/cancelled".formatted(userTaskId));
    CockpitServer.awaitAnyRequest("/workflow/%s/cancelled".formatted(workflowId));

  }

  @Test
  @DisplayName("The workflow module registers itself at the cockpit server")
  public void theWorkflowModuleIsRegistered() {

    final var registration = CockpitServer.awaitRegistration();
    assertTrue(registration.path().endsWith("/workflow-module/c7-cockpit"), registration.path());
    assertTrue(registration.body().contains("approvers"), registration.body());
    assertFalse(registration.body().isBlank());

  }

}
