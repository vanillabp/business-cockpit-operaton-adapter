package io.vanillabp.cockpit.operaton;

import org.operaton.bpm.engine.delegate.TaskListener;
import org.operaton.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.operaton.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.operaton.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.operaton.bpm.engine.impl.pvm.process.ActivityImpl;
import org.operaton.bpm.engine.impl.pvm.process.ScopeImpl;
import org.operaton.bpm.engine.impl.util.xml.Element;

import io.vanillabp.operaton.api.OperatonTaskDefinitions;

/**
 * Attaches the Business Cockpit's task listeners while the engine parses a user task of a
 * process the application claims.
 * <p>
  * The listeners are added as <b>built-in</b> ones, which keeps them out of reach of
  * <code>skipCustomListeners</code>. An operator who reassigns or deletes a task from the Operaton
  * web application asks the engine to skip the custom listeners of the model, and the cockpit
  * still has to learn that the task changed. A task list showing an assignee nobody can correct
  * is worse than no task list. It is also why all four events are taken rather than only the two
  * VanillaBP itself needs.
 * <p>
  * The listener carries the element id and the form key it was parsed with, so a runtime event
  * needs neither a model lookup nor a registry of its own. What the cockpit calls the task is the
  * Operaton adapter's own rule: the form key the modeller wrote, and the element id where the
  * model carries none. A task without a form is still a task somebody has to see.
 * <p>
  * A user task of any other process gets nothing. The engine parses every model it runs, also one
  * the workflow module deploys for somebody else and one somebody deployed past VanillaBP. The
  * cockpit reports nothing about those, so a listener there would only be one more thing in a
  * model which is not this application's. See decision 14 in the Camunda 7 extension's DECISIONS.md.
 * <p>
 * Why built-in and why after VanillaBP's own is decision 1 in the Camunda 7 extension's DECISIONS.md, and
 * that every user task gets them whether or not the application enriches it is decision 2 in the
 * Camunda 7 extension's DECISIONS.md.
 */
public class OperatonUserTaskParseListener implements BpmnParseListener {

  /**
   * The events the cockpit is attached to. The order is the order the engine fires them in,
   * and it is the order they are added in so that a reader of a parsed definition sees the
   * same sequence.
   */
  static final String[] EVENT_NAMES = {
      TaskListener.EVENTNAME_CREATE, TaskListener.EVENTNAME_UPDATE, TaskListener.EVENTNAME_COMPLETE, TaskListener.EVENTNAME_DELETE
  };

  private final OperatonCockpitEvents events;

  /**
   * Builds the parse listener which puts the task listeners on every user task of a claimed
   * process.
   *
   * @param events Where the listeners report what they observed
   */
  public OperatonUserTaskParseListener(
      final OperatonCockpitEvents events) {

    this.events = events;

  }

  @Override
  public void parseUserTask(
      final Element userTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (!(activity.getActivityBehavior() instanceof final UserTaskActivityBehavior behavior)) {
      return;
    }
    // the engine sets key and tenant while it parses the process element, which comes before
    // any of the elements inside it
    if (!(activity.getProcessDefinition() instanceof final ProcessDefinitionEntity definition) || !events
        .reportsAbout(definition.getTenantId(), definition.getKey())) {
      return;
    }
    final var taskDefinition = behavior.getTaskDefinition();
    final var listener = new OperatonUserTaskListener(
        events, activity.getId(), OperatonTaskDefinitions
            .of(OperatonTaskDefinitions.formKeyOf(taskDefinition), activity.getId()));
    for (final var eventName : EVENT_NAMES) {
      taskDefinition.addBuiltInTaskListener(eventName, listener);
    }

  }

}
