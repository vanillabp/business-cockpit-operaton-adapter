package io.vanillabp.cockpit.operaton.springboot.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.task.Task;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Tests on a real embedded Operaton engine that a suspension and an activation reach the cockpit,
 * with the person who did it.
 * <p>
 * The engine switches the tasks of a suspended process instance with one SQL update and calls no
 * task listener. Only the history event of the instance tells. An engine double would not show
 * which of the engine's many updates of an instance is a suspension, so these tests use the
 * engine.
 * <p>
 * Nothing but these reports changes the suspension in the cockpit. So a report the adapter misses
 * is never healed by a later one, and each way to suspend is tested on its own: an instance, a
 * process definition with and without its instances, a definition at a later moment, and a batch.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine which outlives its test keeps its job executor
// running against a database the next class works on
@DirtiesContext
public class OperatonSuspensionTest {

  private static final String MODULE_ID = "c7-cockpit";

  private static final String THE_ONE_WHO_SUSPENDS = "ulla";

  private static final String THE_ONE_WHO_ACTIVATES = "viktor";

  @DynamicPropertySource
  static void cockpitServer(
      final DynamicPropertyRegistry registry) {

    registry.add("vanillabp.cockpit.rest.base-url", CockpitServer::baseUrl);

  }

  @Autowired
  private TestAggregateRepository aggregates;

  @Autowired
  private TransactionTemplate transactions;

  @Autowired
  private ProcessEngine engine;

  @BeforeEach
  public void forgetWhatArrivedBefore() {

    CockpitServer.forgetRequests();

  }

  /**
   * A test which suspends the process definition leaves it suspended when it fails. The next test
   * could not start an instance then.
   */
  @AfterEach
  public void activateTheDefinitionAgain() {

    engine.getIdentityService().clearAuthentication();
    engine
        .getRepositoryService()
        .updateProcessDefinitionSuspensionState()
        .byProcessDefinitionId(theDefinition().getId())
        .activate();

  }

  /**
   * One running case of the multi-instance process, with two open user tasks.
   *
   * @param id The id of the process instance
   * @param userTaskIds The ids of its open user tasks
   */
  private record Instance(
                          String id,
                          List<String> userTaskIds) {
  }

  /**
   * Starts the multi-instance process, which has one open user task per signer. A suspension is
   * reported for every open task, so two of them show that it is not only the first one.
   *
   * @return The instance, after the cockpit learned about it and its tasks
   */
  private Instance aRunningInstance() {

    final var aggregate = transactions
        .execute(status -> {
          final var fresh = new TestAggregate();
          fresh.setCustomer("Suspendable");
          fresh.setSigners(List.of("anna", "bert"));
          return aggregates.save(fresh);
        });
    // started through the engine and not through VanillaBP: the multi-instance process is a
    // secondary process of this aggregate
    final var instance = engine
        .getRuntimeService()
        .createProcessInstanceByKey(TestWorkflowService.MULTI_INSTANCE_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        .execute();
    final var userTaskIds = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(instance.getId())
        .list()
        .stream()
        .map(Task::getId)
        .toList();
    assertEquals(2, userTaskIds.size(), userTaskIds.toString());
    // a report of a creation names the task and the workflow in its body, not in its path
    userTaskIds.forEach(
        userTaskId -> CockpitServer.awaitRequestOf("/usertask/created", "\"userTaskId\":\"%s\"".formatted(userTaskId)));
    CockpitServer.awaitRequestOf("/workflow/created", "\"workflowId\":\"%s\"".formatted(instance.getId()));
    return new Instance(instance.getId(), userTaskIds);

  }

  private ProcessDefinition theDefinition() {

    return engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(TestWorkflowService.MULTI_INSTANCE_PROCESS_ID)
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult();

  }

  /**
   * Runs one call of the engine with a user signed in, as the REST API of the engine and an
   * application which uses the <code>IdentityService</code> do.
   */
  private void as(
      final String userId,
      final Consumer<ProcessEngine> call) {

    // the user belongs to the tenant the workflow module was deployed under. Without it, the
    // engine's tenant check refuses the call
    engine.getIdentityService().setAuthentication(userId, null, List.of(MODULE_ID));
    try {
      call.accept(engine);
    } finally {
      engine.getIdentityService().clearAuthentication();
    }

  }

  /**
   * Waits for the report of a suspension or an activation of one instance: one for each of its
   * tasks and one for its workflow.
   *
   * @param instance The instance
   * @param change <code>suspended</code> or <code>activated</code>, the end of the report's path
   * @param initiator The person the reports have to name, or <code>null</code> for nobody
   */
  private static void awaitReportsOf(
      final Instance instance,
      final String change,
      final String initiator) {

    final var reports = new ArrayList<CockpitServer.Request>();
    instance
        .userTaskIds()
        .forEach(
            userTaskId -> reports.add(CockpitServer.awaitAnyRequest("/usertask/%s/%s".formatted(userTaskId, change))));
    reports.add(CockpitServer.awaitAnyRequest("/workflow/%s/%s".formatted(instance.id(), change)));
    reports
        .forEach(report -> {
          if (initiator == null) {
            assertFalse(report.body().contains("\"initiator\":\""), report.body());
          } else {
            assertTrue(report.body().contains("\"initiator\":\"%s\"".formatted(initiator)), report.body());
          }
        });

  }

  /**
   * The reports about one instance which arrived on paths ending in <code>change</code>.
   */
  private static List<CockpitServer.Request> reportsAbout(
      final Instance instance,
      final String change) {

    final var paths = new ArrayList<String>();
    instance.userTaskIds().forEach(userTaskId -> paths.add("/usertask/%s/%s".formatted(userTaskId, change)));
    paths.add("/workflow/%s/%s".formatted(instance.id(), change));
    return CockpitServer
        .received()
        .stream()
        .filter(request -> paths.stream().anyMatch(path -> request.path().endsWith(path)))
        .toList();

  }

  @Test
  @DisplayName("Suspending and activating an instance reports every open task and the workflow, with the person who did it")
  public void anInstanceIsSuspendedAndActivated() {

    final var instance = aRunningInstance();

    as(THE_ONE_WHO_SUSPENDS, engine -> engine.getRuntimeService().suspendProcessInstanceById(instance.id()));
    awaitReportsOf(instance, "suspended", THE_ONE_WHO_SUSPENDS);

    as(THE_ONE_WHO_ACTIVATES, engine -> engine.getRuntimeService().activateProcessInstanceById(instance.id()));
    awaitReportsOf(instance, "activated", THE_ONE_WHO_ACTIVATES);

    // the history event of a suspension is an update of the instance as well. Reported as one, it
    // would ask the details provider about data nobody changed
    CockpitServer.awaitQuiet();
    assertEquals(List.of(), reportsAbout(instance, "updated"));
    // one report per task and one for the workflow, each of them once
    assertEquals(instance.userTaskIds().size() + 1, reportsAbout(instance, "suspended").size());

  }

  @Test
  @DisplayName("A suspension without a signed-in user is reported without a person")
  public void nobodySignedInIsNoPerson() {

    final var instance = aRunningInstance();

    engine.getRuntimeService().suspendProcessInstanceById(instance.id());
    awaitReportsOf(instance, "suspended", null);

    engine.getRuntimeService().activateProcessInstanceById(instance.id());
    awaitReportsOf(instance, "activated", null);

  }

  @Test
  @DisplayName("Suspending a process definition without its instances reports nothing, because the instances keep running")
  public void aDefinitionWithoutItsInstances() {

    final var instance = aRunningInstance();

    as(
        THE_ONE_WHO_SUSPENDS, engine -> engine
            .getRepositoryService()
            .updateProcessDefinitionSuspensionState()
            .byProcessDefinitionId(theDefinition().getId())
            .includeProcessInstances(false)
            .suspend());

    CockpitServer.awaitQuiet();
    assertEquals(List.of(), reportsAbout(instance, "suspended"));
    assertFalse(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(instance.id()).singleResult()
        .isSuspended());

  }

  @Test
  @DisplayName("Suspending a process definition with its instances reports each of them, with the person who did it")
  public void aDefinitionWithItsInstances() {

    final var instance = aRunningInstance();

    as(
        THE_ONE_WHO_SUSPENDS, engine -> engine
            .getRepositoryService()
            .updateProcessDefinitionSuspensionState()
            .byProcessDefinitionId(theDefinition().getId())
            .includeProcessInstances(true)
            .suspend());
    awaitReportsOf(instance, "suspended", THE_ONE_WHO_SUSPENDS);

    as(
        THE_ONE_WHO_ACTIVATES, engine -> engine
            .getRepositoryService()
            .updateProcessDefinitionSuspensionState()
            .byProcessDefinitionId(theDefinition().getId())
            .includeProcessInstances(true)
            .activate());
    awaitReportsOf(instance, "activated", THE_ONE_WHO_ACTIVATES);

  }

  @Test
  @DisplayName("An instance suspended before its definition is not reported again, so it keeps the person who suspended it")
  public void anInstanceWhichWasSuspendedAlready() {

    final var suspendedBefore = aRunningInstance();
    final var running = aRunningInstance();
    as(THE_ONE_WHO_SUSPENDS, engine -> engine.getRuntimeService().suspendProcessInstanceById(suspendedBefore.id()));
    awaitReportsOf(suspendedBefore, "suspended", THE_ONE_WHO_SUSPENDS);
    CockpitServer.forgetRequests();

    // the engine updates every instance of the definition, also the one which is suspended already
    as(
        THE_ONE_WHO_ACTIVATES, engine -> engine
            .getRepositoryService()
            .updateProcessDefinitionSuspensionState()
            .byProcessDefinitionId(theDefinition().getId())
            .includeProcessInstances(true)
            .suspend());
    awaitReportsOf(running, "suspended", THE_ONE_WHO_ACTIVATES);

    CockpitServer.awaitQuiet();
    assertEquals(List.of(), reportsAbout(suspendedBefore, "suspended"));

  }

  @Test
  @DisplayName("A process definition suspended at a later moment is reported when the timer runs, without a person")
  public void aDefinitionSuspendedLater() {

    final var instance = aRunningInstance();
    // in an hour, so the job executor leaves the timer alone and the test runs it
    as(
        THE_ONE_WHO_SUSPENDS, engine -> engine
            .getRepositoryService()
            .updateProcessDefinitionSuspensionState()
            .byProcessDefinitionId(theDefinition().getId())
            .includeProcessInstances(true)
            .executionDate(new Date(System.currentTimeMillis() + Duration.ofHours(1).toMillis()))
            .suspend());
    CockpitServer.awaitQuiet();
    assertEquals(List.of(), reportsAbout(instance, "suspended"));

    final var timer = engine
        .getManagementService()
        .createJobQuery()
        .timers()
        // the engine writes no process definition id onto this timer. No model of this test has a
        // timer which is due that late
        .duedateHigherThan(new Date(System.currentTimeMillis() + Duration.ofMinutes(30).toMillis()))
        .singleResult();
    // the timer runs the suspension inside the command of the job, which is why the adapter does
    // not look at the command to tell a suspension from another update
    engine.getManagementService().executeJob(timer.getId());

    awaitReportsOf(instance, "suspended", null);

  }

  @Test
  @DisplayName("A batch suspends in the job executor, and every instance is reported with the person who created the batch")
  public void aBatchSuspension() {

    final var first = aRunningInstance();
    final var second = aRunningInstance();

    final var batch = new AtomicReference<Batch>();
    as(
        THE_ONE_WHO_SUSPENDS, engine -> batch
            .set(
                engine
                    .getRuntimeService()
                    .updateProcessInstanceSuspensionState()
                    .byProcessInstanceIds(first.id(), second.id())
                    .suspendAsync()));
    // nobody is signed in while the jobs run, as in the job executor
    runTheJobsOf(batch.get().getSeedJobDefinitionId());
    runTheJobsOf(batch.get().getBatchJobDefinitionId());

    awaitReportsOf(first, "suspended", THE_ONE_WHO_SUSPENDS);
    awaitReportsOf(second, "suspended", THE_ONE_WHO_SUSPENDS);

  }

  @Test
  @DisplayName("A synchronous suspension of several instances reports each of them, with the person who did it")
  public void aSynchronousSuspensionOfSeveralInstances() {

    final var first = aRunningInstance();
    final var second = aRunningInstance();

    as(
        THE_ONE_WHO_SUSPENDS, engine -> engine
            .getRuntimeService()
            .updateProcessInstanceSuspensionState()
            .byProcessInstanceIds(first.id(), second.id())
            .suspend());

    awaitReportsOf(first, "suspended", THE_ONE_WHO_SUSPENDS);
    awaitReportsOf(second, "suspended", THE_ONE_WHO_SUSPENDS);

  }

  /**
   * Runs the jobs of one job definition, next to the job executor. Whichever of the two takes a
   * job first runs it. The other one finds it gone, or half gone, which is no failure here.
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
              // The job executor ran it first. Depending on how far it got, the engine throws a
              // ProcessEngineException or a NullPointerException for the configuration it
              // already deleted. Only a job which is still there failed for real.
              if (engine.getManagementService().createJobQuery().jobId(job.getId()).count() > 0) {
                throw e;
              }
            }
          });
    }
    throw new AssertionError("The jobs of the job definition %s did not run".formatted(jobDefinitionId));

  }

}
