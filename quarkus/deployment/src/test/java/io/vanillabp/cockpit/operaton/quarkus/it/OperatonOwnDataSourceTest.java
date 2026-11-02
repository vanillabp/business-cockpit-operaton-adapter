package io.vanillabp.cockpit.operaton.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.test.support.CockpitServer;
import io.vanillabp.cockpit.operaton.OperatonCockpitCustomizer;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.operaton.quarkus.runtime.VanillaBpOperatonProperties;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Tests a Quarkus application whose engine writes into a database of its own, and what a
 * workflow which the engine rolled back leaves behind.
 * <p>
 * The engine writes to a resource which the application's own persistence does not take part in.
 * Two data sources in one JTA transaction are still two commits. So the outbox entry which reports
 * what the engine did cannot join the engine's commit, however the container enlists them. That
 * is why the entry gets a transaction of its own. This answer is honest, but not exact: the entry
 * and the engine's work commit separately, and the entry of a workflow the engine rolled back is
 * written and stays. The dispatch keeps such a workflow out of the cockpit. It asks the engine
 * about the workflow, the engine has none, and an event about a workflow nobody knows is dropped
 * and not sent.
 * <p>
 * The Operaton adapter answers whether an engine shares the caller's transaction, and reads it
 * from the engine it built. This test pins the answer for an engine with a data source of its own,
 * because that answer decides where the entry goes.
 * <p>
 * The engine's data source is an XA data source, because the Operaton adapter asks for it. Its
 * engine shares its transactions with the application's data source, and at most one of the two
 * may be without XA. Otherwise the boot stops. This extension asks for nothing on top. The entry
 * has a transaction of its own and enlists nothing of the engine. XA was the price of an older
 * answer, where the entry joined the engine's JTA transaction.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OperatonOwnDataSourceTest {

  private static final String ADAPTER_ID = "c7";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication
      .forTestClass(OperatonOwnDataSourceTest.class, "business-cockpit-own-data-source.yaml")
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", CockpitServer.baseUrl());

  @Inject
  TestWorkflowService workflowService;

  @Inject
  VanillaBpOperatonProperties operatonProperties;

  @Inject
  UserTransaction transaction;

  @Inject
  OperatonCockpitCustomizer customizer;

  /**
   * Nothing about the rolled-back workflow reaches the cockpit server. The entry which reports it
   * was written anyway, in a transaction of its own. The second assertion names that, and it is
   * why the first one checks the server and not the store.
   */
  @Test
  @DisplayName("A workflow rolled back on an engine with a data source of its own is not reported")
  public void aRolledBackWorkflowIsNotReported() throws Exception {

    assertTrue(
        operatonProperties.adapters().get(ADAPTER_ID).dataSourceName().isPresent(),
        "this engine runs on the application's data source, so the test proves nothing");

    CockpitServer.forgetRequests();

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Rita");
    final var started = workflowService.processes().startWorkflow(aggregate);
    transaction.rollback();

    assertNotNull(started.getId(), "the workflow aggregate never got an id to look for");
    final var businessId = "\"businessId\":\"%s\"".formatted(started.getId());

    CockpitServer.awaitQuiet();
    assertEquals(
        List.of(),
        CockpitServer
            .received()
            .stream()
            .filter(request -> request.body().contains(businessId))
            .map(CockpitServer.Request::path)
            .toList(),
        "the cockpit was told about a workflow the engine rolled back");

    assertEquals(
        EventTransaction.NEW,
        customizer.eventTransactionOf(ADAPTER_ID),
        """
            the engine of this adapter id was given a data source of its own, so its transaction \
            never reaches the store the outbox entry is written into - an entry sharing it would \
            be an entry whose commit says nothing about the engine's""");

  }

  @Test
  @DisplayName("A workflow which was committed is reported, engine data source or not")
  public void aCommittedWorkflowIsReported() throws Exception {

    transaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setCustomer("Sven");
    final var started = workflowService.processes().startWorkflow(aggregate);
    transaction.commit();

    assertNotNull(started.getId());
    awaitReportOf("/workflow/created", started);

  }

  /**
   * The report about one case. It is told apart from what the other Quarkus applications of this
   * module reported to the same server before they were shut down.
   */
  private static String awaitReportOf(
      final String pathSuffix,
      final TestAggregate aggregate) {

    final var businessId = "\"businessId\":\"%s\"".formatted(aggregate.getId());
    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      final var reported = CockpitServer
          .received()
          .stream()
          .filter(request -> request.path().endsWith(pathSuffix))
          .map(CockpitServer.Request::body)
          .filter(body -> body.contains(businessId))
          .findFirst();
      if (reported.isPresent()) {
        return reported.get();
      }
      try {
        Thread.sleep(200);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for the cockpit server", e);
      }
    }
    throw new AssertionError(
        "Nothing ending in '%s' was reported for aggregate %s. Received: %s"
            .formatted(
                pathSuffix, aggregate.getId(),
                CockpitServer.received().stream().map(CockpitServer.Request::path).toList()));

  }

}
