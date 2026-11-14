package io.vanillabp.cockpit.operaton;

import java.util.List;

import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEvent;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.history.handler.HistoryEventHandler;

import io.vanillabp.cockpit.extension.spi.WorkflowEventKind;

/**
 * How the Business Cockpit learns that a workflow started, ended or was cancelled, and that a
 * process instance was suspended or activated again.
 * <p>
  * The alternative would be an execution listener on every start and end event of every model.
  * That means rewriting models the modeller wrote, and it misses a workflow whose end nobody
  * modelled. History is the engine's own account of the same thing and needs no model change. It
  * even reports a cancellation, the case an end event never reaches, as an end whose state says
  * that the instance was terminated.
 * <p>
 * VanillaBP installs this handler NEXT TO the engine's own, so history is still written the
 * way it was. Every other kind of history event passes through untouched.
 * <p>
 * See decision 3 in the Camunda 7 extension's DECISIONS.md.
 */
public class OperatonWorkflowHistoryHandler implements HistoryEventHandler {

  private final OperatonCockpitEvents events;

  /**
   * Builds the handler which reports the workflow events of one engine.
   *
   * @param events Where the observed event is reported
   */
  public OperatonWorkflowHistoryHandler(
      final OperatonCockpitEvents events) {

    this.events = events;

  }

  @Override
  public void handleEvent(
      final HistoryEvent historyEvent) {

    if (!(historyEvent instanceof final HistoricProcessInstanceEventEntity processInstance)) {
      return;
    }
    // a suspension is reported as what it is and not as an update as well. An update would ask
    // the details provider about data nobody changed
    final var suspension = suspensionChangeOf(processInstance);
    if (suspension != null) {
      events.reportSuspension(processInstance, suspension);
      return;
    }
    events.reportWorkflow(processInstance, kindOf(processInstance));

  }

  @Override
  public void handleEvents(
      final List<HistoryEvent> historyEvents) {

    historyEvents.forEach(this::handleEvent);

  }

  /**
   * Whether an update of a process instance suspended it or activated it again.
   * <p>
   * Every update carries the state of the instance, also one which only gave it a business key or
   * moved it to another version of its process. So the state alone says nothing. The command
   * which suspends or activates an instance changes the state with a bulk SQL update. The engine
   * runs that update when the command ends, so up to then the instance held by the command still
   * has its old state. The command therefore writes the new state into the history event
   * explicitly. Any other update takes the state from the instance. A state which differs from
   * the instance's is a change of the suspension, and only such a change is reported.
   * <p>
   * The class of the running command would not do. A timer which suspends a process definition
   * runs the command inside the command of the job executor, and so does an asynchronous batch.
   * <p>
   * This also leaves out an instance whose state did not change. Suspending a process definition
   * with its instances updates every instance, also one which somebody suspended before. That
   * instance keeps the moment and the person of its own suspension.
   *
   * @return {@link WorkflowEventKind#SUSPENDED}, {@link WorkflowEventKind#ACTIVATED}, or
   *         <code>null</code> where the event is no change of the suspension
   */
  static WorkflowEventKind suspensionChangeOf(
      final HistoricProcessInstanceEventEntity processInstance) {

    if (!processInstance.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_UPDATE)) {
      return null;
    }
    final var command = Context.findCommandContext().orElse(null);
    if (command == null) {
      return null;
    }
    final var suspendedNow = HistoricProcessInstance.STATE_SUSPENDED.equals(processInstance.getState());
    if (!suspendedNow && !HistoricProcessInstance.STATE_ACTIVE.equals(processInstance.getState())) {
      return null;
    }
    final var instance = command.getExecutionManager().findExecutionById(processInstance.getProcessInstanceId());
    if ((instance == null) || (instance.isSuspended() == suspendedNow)) {
      return null;
    }
    return suspendedNow
        ? WorkflowEventKind.SUSPENDED
        : WorkflowEventKind.ACTIVATED;

  }

  /**
   * A process instance which ended without a token reaching an end event was torn down by the
   * engine, most often because somebody deleted it. The cockpit shows that as cancelled, also
   * where nobody gave a reason. See {@link OperatonDeletions}.
   */
  private static WorkflowEventKind kindOf(
      final HistoricProcessInstanceEventEntity processInstance) {

    if (processInstance.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_START)) {
      return WorkflowEventKind.CREATED;
    }
    if (processInstance.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_END)) {
      return OperatonDeletions.endedWithoutReachingAnEndEvent(processInstance)
          ? WorkflowEventKind.CANCELLED
          : WorkflowEventKind.COMPLETED;
    }
    return WorkflowEventKind.UPDATED;

  }

}
