package io.vanillabp.cockpit.operaton;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.interceptor.CommandContext;
import org.operaton.bpm.engine.impl.interceptor.CommandContextListener;
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;
import org.operaton.bpm.engine.impl.util.ClockUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.cockpit.extension.spi.BusinessCockpitEventPublisher;
import io.vanillabp.cockpit.extension.spi.EventTransaction;
import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;
import io.vanillabp.cockpit.extension.spi.UserTaskReference;
import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;
import io.vanillabp.cockpit.extension.spi.WorkflowReference;

/**
 * What one configured Operaton engine reports to the Business Cockpit.
 * <p>
  * The engine has two hooks here, the task listeners of a user task and the handler of the
  * process-instance history events. Both end in this class, and both do the same: turn what the
  * engine says into the identifiers the cockpit addresses a task or a workflow by, and hand the
  * event over. The cockpit's neutral half then builds the whole report and writes one outbox
  * entry. Nothing is sent from here. The engine is in the middle of a transaction, and a listener
  * which talks to a server holds that transaction open for as long as the server takes.
 * <p>
  * The report is built out of the event rather than out of a later reading, so what the engine
  * handed over is put into {@link OperatonEventBeingReported} for as long as that takes.
  * {@link OperatonCockpitBridge} answers the cockpit's questions from there.
 * <p>
  * The version of the deployed process is one of those identifiers, and the cockpit picks the
  * details provider of a task or a workflow by it. The Operaton adapter has it cached, so
  * asking for it here costs the transaction nothing.
 * <p>
  * The entry is written in the transaction the engine's work happens in, so it becomes visible if
  * and only if that work was committed. An engine which commits on its own has no such
  * transaction to share. The entry then gets one of its own.
 */
public class OperatonCockpitEvents {

  private static final Logger logger = LoggerFactory.getLogger(OperatonCockpitEvents.class);

  private final OperatonScope scope;

  private final OperatonWorkflowProcesses processes;

  private final OperatonEventBeingReported eventBeingReported;

  private final Supplier<BusinessCockpitEventPublisher> publisher;

  private final Supplier<EventTransaction> transaction;

  /**
   * The starts of process instances which had no name yet, by the id of the instance, with the
   * moment each one started. An entry lives for as long as the engine command which started the
   * instance: see {@link #rememberTheStartOfAnUnnamedWorkflow}.
   */
  private final Map<String, Date> startsWaitingForTheirName = new ConcurrentHashMap<>();

  /**
   * Builds the events of one engine.
   *
   * @param scope The engine these events come from
   * @param processes The deployed processes, to translate the engine's identifiers back
   * @param eventBeingReported Where the event is put for as long as its report is built, shared
   *          with the bridge which answers out of it
   * @param publisher Where an event is handed to, asked for on the first event rather than up
   *          front: the engine is built while the application is still wiring itself together,
   *          and the extension is built from the engines
   * @param transaction Which transaction the outbox entry is written in, asked for on the first
   *          event for the same reason the publisher is: it is the engine's answer, and the
   *          engine is still being built while this is
   */
  public OperatonCockpitEvents(
      final OperatonScope scope,
      final OperatonWorkflowProcesses processes,
      final OperatonEventBeingReported eventBeingReported,
      final Supplier<BusinessCockpitEventPublisher> publisher,
      final Supplier<EventTransaction> transaction) {

    this.scope = scope;
    this.processes = processes;
    this.eventBeingReported = eventBeingReported;
    this.publisher = publisher;
    this.transaction = transaction;

  }

  /**
   * Tells which engine these events come from.
   *
   * @return The engine these events come from
   */
  public OperatonScope scope() {

    return scope;

  }

  /**
   * Tells in which transaction the outbox entry of an event is written.
   *
   * @return Which transaction the outbox entry of an event is written in
   */
  public EventTransaction transaction() {

    return transaction.get();

  }

  /**
   * Tells whether the cockpit is told about a process at all. That is the case for a process a
   * <code>&#64;WorkflowService</code> of this application claims, and for no other: not for one
   * the module deploys for somebody else, and not for one somebody deployed past VanillaBP. See
   * decision 14 in the Camunda 7 extension's DECISIONS.md.
   *
   * @param tenantId The tenant the engine stored, <code>null</code> where it stored none
   * @param processDefinitionKey The process definition key the engine stored
   * @return Whether events of that process are reported
   */
  public boolean reportsAbout(
      final String tenantId,
      final String processDefinitionKey) {

    return processes.resolve(scope, tenantId, processDefinitionKey).isPresent();

  }

  /**
   * Reports what happened to one user task.
   *
   * @param task The task the engine handed to the listener
   * @param bpmnTaskId The BPMN element id of the user task
   * @param taskDefinition The task's form key, or its element id where it has none
   * @param kind What happened
   */
  public void reportUserTask(
      final DelegateTask task,
      final String bpmnTaskId,
      final String taskDefinition,
      final UserTaskEventKind kind) {

    final var reference = referenceOf(task, bpmnTaskId, taskDefinition);
    if (reference.isEmpty()) {
      return;
    }
    eventBeingReported
        .whileReporting(
            task,
            () -> publisher
                .get()
                .publishUserTaskEvent(
                    reference.get(), kind, "%s#%s".formatted(task.getId(), task.getEventName()), OffsetDateTime
                        .now(),
                    transaction()));

  }

  /**
   * Reports that the process instance of one user task was suspended or activated again. The
   * report carries no business data, so neither the bridge nor a details provider is asked.
   *
   * @param task An open task of the process instance
   * @param bpmnTaskId The BPMN element id of the user task
   * @param taskDefinition The task's form key, or its element id where it has none
   * @param kind {@link UserTaskEventKind#SUSPENDED} or {@link UserTaskEventKind#ACTIVATED}
   * @param timestamp When the engine changed the state of the process instance
   * @param suspendedBy The user signed in to the engine, or <code>null</code> where nobody is
   */
  public void reportUserTaskSuspension(
      final DelegateTask task,
      final String bpmnTaskId,
      final String taskDefinition,
      final UserTaskEventKind kind,
      final OffsetDateTime timestamp,
      final String suspendedBy) {

    referenceOf(task, bpmnTaskId, taskDefinition)
        .ifPresent(reference -> publisher
            .get()
            .publishUserTaskSuspension(
                reference, kind, suspensionEventIdOf(task.getId(), kind, timestamp), timestamp, suspendedBy,
                transaction()));

  }

  /**
   * The identifiers the cockpit addresses one user task by.
   *
   * @return The reference, or nothing where the task is no task of the cockpit
   */
  private Optional<UserTaskReference> referenceOf(
      final DelegateTask task,
      final String bpmnTaskId,
      final String taskDefinition) {

    final var execution = (ExecutionEntity) task.getExecution();
    final var definition = execution.getProcessDefinition();
    final var process = processes
        .resolve(scope, definition.getTenantId(), definition.getKey());
    if (process.isEmpty()) {
      logger
          .debug(
              "Operaton[{}]: not reporting user task '{}': no @WorkflowService of this application claims its process definition '{}'",
              scope.adapterId(), task.getId(), definition.getKey());
      return Optional.empty();
    }

    final var workflowAggregateId = execution.getBusinessKey();
    if (workflowAggregateId == null) {
      logger
          .debug(
              "Operaton[{}]: not reporting user task '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), task.getId(), process.get().bpmnProcessId());
      return Optional.empty();
    }

    return Optional
        .of(
            new UserTaskReference(
                scope.adapterId(), process.get().workflowModuleId(), process.get()
                    .bpmnProcessId(), scope
                        .processVersionOf(
                            definition.getId()), workflowAggregateId, OperatonBusinessCases
                                .caseOf(scope, processes, execution), task.getId(), taskDefinition, bpmnTaskId));

  }

  /**
   * Reports what happened to one workflow.
   *
   * @param event The history event of the process instance
   * @param kind What happened
   */
  public void reportWorkflow(
      final HistoricProcessInstanceEventEntity event,
      final WorkflowEventKind kind) {

    final var process = processes
        .resolve(scope, event.getTenantId(), event.getProcessDefinitionKey());
    if (process.isEmpty()) {
      return;
    }
    // the cockpit shows business cases. A called process which shares its caller's workflow
    // aggregate is a step of the caller's case, and one with an aggregate of its own is a case
    // of its own. See decision 3 in the Camunda 7 extension's DECISIONS.md
    if (isAStepOfItsCaller(event)) {
      return;
    }
    final var workflowAggregateId = event.getBusinessKey();
    if (workflowAggregateId == null) {
      if (kind == WorkflowEventKind.CREATED) {
        rememberTheStartOfAnUnnamedWorkflow(event);
      }
      logger
          .debug(
              "Operaton[{}]: not reporting workflow '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), event.getProcessInstanceId(), process.get().bpmnProcessId());
      return;
    }

    // the update which gives a workflow its first name, after a start nobody could report, is
    // the start of the case for the cockpit, with the moment the instance started
    final var startWaitingForThisName = kind == WorkflowEventKind.UPDATED
        ? startsWaitingForTheirName.remove(event.getProcessInstanceId())
        : null;
    final var reportedKind = startWaitingForThisName == null
        ? kind
        : WorkflowEventKind.CREATED;
    final var timestamp = startWaitingForThisName == null
        ? timestampOf(event, kind)
        : atOffset(startWaitingForThisName);

    final var reference = new WorkflowReference(
        scope.adapterId(), process.get().workflowModuleId(), process.get()
            .bpmnProcessId(), scope
                .processVersionOf(
                    event.getProcessDefinitionId()), workflowAggregateId, event
                        .getProcessInstanceId());
    eventBeingReported
        .whileReporting(
            event,
            () -> publisher
                .get()
                .publishWorkflowEvent(
                    reference, reportedKind, event.getId(), timestamp, transaction()));

  }

  /**
   * Reports that a process instance was suspended or activated again. Every open user task of the
   * instance is reported, and so is the workflow where the instance is a case of its own.
   * <p>
   * The engine switches the tasks with one SQL update and calls no task listener, so the history
   * event of the instance is the only moment to report them. The tasks of a called process
   * instance are not reported: the engine does not suspend it together with its caller. A step of
   * its caller's case reports its tasks, but not a workflow, because the case itself is not
   * suspended.
   * <p>
   * Nothing but these reports changes the suspension in the cockpit, and nothing heals one that
   * was missed. So they are written into the engine's transaction like every other report: they
   * reach the cockpit if and only if the suspension was committed.
   * <p>
   * Must be called while the engine runs the command which changed the state.
   *
   * @param event The history event of the process instance, written by the command which
   *          suspended or activated it
   * @param kind {@link WorkflowEventKind#SUSPENDED} or {@link WorkflowEventKind#ACTIVATED}
   */
  public void reportSuspension(
      final HistoricProcessInstanceEventEntity event,
      final WorkflowEventKind kind) {

    final var process = processes
        .resolve(scope, event.getTenantId(), event.getProcessDefinitionKey());
    if (process.isEmpty()) {
      return;
    }
    final var workflowAggregateId = event.getBusinessKey();
    if (workflowAggregateId == null) {
      logger
          .debug(
              "Operaton[{}]: not reporting the suspension of workflow '{}' of BPMN process '{}': the process instance carries no business key, so there is no workflow aggregate to report it for",
              scope.adapterId(), event.getProcessInstanceId(), process.get().bpmnProcessId());
      return;
    }

    final var command = Context.findCommandContext().orElse(null);
    final var suspendedBy = whoSuspendedOrActivated(command);
    final var timestamp = atOffset(ClockUtil.getCurrentTime());

    final var userTaskKind = kind == WorkflowEventKind.SUSPENDED
        ? UserTaskEventKind.SUSPENDED
        : UserTaskEventKind.ACTIVATED;
    for (final var task : command.getTaskManager().findTasksByProcessInstanceId(event.getProcessInstanceId())) {
      cockpitListenerOf(task)
          .ifPresent(listener -> listener.reportSuspension(task, userTaskKind, timestamp, suspendedBy));
    }

    if (isAStepOfItsCaller(event)) {
      return;
    }
    final var reference = new WorkflowReference(
        scope.adapterId(), process.get().workflowModuleId(), process.get()
            .bpmnProcessId(), scope
                .processVersionOf(
                    event.getProcessDefinitionId()), workflowAggregateId, event
                        .getProcessInstanceId());
    publisher
        .get()
        .publishWorkflowSuspension(
            reference, kind, suspensionEventIdOf(event.getProcessInstanceId(), kind, timestamp), timestamp,
            suspendedBy, transaction());

  }

  /**
   * The person who suspended or activated a process instance. That is the user of the
   * <code>IdentityService</code> on the thread of the command. Nobody is signed in in the job
   * executor, nor in a REST call without authentication.
   * <p>
   * An asynchronous batch runs in the job executor, but the engine keeps the user who created the
   * batch. That user asked for the suspension, so the report names them. The job of the batch
   * belongs to a job definition whose configuration is the id of the batch. A timer which suspends
   * a process definition at a later moment keeps no user, so its report names nobody.
   *
   * @return The id of the user, or <code>null</code> where the engine does not know one
   */
  private static String whoSuspendedOrActivated(
      final CommandContext command) {

    final var signedIn = command.getAuthenticatedUserId();
    if (signedIn != null) {
      return signedIn;
    }
    final var job = command.getCurrentJob();
    if ((job == null) || (job.getJobDefinitionId() == null)) {
      return null;
    }
    final var jobDefinition = command.getJobDefinitionManager().findById(job.getJobDefinitionId());
    if ((jobDefinition == null) || !Batch.TYPE_PROCESS_INSTANCE_UPDATE_SUSPENSION_STATE
        .equals(jobDefinition.getJobType())) {
      return null;
    }
    final var batch = command.getBatchManager().findBatchById(jobDefinition.getJobConfiguration());
    return batch == null
        ? null
        : batch.getCreateUserId();

  }

  /**
   * The listener this extension put on the user task of a task. It knows the identifiers the
   * task is reported with. A task without it is no task of this extension, for example one of a
   * model which was parsed before the extension was there.
   */
  private Optional<OperatonUserTaskListener> cockpitListenerOf(
      final TaskEntity task) {

    final var definition = task.getTaskDefinition();
    final var listeners = definition == null
        ? null
        : definition.getBuiltinTaskListenersForEvent(TaskListener.EVENTNAME_CREATE);
    if (listeners == null) {
      return Optional.empty();
    }
    return listeners
        .stream()
        .filter(OperatonUserTaskListener.class::isInstance)
        .map(OperatonUserTaskListener.class::cast)
        .filter(listener -> listener.reportsTo(this))
        .findFirst();

  }

  /**
   * The engine has no id of its own for a suspension. The history event of the instance carries
   * the id of the instance, which every other event of the instance carries as well. So the id
   * names the task or the workflow, the kind and the moment.
   */
  private static String suspensionEventIdOf(
      final String id,
      final Enum<?> kind,
      final OffsetDateTime timestamp) {

    return "%s#%s#%d".formatted(id, kind.name().toLowerCase(Locale.ROOT), timestamp.toInstant().toEpochMilli());

  }

  /**
   * Whether the instance of a history event is a step of the case of the instance which called
   * it. The caller runs while the instance it called starts or ends, so the running command holds
   * it. Where it does not, the instance is taken as a step, which is what every called process
   * was before a called process could be a case of its own. See decision 3 in the Camunda 7 extension's
   * DECISIONS.md.
   */
  private boolean isAStepOfItsCaller(
      final HistoricProcessInstanceEventEntity event) {

    if (event.getSuperProcessInstanceId() == null) {
      return false;
    }
    final var caller = OperatonBusinessCases.callerInTheRunningCommand(event.getSuperProcessInstanceId());
    if (caller == null) {
      return true;
    }
    return !OperatonBusinessCases
        .isACase(
            scope, processes, new OperatonBusinessCases.Instance(
                event.getProcessInstanceId(), event.getTenantId(), event.getProcessDefinitionKey(), event
                    .getSuperProcessInstanceId()),
            caller);

  }

  /**
   * A workflow which the engine started by itself has no name at its start. A timer, a signal or
   * a message correlated past VanillaBP starts the process instance without a business key, and
   * the Operaton adapter writes the name into it a moment later, once the application has built
   * the workflow aggregate. For the engine that is a change of the process instance, so the next
   * event about it is an update. This remembers the start, and the update which brings the name
   * is reported as the start of the case.
   * <p>
   * The name arrives in the same engine command, while the listener of the start event runs. So
   * the entry is dropped when that command ends, whether a name arrived or not. A start which
   * never gets a name is no case of the cockpit, and a later update is a real update.
   *
   * @param event The history event of the start
   */
  private void rememberTheStartOfAnUnnamedWorkflow(
      final HistoricProcessInstanceEventEntity event) {

    final var commandContext = Context.findCommandContext().orElse(null);
    if (commandContext == null) {
      // there is no command whose end would drop the entry again
      return;
    }
    final var processInstanceId = event.getProcessInstanceId();
    startsWaitingForTheirName
        .put(
            processInstanceId, event.getStartTime() == null
                ? new Date()
                : event.getStartTime());
    commandContext.registerCommandContextListener(new CommandContextListener() {

      @Override
      public void onCommandContextClose(
          final CommandContext closed) {

        startsWaitingForTheirName.remove(processInstanceId);

      }

      @Override
      public void onCommandFailed(
          final CommandContext failed,
          final Throwable cause) {

        startsWaitingForTheirName.remove(processInstanceId);

      }

    });

  }

  private static OffsetDateTime timestampOf(
      final HistoricProcessInstanceEventEntity event,
      final WorkflowEventKind kind) {

    final var reported = kind == WorkflowEventKind.CREATED
        ? event.getStartTime()
        : event.getEndTime();
    return reported == null
        ? OffsetDateTime.now()
        : atOffset(reported);

  }

  private static OffsetDateTime atOffset(
      final Date date) {

    return date.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();

  }

}
