package io.vanillabp.cockpit.operaton;

import java.util.function.Supplier;

import io.vanillabp.integration.spi.IdentifierScoping;
import io.vanillabp.operaton.api.OperatonEngineFacts;

/**
 * How one configured Operaton engine names the things the extension asks it about.
 * <p>
  * Name-clash avoidance decides whether a workflow module's processes are deployed under a
  * tenant, under a prefixed process id or under neither. Every query the extension sends to an
  * engine has to spell the id the way that engine stores it. The rules are the adapter's, so they
  * are asked of the adapter rather than rebuilt here. A prefix which the extension assembles
  * itself drifts apart from the adapter's on the next change.
 * <p>
  * The tenant is the adapter's own answer, not a property read a second time. It may be
  * configured for a workflow module and for the adapter. Which of the two a module runs under is
  * what the adapter resolves when it deploys that module. Asking anywhere else means querying a
  * tenant the engine never stored.
 * <p>
  * The version of a deployed process is asked here as well. It is not a name, but it is the
  * same kind of answer: the adapter knows it, both halves of this extension have to spell it
  * alike, and working it out a second time would let them drift apart.
 * <p>
  * That answer is asked for when it is needed rather than when this scope is built. An engine is
  * built with what this extension contributes to it, so there is nothing to ask about the engine
  * while it is being built. By the time anything is reported or read back, the engine exists.
 */
public final class OperatonScope {

  private final String adapterId;

  private final IdentifierScoping scoping;

  private final Supplier<OperatonEngineFacts> engine;

  /**
   * Builds the scope of one engine.
   *
   * @param adapterId The configured adapter id whose engine this scope belongs to
   * @param scoping VanillaBP's name-clash avoidance
   * @param engine What the Operaton adapter knows about that engine
   */
  public OperatonScope(
      final String adapterId,
      final IdentifierScoping scoping,
      final Supplier<OperatonEngineFacts> engine) {

    this.adapterId = adapterId;
    this.scoping = scoping;
    this.engine = engine;

  }

  /**
   * Tells which adapter id this scope belongs to.
   *
   * @return The configured adapter id
   */
  public String adapterId() {

    return adapterId;

  }

  /**
   * Tells which Operaton tenant a workflow module uses on this engine.
   *
   * @param workflowModuleId The workflow module
   * @return The Operaton tenant this module's processes live in, or <code>null</code> where the
   *         configured mode uses none
   */
  public String tenantIdOf(
      final String workflowModuleId) {

    return engine.get().tenantIdOf(workflowModuleId);

  }

  /**
   * Which deployed version of its BPMN process a task or a workflow belongs to, as this engine
   * counted it: a number counted upwards per process id, and nothing else.
   * <p>
    * It is what a reference of the cockpit carries, and the cockpit picks the details provider
    * of a task or a workflow by it. So it is the plain version and never a version dressed up
    * for a person to read. How an operator reads the same deployment is
    * <code>DeployedProcessVersion.displayVersion()</code>, which adds the version tag, and
    * <code>OperatonCockpitBridge</code> reports that one in the prefilled details.
   * <p>
    * The answer comes from the Operaton adapter's cache. The adapter resolves a definition once
    * and answers every later question about it for free, so a cockpit asking per task and per
    * rendered page pays the engine for none of them. It is asked here for the same reason the
    * tenant is: an event the listeners report and a read the bridge answers have to name one
    * deployment the same way.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version the engine counted, or <code>null</code> where nobody can name it: the
   *         engine does not hold that definition any more, or the adapter has no deployment
   *         service for this adapter id yet
   */
  public String processVersionOf(
      final String processDefinitionId) {

    final var deployed = engine.get().definitionOf(processDefinitionId);
    return deployed == null
        ? null
        : deployed.version();

  }

  /**
   * Turns a BPMN process id into the key this engine stores the process under.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The BPMN process id as the application wrote it
   * @return The process definition key the engine knows, which differs from the plain id only
   *         under <code>use-prefix</code>
   */
  public String scopedProcessIdOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return IdentifierScoping
        .scopedProcessId(scoping, workflowModuleId, bpmnProcessId, adapterId);

  }

}
