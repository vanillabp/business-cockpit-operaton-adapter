package io.vanillabp.cockpit.operaton;

import org.operaton.bpm.engine.batch.Batch;
import org.operaton.bpm.engine.history.HistoricProcessInstance;
import org.operaton.bpm.engine.impl.cmd.AbstractDeleteProcessInstanceCmd;
import org.operaton.bpm.engine.impl.context.Context;
import org.operaton.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.operaton.bpm.engine.impl.history.event.HistoryEventTypes;
import org.operaton.bpm.engine.impl.persistence.entity.TaskEntity;

/**
 * Whether a workflow or a user task ends because somebody deleted the process instance.
 * <p>
 * Two different questions are answered here.
 * <p>
 * <b>Did the process instance end without reaching an end event?</b> The engine writes that into
 * the state of the history event of the end. A run which reached an end event is
 * <code>COMPLETED</code>. A run the engine tore down is <code>EXTERNALLY_TERMINATED</code> or
 * <code>INTERNALLY_TERMINATED</code>, depending on a flag the caller may pass. The Java API passes
 * <code>false</code> by default, so both mean the same here. Such a workflow is reported as
 * cancelled, also where nobody gave a reason. A terminate end event, an interrupting event
 * subprocess and a boundary event all end on an end event of the model, so they stay completed.
 * <p>
 * <b>Was it a deletion?</b> Only then is the delete reason something a person said, and only then
 * is it handed to the cockpit as the comment. The engine writes texts of its own into the same
 * field where a boundary event, an event subprocess, a migration or a modification takes a task
 * away. The state of the process instance cannot tell a deletion apart while its user tasks are
 * deleted: the engine deletes the tasks of an instance first and marks the instance as deleted
 * only afterwards. So the question is answered by what the engine is doing right now: running a
 * command which deletes process instances, or running a job of a batch which deletes them. The
 * Operaton web application and the REST API delete through the same command.
 */
public final class OperatonDeletions {

  private OperatonDeletions() {
  }

  /**
   * Whether a process instance ended without reaching an end event of its model.
   *
   * @param event The history event of the process instance
   * @return <code>true</code> for the end of a run the engine tore down
   */
  public static boolean endedWithoutReachingAnEndEvent(
      final HistoricProcessInstanceEventEntity event) {

    if (!event.isEventOfType(HistoryEventTypes.PROCESS_INSTANCE_END)) {
      return false;
    }
    return HistoricProcessInstance.STATE_EXTERNALLY_TERMINATED
        .equals(event.getState()) || HistoricProcessInstance.STATE_INTERNALLY_TERMINATED.equals(event.getState());

  }

  /**
   * The comment a deleted workflow gets: the delete reason, where the end is a deletion.
   *
   * @param event The history event of the process instance
   * @return The reason the person gave, or <code>null</code>
   */
  public static String commentOf(
      final HistoricProcessInstanceEventEntity event) {

    if (!endedWithoutReachingAnEndEvent(event) || !theEngineIsDeletingProcessInstances()) {
      return null;
    }
    return event.getDeleteReason();

  }

  /**
   * The comment a deleted user task gets: the delete reason, where the task goes because its
   * process instance is deleted.
   * <p>
   * The engine gives such a task the word <code>deleted</code> where the person gave no reason.
   * That word is the engine's stand-in for "none", so it is no comment.
   *
   * @param deleteReason The delete reason of the task
   * @return The reason the person gave, or <code>null</code>
   */
  public static String commentOfADeletedTask(
      final String deleteReason) {

    if ((deleteReason == null) || TaskEntity.DELETE_REASON_DELETED
        .equals(deleteReason) || !theEngineIsDeletingProcessInstances()) {
      return null;
    }
    return deleteReason;

  }

  /**
   * Whether the engine is deleting process instances right now: in a command of
   * <code>RuntimeService#deleteProcessInstance</code> or <code>#deleteProcessInstances</code>,
   * or in a job of a batch of <code>#deleteProcessInstancesAsync</code>. The job runs the command
   * inside its own, so the job is what is asked for there.
   */
  private static boolean theEngineIsDeletingProcessInstances() {

    final var invocation = Context.getCommandInvocationContext();
    if ((invocation != null) && (invocation.getCommand() instanceof AbstractDeleteProcessInstanceCmd)) {
      return true;
    }
    final var command = Context.findCommandContext().orElse(null);
    final var job = command == null
        ? null
        : command.getCurrentJob();
    return (job != null) && Batch.TYPE_PROCESS_INSTANCE_DELETION.equals(job.getJobHandlerType());

  }

}
