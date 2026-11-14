package io.vanillabp.cockpit.operaton.springboot.test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Business Cockpit adapter for Operaton reads no key of its own below an adapter id. Its
 * engine, its data source and its engine name all come from the Operaton adapter. So a key at
 * <code>vanillabp.adapters.&lt;adapter-id&gt;.cockpit</code> is read by nobody, and the cockpit's
 * neutral half ends the start with a message which says so. Spring Boot itself would bind around
 * the key without a word.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OperatonKeyAtTheAdapterRefusedTest {

  private static String failureOfBooting(
      final String databaseName,
      final String key) {

    final var failure = assertThrows(
        Exception.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--%s=%s".formatted(ADatabaseOfItsOwn.DATABASE_NAME_KEY, databaseName),
                "--vanillabp.cockpit.rest.base-url=http://localhost:1", "--%s".formatted(key))
            .close());
    final var messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages.append(cause.getMessage()).append('\n');
    }
    return messages.toString();

  }

  @Test
  @DisplayName("A key at the adapter id ends the start, because this cockpit adapter reads none there")
  public void aKeyAtTheAdapterEndsTheStart() {

    final var message = failureOfBooting(
        "c7-cockpit-key-at-the-adapter", "vanillabp.adapters.c7.cockpit.check-interval=PT1M");

    assertTrue(message.contains("vanillabp.adapters.c7.cockpit.check-interval"), message);
    assertTrue(message.contains("adapter type 'operaton' reads no key below an adapter id"), message);

  }

  @Test
  @DisplayName("A neutral key at the adapter id ends the start and names the place where it is read")
  public void aNeutralKeyAtTheAdapterNamesItsPlace() {

    final var message = failureOfBooting(
        "c7-cockpit-neutral-key-at-the-adapter",
        "vanillabp.workflow-modules.c7-cockpit.adapters.c7.cockpit.template-path=approvals");

    assertTrue(message.contains("vanillabp.workflow-modules.c7-cockpit.adapters.c7.cockpit.template-path"), message);
    assertTrue(message.contains("'vanillabp.workflow-modules.c7-cockpit.cockpit.template-path'"), message);

  }

}
