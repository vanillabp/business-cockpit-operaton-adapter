package io.vanillabp.cockpit.operaton.quarkus;

import java.util.List;

import org.operaton.bpm.model.bpmn.BpmnModelInstance;

import io.quarkus.arc.Unremovable;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.operaton.OperatonCockpitBridge;
import io.vanillabp.cockpit.operaton.OperatonCockpitCustomizer;
import io.vanillabp.cockpit.operaton.OperatonCockpitWiring;
import io.vanillabp.cockpit.operaton.OperatonWorkflowProcesses;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.settings.VanillaBpSettings;
import io.vanillabp.integration.spi.IdentifierScoping;
import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;
import io.vanillabp.operaton.OperatonAdapter;
import io.vanillabp.operaton.OperatonProcessingContext;
import io.vanillabp.operaton.api.OperatonEngineFacts;
import io.vanillabp.operaton.quarkus.runtime.OperatonQuarkusEngineRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
  * Registers the Operaton half of the Business Cockpit extension on Quarkus. It is the twin of
  * the Spring Boot module's auto-configuration and does the same things with CDI.
 * <p>
 * The producers are <code>&#64;Singleton</code> rather than
 * <code>&#64;ApplicationScoped</code>: what they produce has no no-argument constructor and is
 * therefore not client-proxyable.
 */
@ApplicationScoped
public class OperatonCockpitProducer {

  /**
   * Quarkus builds the bean to call the producers below. It keeps no state of its
   * own.
   */
  public OperatonCockpitProducer() {

  }

  /**
   * Produces the one store of deployed processes of this application.
   *
   * @return Which BPMN processes of which workflow modules this application deployed, shared
   *         by the wiring service filling it and by everything translating an engine's
   *         identifiers back
   */
  @Produces
  @Singleton
  @Unremovable
  public OperatonWorkflowProcesses businessCockpitOperatonWorkflowProcesses() {

    return new OperatonWorkflowProcesses();

  }

  /**
   * Produces this extension's wiring service.
   *
   * @param processes The claimed processes
   * @param workflowTaskClaims VanillaBP's registry, which answers whether a
   *          <code>&#64;WorkflowService</code> claims a process
   * @param publisher The extension, which is told the user tasks of every wired model. It is
   *          resolved when the first model is wired
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on Operaton and for no other
   */
  @Produces
  @Singleton
  @Unremovable
  public ExtensionWiringService<BpmnModelInstance, OperatonProcessingContext> businessCockpitOperatonWiringService(
      final OperatonWorkflowProcesses processes,
      final WorkflowTaskClaims workflowTaskClaims,
      final Instance<BusinessCockpitEventPublisher> publisher) {

    return new OperatonCockpitWiring(processes, workflowTaskClaims, publisher::get);

  }

  /**
   * The listeners and the history handler of every Operaton engine this application builds.
   *
   * @param processes The deployed processes
   * @param scoping VanillaBP's name-clash avoidance
   * @param engines What the Operaton adapter knows about each of its engines, one entry per
   *          configured adapter id. They are asked for when they are first needed rather than
   *          now: each of them is built from an engine, and an engine is built from this bean
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is asked for while an engine is being built, and the
   *          extension it would return is built from those engines
   * @return The customizer
   */
  @Produces
  @Singleton
  @Unremovable
  public OperatonCockpitCustomizer businessCockpitOperatonEngineCustomizer(
      final OperatonWorkflowProcesses processes,
      final IdentifierScoping scoping,
      final Instance<List<OperatonEngineFacts>> engines,
      final Instance<BusinessCockpitEventPublisher> publisher) {

    return new OperatonCockpitCustomizer(processes, scoping, engines::get, publisher::get);

  }

  /**
   * One bridge per configured Operaton adapter id, because during a migration each engine
   * holds workflows of its own and the cockpit addresses a workflow by the engine holding it.
   * <p>
    * They are produced as one list rather than as one bean each. How many there are is decided by
    * the configuration, which a producer method cannot express. The cockpit's neutral half
    * collects both shapes, the same way VanillaBP's own Quarkus integration collects the adapter
    * deployment services of a BPMS adapter.
   * <p>
    * WHICH adapter ids those are is {@code VanillaBpSettings#adapterIdsOfType}, the same
    * answer the Spring Boot half reads through the platform's registrar support. Filtering the
    * configured types is not that answer. An id named in <code>prioritized-adapters</code> needs
    * no section of its own, and an application which configured nothing at all has the id the
    * classpath derives. A migration setup and a single-dependency application are therefore
    * exactly the two cases where an extension answering it itself registers no bridge while the
    * adapter registers fine.
   *
   * @param properties VanillaBP's resolved configuration, which names the configured adapters
   * @param engines The engines the Operaton adapter built
   * @param customizer The customizer, which owns the scope of each engine
   * @param processes The deployed processes
   * @return One bridge per configured Operaton adapter id
   */
  @Produces
  @Singleton
  @Unremovable
  public List<BusinessCockpitBpmsBridge> businessCockpitOperatonBridges(
      final VanillaBpSettings properties,
      final OperatonQuarkusEngineRegistry engines,
      final OperatonCockpitCustomizer customizer,
      final OperatonWorkflowProcesses processes) {

    return properties
        .adapterIdsOfType(OperatonAdapter.ADAPTER_TYPE)
        .stream()
        .<BusinessCockpitBpmsBridge>map(
            adapterId -> new OperatonCockpitBridge(
                customizer.scopeOf(adapterId), customizer.engineOf(adapterId), processes, customizer
                    .eventBeingReportedOf(adapterId), engines
                        .engineFor(adapterId)
                        .getProcessEngine()))
        .toList();

  }

}
