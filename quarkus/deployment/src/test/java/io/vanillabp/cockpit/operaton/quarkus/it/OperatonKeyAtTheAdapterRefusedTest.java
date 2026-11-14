package io.vanillabp.cockpit.operaton.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Business Cockpit adapter for Operaton reads no key of its own below an adapter id, on
 * Quarkus either. The cockpit's neutral half checks the keys while Quarkus builds the application,
 * so a key at <code>vanillabp.adapters.&lt;adapter-id&gt;.cockpit</code> ends the build with a
 * message which says that nobody reads it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OperatonKeyAtTheAdapterRefusedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication
      .forTestClass(OperatonKeyAtTheAdapterRefusedTest.class, "business-cockpit.yaml")
      // written into the build's configuration, because the check runs while Quarkus builds
      .overrideConfigKey("vanillabp.adapters.c7.cockpit.check-interval", "PT1M")
      .overrideRuntimeConfigKey("vanillabp.cockpit.rest.base-url", "http://localhost:1")
      .assertException(failure -> {
        final var messages = new StringBuilder();
        for (var cause = failure; cause != null; cause = cause.getCause()) {
          messages.append(cause.getMessage()).append('\n');
        }
        assertTrue(messages.toString().contains("vanillabp.adapters.c7.cockpit.check-interval"), messages.toString());
        assertTrue(
            messages.toString().contains("adapter type 'operaton' reads no key below an adapter id"), messages
                .toString());
      });

  @Test
  @DisplayName("A key at the adapter id ends the build, because this cockpit adapter reads none there")
  public void theApplicationDoesNotStart() {

    // the assertion is the one the extension above makes. This application never runs

  }

}
