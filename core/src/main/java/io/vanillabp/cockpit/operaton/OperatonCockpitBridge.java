package io.vanillabp.cockpit.operaton;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.operaton.bpm.engine.ProcessEngine;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.repository.ProcessDefinition;
import org.operaton.bpm.engine.task.IdentityLink;
import org.operaton.bpm.engine.task.IdentityLinkType;
import org.operaton.bpm.engine.task.Task;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitBpmsBridge;
import io.vanillabp.cockpit.extension.spi.UserTaskDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowDetailsPrefill;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.extension.spi.handler.HandlerMultiInstance;
import io.vanillabp.operaton.OperatonAdapter;
import io.vanillabp.operaton.api.OperatonEngineFacts;
import io.vanillabp.operaton.api.OperatonMultiInstances;
import io.vanillabp.operaton.api.OperatonTaskDefinitions;

/**
 * What one configured Operaton engine can be asked about a task or a workflow.
 * <p>
 * <b>Two ways in, and which one runs is decided by the caller.</b>
 * <p>
  * A report is built at the moment of the event, inside the engine command which fired it. The
  * two <code>prefilled…</code> methods then answer out of what the engine handed to the listener
  * or to the history handler, which {@link OperatonEventBeingReported} holds while that report is
  * built. Nothing is queried there, because the command has not been flushed yet. So the history
  * of this very event is not written, and a query for it answers nothing at all. The event itself
  * says everything a report needs.
 * <p>
  * The other way in is a question of <code>BusinessCockpitService</code>, and no event is
  * anywhere near it. The application reads a task it names, or it says that its aggregate
  * changed. The engine is then queried for the state of now, which is what such a question asks
  * for. The three <code>…OfAggregate</code> methods are always that way in, and the two
  * <code>prefilled…</code> methods take it whenever this thread reports no event about the task
  * or the workflow they were asked about.
 * <p>
  * A task the engine has finished with is answered by neither. The report of its end was built
  * while the task was still there, and a question about a running task is about a task which is
  * running. So nothing is read from the historic task instance any more, and nothing from the
  * identity-link log.
 */
public class OperatonCockpitBridge implements BusinessCockpitBpmsBridge {

  private final OperatonScope scope;

  private final OperatonEngineFacts engineFacts;

  private final OperatonWorkflowProcesses processes;

  private final OperatonEventBeingReported eventBeingReported;

  private final ProcessEngine engine;

  /**
   * Builds the bridge for one engine. Each configured adapter id gets a bridge of its own.
   *
   * @param scope The engine this bridge serves
   * @param engineFacts What the Operaton adapter knows about that engine
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param eventBeingReported What the engine handed over for the event being reported, shared
   *          with {@link OperatonCockpitEvents} which puts it there
   * @param engine The engine
   */
  public OperatonCockpitBridge(
      final OperatonScope scope,
      final OperatonEngineFacts engineFacts,
      final OperatonWorkflowProcesses processes,
      final OperatonEventBeingReported eventBeingReported,
      final ProcessEngine engine) {

    this.scope = scope;
    this.engineFacts = engineFacts;
    this.processes = processes;
    this.eventBeingReported = eventBeingReported;
    this.engine = engine;

  }

  @Override
  public String adapterId() {

    return scope.adapterId();

  }

  @Override
  public String adapterType() {

    return OperatonAdapter.ADAPTER_TYPE;

  }

  /**
   * What the cockpit shows about a user task: the values of the event being reported, or the
   * values of now where the application asks about the task.
   */
  @Override
  public Optional<UserTaskDetailsPrefill> prefilledUserTaskDetails(
      final UserTaskReference userTask) {

    final var beingReported = eventBeingReported.userTask(userTask.userTaskId());
    if (beingReported.isPresent()) {
      return Optional.of(prefillOfTheEvent(beingReported.get(), userTask.bpmnProcessId()));
    }

    return inOneEngineCommand(
        () -> Optional
            .ofNullable(
                engine
                    .getTaskService()
                    .createTaskQuery()
                    .taskId(userTask.userTaskId())
                    .singleResult())
            .map(running -> prefillOfTheRunningTask(running, userTask.bpmnProcessId())));

  }

  /**
   * What the cockpit shows about a workflow: the values of the event being reported, or the
   * values of now where the application says that its aggregate changed.
   */
  @Override
  public Optional<WorkflowDetailsPrefill> prefilledWorkflowDetails(
      final WorkflowReference workflow) {

    final var beingReported = eventBeingReported.workflow(workflow.workflowId());
    if (beingReported.isPresent()) {
      return Optional.of(prefillOfTheEvent(beingReported.get(), workflow.bpmnProcessId()));
    }

    return inOneEngineCommand(() -> {
      final var instance = historicWorkflow(workflow.workflowId());
      if (instance == null) {
        return Optional.empty();
      }
      final var definition = definitionOf(instance.getProcessDefinitionId());
      return Optional
          .of(
              new WorkflowDetailsPrefill(
                  versionOf(instance.getProcessDefinitionId()), instance.getBusinessKey(), processNameOf(
                      definition, workflow.bpmnProcessId()), instance.getStartUserId()));
    });

  }

  @Override
  public List<WorkflowReference> workflowsOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    var query = engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(workflowAggregateId)
        .processDefinitionKey(scope.scopedProcessIdOf(workflowModuleId, bpmnProcessId));
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();

    final var instances = query;
    return inOneEngineCommand(() -> instances
        .list()
        .stream()
        // the cockpit shows business cases. A called process which shares its caller's
        // aggregate is a step of the caller's case, and one with an aggregate of its own is a
        // case. See decision 3 in the Camunda 7 extension's DECISIONS.md
        .filter(
            instance -> OperatonBusinessCases
                .isACase(
                    scope, processes, OperatonBusinessCases.instanceOf(instance), OperatonBusinessCases
                        .callerOf(engine.getHistoryService(), instance.getSuperProcessInstanceId())))
        .map(
            instance -> new WorkflowReference(
                scope.adapterId(), workflowModuleId, bpmnProcessId, scope
                    .processVersionOf(
                        instance.getProcessDefinitionId()), workflowAggregateId, instance.getId()))
        .toList());

  }

  @Override
  public List<UserTaskReference> userTasksOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final List<String> userTaskIds) {

    var query = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(workflowAggregateId);
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    if ((userTaskIds != null) && !userTaskIds.isEmpty()) {
      query = query.taskIdIn(userTaskIds.toArray(String[]::new));
    }
    final var tasksOfTheAggregate = query;

    return inOneEngineCommand(
        () -> tasksOfTheAggregate
            .list()
            .stream()
            .map(task -> referenceOf(task, workflowModuleId, bpmnProcessId, workflowAggregateId))
            .flatMap(Optional::stream)
            .toList());

  }

  @Override
  public Optional<UserTaskReference> userTaskOfAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String userTaskId) {

    // the business key is part of the query rather than checked afterwards, so a task of
    // another case cannot be read by guessing its id
    var query = engine
        .getTaskService()
        .createTaskQuery()
        .taskId(userTaskId)
        .processInstanceBusinessKey(workflowAggregateId);
    final var tenantId = scope.tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    final var theTask = query;

    return inOneEngineCommand(
        () -> Optional
            .ofNullable(theTask.singleResult())
            .flatMap(task -> referenceOf(task, workflowModuleId, bpmnProcessId, workflowAggregateId)));

  }

  /**
    * A running task as the cockpit addresses it. Its BPMN process has to be one the asking
    * workflow module deployed. Under <code>none</code> and under <code>use-prefix</code> no
    * tenant separates the modules of an application. A business key which two of them use would
    * otherwise answer one module's question with another module's task.
   * <p>
   * Its process also has to work on the aggregate which was asked about. Two aggregate classes
   * may count their ids alike, so a business key does not say which aggregate it belongs to. A
   * task of a called process with an aggregate of its own belongs to that case and not to the
   * caller's (decision 3 in the Camunda 7 extension's DECISIONS.md).
   *
   * @param task The task the engine answered with
   * @param workflowModuleId The workflow module which was asked about
   * @param bpmnProcessId The process of the aggregate which was asked about
   * @param workflowAggregateId The aggregate which was asked about, which is the business key
   *          every one of these queries filtered on
   */
  private Optional<UserTaskReference> referenceOf(
      final Task task,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    if (definition == null) {
      return Optional.empty();
    }
    return processes
        .resolve(scope, definition.getTenantId(), definition.getKey())
        .filter(process -> process.workflowModuleId().equals(workflowModuleId))
        .filter(
            process -> process.bpmnProcessId().equals(bpmnProcessId) || processes
                .shareTheWorkflowAggregate(
                    new OperatonWorkflowProcesses.WorkflowProcess(workflowModuleId, bpmnProcessId), process))
        .map(
            process -> new UserTaskReference(
                scope.adapterId(), process.workflowModuleId(), process
                    .bpmnProcessId(), scope
                        .processVersionOf(
                            task.getProcessDefinitionId()), workflowAggregateId, caseOf(task
                                .getProcessInstanceId()), task
                                    .getId(), taskDefinitionOf(task), task.getTaskDefinitionKey()));

  }

  /**
   * A user task as the engine handed it to the listener.
   * <p>
    * Every field comes off the task, its execution and the process definition that execution
    * runs on. None of it is a query, so this can be read inside the engine command. The objects
    * are the ones the command is working with, and they carry what was just done to the task. A
    * task which is being completed still answers its variables here. Reading it back afterwards
    * could never do that.
   * <p>
    * One field of a prefill stays empty here and in the read below: the initiator. Operaton
    * records who started a case in the history of the process instance. A user task could only
    * get it with a second query, and at the moment of an event no query reaches it at all.
    * Nothing else is put there instead. See decision 10 in the Camunda 7 extension's DECISIONS.md.
   * <p>
    * The task also says when it was created. The cockpit reads that only from the report of an
    * end, for an end which arrives before the creation did.
   *
   * @param task The task of the listener
   * @param bpmnProcessId The BPMN process as the application wrote it, the fallback for a model
   *          without a name
   */
  private UserTaskDetailsPrefill prefillOfTheEvent(
      final DelegateTask task,
      final String bpmnProcessId) {

    final var execution = (ExecutionEntity) task.getExecution();
    final var caseId = OperatonBusinessCases.caseOf(scope, processes, execution);
    final var candidates = candidatesOf(task.getCandidates());

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(execution.getProcessDefinitionId()))
        .bpmnProcessName(processNameOf(execution.getProcessDefinition(), bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(caseId)
        .subWorkflowId(
            execution.getProcessInstanceId().equals(caseId)
                ? null
                : execution.getProcessInstanceId())
        .businessId(execution.getBusinessKey())
        .assignee(task.getAssignee())
        .candidateUsers(candidates.users())
        .candidateGroups(candidates.groups())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .createdAt(atOffset(task.getCreateTime()))
        .variables(task.getVariables())
        .multiInstances(
            multiInstancesOf(
                OperatonMultiInstances.of(task.getExecution(), engineFacts.taskRegistry())))
        .comment(commentOfTheEvent(task))
        .build();

  }

  /**
   * A workflow as the engine handed its history event to the handler.
   * <p>
    * The event carries the business key, the process definition and its name, and a start event
    * carries who started the case. An end event does not repeat who started it, so the report of
    * an end leaves that empty and the cockpit keeps what the creation told it.
    * See decision 10 in the Camunda 7 extension's DECISIONS.md.
   * <p>
    * The cockpit needs the start of the case where the end arrives before the creation did. The
    * engine writes the start time only into the event of the start, and the event of an end has
    * it only where the case ended in the command that started it. Any other end would need a
    * read of the history, so its report leaves the start empty.
   *
   * @param event The history event of the process instance
   * @param bpmnProcessId The BPMN process as the application wrote it, the fallback for a model
   *          without a name
   */
  private WorkflowDetailsPrefill prefillOfTheEvent(
      final HistoricProcessInstanceEventEntity event,
      final String bpmnProcessId) {

    final var name = event.getProcessDefinitionName();
    return new WorkflowDetailsPrefill(
        versionOf(event.getProcessDefinitionId()), event.getBusinessKey(), (name == null) || name.isBlank()
            ? bpmnProcessId
            : name, event.getStartUserId(), atOffset(event.getStartTime()), null, null, commentOfTheEvent(event));

  }

  /**
   * The reason somebody gave while deleting the process instance of a task. Only the deletion of
   * a task carries one. See {@link OperatonDeletions} for when it counts.
   */
  private static String commentOfTheEvent(
      final DelegateTask task) {

    if (!TaskListener.EVENTNAME_DELETE.equals(task.getEventName())) {
      return null;
    }
    return OperatonDeletions.commentOfADeletedTask(task.getDeleteReason());

  }

  /**
   * The reason somebody gave while deleting a process instance. See {@link OperatonDeletions}
   * for when it counts.
   */
  private static String commentOfTheEvent(
      final HistoricProcessInstanceEventEntity event) {

    return OperatonDeletions.commentOf(event);

  }

  /**
   * A user task the engine holds right now, which is what the application asks about.
   * <p>
    * The business case the task belongs to is read off the process instance rather than off the
    * task, although the task records it too. The instance is in hand here anyway, and taking it
    * from one place means one rule about what a case is.
   */
  private UserTaskDetailsPrefill prefillOfTheRunningTask(
      final Task task,
      final String bpmnProcessId) {

    final var definition = definitionOf(task.getProcessDefinitionId());
    final var workflow = historicWorkflow(task.getProcessInstanceId());
    final var candidates = candidatesOf(engine.getTaskService().getIdentityLinksForTask(task.getId()));

    return UserTaskDetailsPrefill
        .builder()
        .bpmnProcessVersion(versionOf(task.getProcessDefinitionId()))
        .bpmnProcessName(processNameOf(definition, bpmnProcessId))
        .bpmnTaskName(task.getName())
        .workflowId(caseOf(workflow))
        .subWorkflowId(subWorkflowIdOf(workflow))
        .businessId(workflow == null ? null : workflow.getBusinessKey())
        .assignee(task.getAssignee())
        .candidateUsers(candidates.users())
        .candidateGroups(candidates.groups())
        .dueDate(atOffset(task.getDueDate()))
        .followUpDate(atOffset(task.getFollowUpDate()))
        .variables(variablesOf(task.getId()))
        .multiInstances(
            multiInstancesOf(
                OperatonMultiInstances
                    .of(engine, task.getExecutionId(), engineFacts.taskRegistry())))
        .build();

  }

  /**
   * Everything a prefill reads, in one engine command.
   * <p>
    * A task, its process instance, its identity links, its variables and its execution tree are
    * five questions. Each of them opens a command of its own when it is asked through the
    * engine's services. The engine reuses a command context which is already open, so wrapping
    * them makes the five share one session and one transaction instead of taking one apiece.
   *
   * @param reads What is to be read while that command is open
   * @return Whatever those reads produced
   */
  private <T> T inOneEngineCommand(
      final Supplier<T> reads) {

    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired()
        .execute(commandContext -> reads.get());

  }

  /**
    * Every variable the task can see, which is what a <code>&#64;TaskParam</code> parameter of a
    * details provider is bound from. A variable somebody set to <code>null</code> is handed over
    * as it is. The cockpit's neutral half binds the parameter to what the engine says, and a
    * variable dropped here would look exactly like one nobody ever set.
   */
  private Map<String, Object> variablesOf(
      final String taskId) {

    return engine.getTaskService().getVariables(taskId);

  }

  /** The users and the groups an identity link of a task may name. */
  private record Candidates(
                            List<String> users,
                            List<String> groups) {
  }

  /**
   * Who may work on a task, out of the identity links it carries.
   * <p>
    * Both ways in hand over the same links. A listener has them on the task the engine gave it,
    * and a task the engine was queried for is asked for them. Only the links which say candidate
    * are read: an assignee and an owner are links as well, and the cockpit shows them as what
    * they are.
   *
   * @param links The identity links of the task
   */
  private static Candidates candidatesOf(
      final Collection<? extends IdentityLink> links) {

    final var users = new LinkedList<String>();
    final var groups = new LinkedList<String>();
    links
        .stream()
        .filter(link -> IdentityLinkType.CANDIDATE.equals(link.getType()))
        .forEach(link -> {
          if (link.getGroupId() != null) {
            groups.add(link.getGroupId());
          } else if (link.getUserId() != null) {
            users.add(link.getUserId());
          }
        });
    return new Candidates(List.copyOf(users), List.copyOf(groups));

  }

  private HistoricProcessInstance historicWorkflow(
      final String processInstanceId) {

    return processInstanceId == null
        ? null
        : engine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .singleResult();

  }

  /** Named only where the task really sits in a called process. */
  private String subWorkflowIdOf(
      final HistoricProcessInstance workflow) {

    if (workflow == null) {
      return null;
    }
    return workflow.getId().equals(caseOf(workflow))
        ? null
        : workflow.getId();

  }

  /**
   * The business case of an instance, read from the history. See decision 3 in the
   * Camunda 7 extension's DECISIONS.md.
   */
  private String caseOf(
      final HistoricProcessInstance workflow) {

    return OperatonBusinessCases.caseOf(scope, processes, engine.getHistoryService(), workflow);

  }

  private String caseOf(
      final String processInstanceId) {

    final var workflow = historicWorkflow(processInstanceId);
    return workflow == null
        ? processInstanceId
        : caseOf(workflow);

  }

  private ProcessDefinition definitionOf(
      final String processDefinitionId) {

    return processDefinitionId == null
        ? null
        : engine.getRepositoryService().getProcessDefinition(processDefinitionId);

  }

  /**
   * How Operaton counts the deployed process a workflow runs on, as an operator reads it.
   * <p>
    * One deployment yields two strings here, and they go to two places. This one is for a person
    * to read: with a version tag it reads <code>release-7:4</code>, and it goes into the
    * prefilled details, which is what the cockpit shows next to a task or a case. The other one
    * is the version the engine counted, <code>4</code>, and it goes into the reference. That is
    * what picks the details provider of that version, so it must carry no tag. See
    * {@link OperatonScope#processVersionOf(String)}.
   * <p>
    * The adapter resolved that definition when it first met it, and it answers every later
    * question about it from its own cache. A cockpit asking once per task and once per rendered
    * page therefore pays the engine for none of them. Turning the version and its tag into one
    * string is the platform's rule rather than this repository's, which is what makes one
    * deployment read the same however the cockpit heard about it.
   * <p>
    * Nothing is reported while the adapter has no deployment service for this adapter id yet, and
    * nothing for a definition the engine no longer holds. Every field of a prefill is optional,
    * so a version nobody can name is left out rather than guessed at.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version as an operator reads it, or <code>null</code>
   */
  private String versionOf(
      final String processDefinitionId) {

    final var deployed = engineFacts.definitionOf(processDefinitionId);
    return deployed == null
        ? null
        : deployed.displayVersion();

  }

  /**
   * The name the modeller wrote, which is what the cockpit shows where neither a template nor
   * a details provider produced a title. A model without a name falls back to its id.
   */
  private static String processNameOf(
      final ProcessDefinition definition,
      final String fallback) {

    if (definition == null) {
      return fallback;
    }
    final var name = definition.getName();
    return (name == null) || name.isBlank()
        ? fallback
        : name;

  }

  /**
   * What the cockpit calls a running task: the form key the modeller wrote, and the element id
   * where the model carries none.
   * <p>
    * The form key is read off the deployed process definition rather than off the task. A task
    * answers the form key its engine COMPUTED, and a form key which is an expression computes
    * another string per workflow instance. One task would then reach the cockpit under as many
    * identities as it has instances, and none of them would be the identity its listener reported
    * while the model was parsed. Reading the definition is also why no query here asks the engine
    * to evaluate form keys any more.
   *
   * @param task The task the engine answered with
   * @return What a details provider is matched by and what the cockpit shows a form for
   */
  private String taskDefinitionOf(
      final Task task) {

    return OperatonTaskDefinitions
        .of(
            OperatonTaskDefinitions
                .formKeyOf(engine, task.getProcessDefinitionId(), task.getTaskDefinitionKey()),
            task.getTaskDefinitionKey());

  }

  /**
   * The multi-instance scopes a user task runs in, as a details provider's
   * <code>&#64;MultiInstanceElement</code>, <code>&#64;MultiInstanceIndex</code> and
   * <code>&#64;MultiInstanceTotal</code> parameters are bound from.
   * <p>
    * The walk itself belongs to the Operaton adapter. It reads the execution tree, which is the
    * engine knowledge an Operaton upgrade is most likely to invalidate, and the adapter does it for
    * its own task deliveries anyway.
   * <p>
    * Both calls of it hand over the task registry of this engine. The walk needs it for a call
    * activity which names the process it calls in an expression. Nothing was written onto such a
    * call activity while the model was deployed. So the walk asks while the workflow runs whether
    * the called process continues the caller's business case. Without the registry the walk ends
    * there. Then the cockpit names no round at all for a task of such a called process, and a
    * details provider asking for one is refused while the task is created. Both ways into this
    * class hand the registry over, because a task read from the engine has to say what the report
    * of its creation said. Measured by
    * {@code OperatonCockpitTest#theLevelOfACallerReachesTheCockpit} and
    * {@code OperatonCockpitTest#theLevelOfACallerIsAnsweredWhenTheTaskIsRead}.
   * <p>
    * What is left here is a copy from one record into another. The adapter answers what a BPMS
    * reports about a task, and the platform's handler layer takes what a call into application
    * code runs in. They are separate contracts, although they carry the same three values.
    * Copying them is done in this one place, and the map keeps the order the adapter promises,
    * outermost first.
   *
   * @param scopes What the adapter answered
   * @return The scopes, keyed by BPMN element id
   */
  private static Map<String, HandlerMultiInstance> multiInstancesOf(
      final Map<String, MultiInstanceValue> scopes) {

    final var outermostFirst = new LinkedHashMap<String, HandlerMultiInstance>();
    scopes
        .forEach(
            (
                elementId,
                scope) -> outermostFirst
                    .put(
                        elementId,
                        new HandlerMultiInstance(scope.element(), scope.index(), scope.total())));
    return outermostFirst;

  }

  private static OffsetDateTime atOffset(
      final Date date) {

    return date == null
        ? null
        : date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();

  }

}
