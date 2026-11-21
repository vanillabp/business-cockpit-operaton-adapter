package io.vanillabp.cockpit.operaton.springboot;

import org.operaton.bpm.model.bpmn.BpmnModelInstance;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.operaton.OperatonCockpitCustomizer;
import io.vanillabp.cockpit.operaton.OperatonCockpitWiring;
import io.vanillabp.cockpit.operaton.OperatonWorkflowProcesses;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.settings.VanillaBpSettings;
import io.vanillabp.integration.spi.IdentifierScoping;
import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;
import io.vanillabp.operaton.OperatonProcessingContext;
import io.vanillabp.operaton.api.OperatonEngineFacts;
import io.vanillabp.operaton.engine.OperatonEngineCustomizer;

/**
 * Registers the Operaton half of the Business Cockpit extension on Spring Boot.
 * <p>
  * Nothing here decides anything. What the extension does with an engine is decided in the
  * platform-neutral module of this repository. This class does what only Spring can do: find the
  * beans and put the extension's own where VanillaBP and the Operaton adapter collect them.
 * <p>
 * It runs after VanillaBP's own auto-configuration, named rather than referenced, because an
 * extension does not compile against a platform integration.
 */
@AutoConfiguration(afterName = "io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration")
@ConditionalOnBean(VanillaBpSettings.class)
@Import(OperatonCockpitBeanRegistrar.class)
public class OperatonCockpitAutoConfiguration {

  /**
   * Spring builds the configuration to call the bean methods below. It keeps no
   * state of its own.
   */
  public OperatonCockpitAutoConfiguration() {

  }

  /**
   * Provides the one store of deployed processes of this application.
   *
   * @return Which BPMN processes of which workflow modules this application deployed, shared
   *         by the wiring service filling it and by everything translating an engine's
   *         identifiers back
   */
  @Bean
  public OperatonWorkflowProcesses businessCockpitOperatonWorkflowProcesses() {

    return new OperatonWorkflowProcesses();

  }

  /**
   * Provides this extension's wiring service.
   *
   * @param processes The claimed processes
   * @param workflowTaskClaims VanillaBP's registry, which answers whether a
   *          <code>&#64;WorkflowService</code> claims a process
   * @param publisher The extension, which is told the user tasks of every wired model. It is
   *          resolved when the first model is wired
   * @return This extension's place in VanillaBP's deployment pipeline, taken for a workflow
   *         module which runs on Operaton and for no other
   */
  @Bean
  public ExtensionWiringService<BpmnModelInstance, OperatonProcessingContext> businessCockpitOperatonWiringService(
      final OperatonWorkflowProcesses processes,
      final WorkflowTaskClaims workflowTaskClaims,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new OperatonCockpitWiring(processes, workflowTaskClaims, publisher::getObject);

  }

  /**
   * The listeners and the history handler of every Operaton engine this application builds.
   * <p>
    * The type is the concrete one rather than {@link OperatonEngineCustomizer}. The bridge of
    * each adapter id takes its scope from here, and the adapter collects customizers by their
    * interface either way.
   *
   * @param processes The deployed processes
   * @param scoping VanillaBP's name-clash avoidance
   * @param engines What the Operaton adapter knows about each of its engines, one bean per
   *          configured adapter id. They are collected when they are first needed rather than
   *          now: each of them is built from an engine, and an engine is built from this bean
   * @param publisher Where an observed event is reported. It is resolved on the first event
   *          rather than now: this bean is asked for while an engine is being built, and the
   *          extension it would return is built from those engines
   * @return The customizer
   */
  @Bean
  public OperatonCockpitCustomizer businessCockpitOperatonEngineCustomizer(
      final OperatonWorkflowProcesses processes,
      final IdentifierScoping scoping,
      final ObjectProvider<OperatonEngineFacts> engines,
      final ObjectProvider<BusinessCockpitEventPublisher> publisher) {

    return new OperatonCockpitCustomizer(
        processes, scoping, () -> engines.stream().toList(), publisher::getObject);

  }

}
