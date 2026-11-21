package io.vanillabp.cockpit.operaton;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

import io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims;

/**
 * Which BPMN processes of which workflow modules this application deployed and claims, and how
 * to get from an engine's own identifiers back to them.
 * <p>
  * An engine reports a task or a history event with the process definition key and the tenant it
  * stored. Both of those depend on the name-clash avoidance the module was deployed with. The
  * translation back is therefore not a string operation but a lookup. Every process a
  * <code>&#64;WorkflowService</code> claims is remembered here while the deployment pipeline runs.
  * An event of a process which is not in here belongs to something else: a process the module
  * deploys for somebody else, another application on the same database, or a process definition
  * of an earlier release which is no longer part of this one.
 * <p>
 * The reverse map is built per adapter id and thrown away whenever a process is added, which
 * happens a handful of times while the application starts and never afterwards.
 * <p>
 * Why a lookup rather than a prefix somebody cuts off a string is decision 4 in the Camunda 7 extension's
 * DECISIONS.md.
 */
public class OperatonWorkflowProcesses {

  /**
   * Starts out empty. The wiring service fills it while the workflow modules are
   * deployed.
   */
  public OperatonWorkflowProcesses() {

  }

  /**
   * The core which wired the processes, which answers whether two of them share a workflow
   * aggregate. Set by the wiring service, which is the one bean holding both.
   */
  private volatile WorkflowTaskClaims core;

  /**
   * Remembers the core which answers whether two processes share a workflow aggregate.
   *
   * @param core VanillaBP's registry of what the application declared
   */
  public void rememberTheCore(
      final WorkflowTaskClaims core) {

    this.core = core;

  }

  /**
   * Whether a called process works on the workflow aggregate of the process which called it.
   * The core answers it (<code>WorkflowTaskClaims#workflowsShareTheWorkflowAggregate</code>), the
   * same answer the Operaton adapter uses for its call activities. Nothing is decided here. See
   * decision 3 in the Camunda 7 extension's DECISIONS.md.
   *
   * @param calling The calling process
   * @param called The called process
   * @return Whether both share the aggregate. <code>false</code> where the two belong to
   *         different workflow modules, and where no core was remembered
   */
  public boolean shareTheWorkflowAggregate(
      final WorkflowProcess calling,
      final WorkflowProcess called) {

    final var theCore = core;
    return (theCore != null) && calling.workflowModuleId().equals(called.workflowModuleId()) && theCore
        .workflowsShareTheWorkflowAggregate(calling.workflowModuleId(), calling.bpmnProcessId(), called
            .bpmnProcessId());

  }

  /**
   * One BPMN process of one workflow module, named the way the application wrote it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   */
  public record WorkflowProcess(
                                String workflowModuleId,
                                String bpmnProcessId) {
  }

  /**
    * Joins the two halves of an engine identity. A process definition key is a BPMN process id
    * and carries no blank. The key is therefore always what stands behind the last blank, and no
    * pair of halves can be read as another pair.
   */
  private static final String SEPARATOR = " ";

  private final Set<WorkflowProcess> processes = new CopyOnWriteArraySet<>();

  private final Map<String, Map<String, WorkflowProcess>> byEngineIdentity = new ConcurrentHashMap<>();

  /**
   * Remembers a BPMN process VanillaBP is deploying and a <code>&#64;WorkflowService</code>
   * claims. Called while the deployment pipeline runs and therefore before the engine parses that
   * module's files.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   */
  public void register(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (processes.add(new WorkflowProcess(workflowModuleId, bpmnProcessId))) {
      byEngineIdentity.clear();
    }

  }

  /**
   * The workflow module and BPMN process an engine's identifiers belong to.
   *
   * @param scope The engine which reported them
   * @param tenantId The tenant the engine stored, <code>null</code> where it stored none
   * @param processDefinitionKey The process definition key the engine stored
   * @return What the application calls it, or empty where no deployed process of this
   *         application matches
   */
  public Optional<WorkflowProcess> resolve(
      final OperatonScope scope,
      final String tenantId,
      final String processDefinitionKey) {

    if (processDefinitionKey == null) {
      return Optional.empty();
    }
    return Optional
        .ofNullable(
            byEngineIdentity
                .computeIfAbsent(scope.adapterId(), adapterId -> engineIdentities(scope))
                .get(identity(tenantId, processDefinitionKey)));

  }

  private Map<String, WorkflowProcess> engineIdentities(
      final OperatonScope scope) {

    final var identities = new HashMap<String, WorkflowProcess>();
    processes
        .forEach(
            process -> identities
                .putIfAbsent(
                    identity(
                        scope.tenantIdOf(process.workflowModuleId()),
                        scope.scopedProcessIdOf(
                            process.workflowModuleId(), process.bpmnProcessId())),
                    process));
    return Map.copyOf(identities);

  }

  private static String identity(
      final String tenantId,
      final String processDefinitionKey) {

    return (tenantId == null ? "" : tenantId) + SEPARATOR + processDefinitionKey;

  }

}
