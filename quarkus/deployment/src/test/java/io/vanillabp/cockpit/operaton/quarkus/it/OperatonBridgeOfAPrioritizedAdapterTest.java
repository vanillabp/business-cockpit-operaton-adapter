package io.vanillabp.cockpit.operaton.quarkus.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Tests an adapter id which has no configuration section of its own. It is named in
 * <code>prioritized-adapters</code> and nowhere else.
 * <p>
 * This is common: in a migration, the order of the engines is often all there is to say about
 * the second one. This repository used to miss such an id. Filtering the configured adapter TYPES
 * finds nothing for it. So the adapter built its engine, the cockpit built no bridge, and no event
 * of that engine reached the cockpit. That is why VanillaBP answers which ids belong to an
 * adapter type ({@code VanillaBpSettings#adapterIdsOfType}), and not this repository.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OperatonBridgeOfAPrioritizedAdapterTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = TestApplication
      .forTestClass(OperatonBridgeOfAPrioritizedAdapterTest.class, "business-cockpit-prioritized-only.yaml")
      .overrideRuntimeConfigKey(
          "vanillabp.cockpit.rest.base-url", "http://localhost:1");

  @Inject
  List<BusinessCockpitBpmsBridge> bridges;

  @Test
  @DisplayName("An adapter id which only stands in prioritized-adapters has a bridge")
  public void theBridgeOfAPrioritizedAdapterExists() {

    assertEquals(1, bridges.size(), bridges.toString());
    assertEquals("operaton", bridges.getFirst().adapterId());

  }

}
