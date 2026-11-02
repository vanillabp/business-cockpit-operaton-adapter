package io.vanillabp.cockpit.operaton;

import java.time.OffsetDateTime;

import org.operaton.bpm.engine.delegate.DelegateTask;
import org.operaton.bpm.engine.delegate.TaskListener;

import io.vanillabp.cockpit.extension.spi.UserTaskEventKind;

/**
 * The listener the Business Cockpit attaches to one user task of one engine.
 * <p>
 * It knows its element from the moment the engine parsed it, so an event costs no lookup in the
 * model. What the cockpit shows about the task is built here, out of the task the engine handed
 * over, and it travels with the outbox entry. Nothing is sent from here, so the engine's
 * transaction is not held open for a server.
 */
public class OperatonUserTaskListener implements TaskListener {

  private final OperatonCockpitEvents events;

  private final String bpmnTaskId;

  private final String taskDefinition;

  /**
   * Builds the listener for one user task of one process.
   *
   * @param events Where the observed event is reported
   * @param bpmnTaskId The BPMN element id of the user task this listener sits on
   * @param taskDefinition The task's form key, or its element id where it has none
   */
  public OperatonUserTaskListener(
      final OperatonCockpitEvents events,
      final String bpmnTaskId,
      final String taskDefinition) {

    this.events = events;
    this.bpmnTaskId = bpmnTaskId;
    this.taskDefinition = taskDefinition;

  }

  @Override
  public void notify(
      final DelegateTask delegateTask) {

    final var kind = kindOf(delegateTask.getEventName());
    if (kind == null) {
      return;
    }
    events.reportUserTask(delegateTask, bpmnTaskId, taskDefinition, kind);

  }

  /**
   * Reports that the process instance of the task was suspended or activated again. The engine
   * calls no task listener for that, so the history handler of the instance calls this for each
   * open task. The listener adds what it knows about its element.
   *
   * @param task An open task of the process instance
   * @param kind {@link UserTaskEventKind#SUSPENDED} or {@link UserTaskEventKind#ACTIVATED}
   * @param timestamp When the engine changed the state of the process instance
   * @param suspendedBy The user signed in to the engine, or <code>null</code> where nobody is
   */
  public void reportSuspension(
      final DelegateTask task,
      final UserTaskEventKind kind,
      final OffsetDateTime timestamp,
      final String suspendedBy) {

    events.reportUserTaskSuspension(task, bpmnTaskId, taskDefinition, kind, timestamp, suspendedBy);

  }

  /**
   * Tells whether this listener reports to the events of one engine. Each engine parses its
   * models with listeners of its own.
   *
   * @param engineEvents The events of the engine asking
   * @return Whether this listener reports there
   */
  boolean reportsTo(
      final OperatonCockpitEvents engineEvents) {

    return events == engineEvents;

  }

  /**
   * @return What the cockpit calls this engine event, or <code>null</code> for an event the
   *         cockpit has no kind for - this listener is registered for four of them, and a
   *         fifth arriving from a later engine is not something to fail a workflow over
   */
  private static UserTaskEventKind kindOf(
      final String eventName) {

    return switch (eventName) {
      case TaskListener.EVENTNAME_CREATE -> UserTaskEventKind.CREATED;
      case TaskListener.EVENTNAME_UPDATE -> UserTaskEventKind.UPDATED;
      case TaskListener.EVENTNAME_COMPLETE -> UserTaskEventKind.COMPLETED;
      case TaskListener.EVENTNAME_DELETE -> UserTaskEventKind.CANCELED;
      default -> null;
    };

  }

}
