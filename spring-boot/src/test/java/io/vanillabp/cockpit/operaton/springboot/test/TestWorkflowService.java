package io.vanillabp.cockpit.operaton.springboot.test;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.cockpit.BusinessCockpitService;
import io.vanillabp.spi.cockpit.details.DetailsEvent;
import io.vanillabp.spi.cockpit.usertask.PrefilledUserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetails;
import io.vanillabp.spi.cockpit.usertask.UserTaskDetailsProvider;
import io.vanillabp.spi.cockpit.workflow.PrefilledWorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetails;
import io.vanillabp.spi.cockpit.workflow.WorkflowDetailsProvider;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of the application under test. It claims the processes whose user tasks
 * the cockpit shows. Its details providers enrich what the engine reported, and one of them also
 * writes into the aggregate.
 */
@Service
@WorkflowService(workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = TestWorkflowService.BPMN_PROCESS_ID),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = TestWorkflowService.MULTI_INSTANCE_PROCESS_ID), @BpmnProcess(
            bpmnProcessId = TestWorkflowService.NO_FORM_KEY_PROCESS_ID), @BpmnProcess(
                bpmnProcessId = TestWorkflowService.EXPRESSION_FORM_KEY_PROCESS_ID), @BpmnProcess(
                    bpmnProcessId = TestWorkflowService.EXPRESSION_CALL_PROCESS_ID), @BpmnProcess(
                        bpmnProcessId = TestWorkflowService.CALLED_SIGN_PROCESS_ID)
    })
public class TestWorkflowService {

  /** The customer whose case and task get a comment from the details providers. */
  public static final String CUSTOMER_WITH_A_COMMENT = "Mona";

  /** The comment the details providers set for {@link #CUSTOMER_WITH_A_COMMENT}. */
  public static final String COMMENT_OF_THE_MODULE = "withdrawn by phone";

  /** The BPMN process of the test. */
  public static final String BPMN_PROCESS_ID = "CockpitProcess";

  /** The form key of the user task. The cockpit calls it the task definition. */
  public static final String TASK_DEFINITION = "approve";

  /** The BPMN element id of the user task. */
  public static final String BPMN_TASK_ID = "Approve";

  /**
   * The version number the engine counted for the one deployment of these models. A reference
   * which the test builds itself carries it, so it matches a reference built by the extension.
   */
  public static final String DEPLOYED_VERSION = "1";

  /** The BPMN process whose user task runs once per signer. */
  public static final String MULTI_INSTANCE_PROCESS_ID = "MultiInstanceProcess";

  /** The form key of the multi-instance user task. */
  public static final String SIGN_TASK_DEFINITION = "sign";

  /** The BPMN element which is the key of the multi-instance context. */
  public static final String MULTI_INSTANCE_ELEMENT = "MI_Sign";

  /** The variable in which each instance of the multi-instance task gets its signer. */
  public static final String SIGNER_VARIABLE = "signer";

  /**
   * A variable of the process instance, not of one multi-instance execution. A
   * <code>&#64;TaskParam</code> of an enclosing scope is bound from it.
   */
  public static final String ORDER_KIND_VARIABLE = "orderKind";

  /** The BPMN process whose call activity names the process it calls in an expression. */
  public static final String EXPRESSION_CALL_PROCESS_ID = "ExpressionCallProcess";

  /** The BPMN process that call activity calls. */
  public static final String CALLED_SIGN_PROCESS_ID = "CalledSignProcess";

  /**
   * The BPMN element of the call activity. It is the only multi-instance element of the two
   * models, and it sits in the CALLING model. So the user task of the called process reports a
   * level which its own model does not have.
   */
  public static final String CALL_ACTIVITY_ELEMENT = "EC_Call";

  /** The form key of the user task of the called process. */
  public static final String CALLER_SIGN_TASK_DEFINITION = "callerSign";

  /** The BPMN element id of that user task. */
  public static final String CALLER_SIGN_TASK_ID = "CS_Sign";

  /** The variable from which the call activity reads the process to call. */
  public static final String CALLED_PROCESS_VARIABLE = "calledProcess";

  /** A BPMN process whose user task carries no form key at all. */
  public static final String NO_FORM_KEY_PROCESS_ID = "NoFormKeyProcess";

  /**
   * The BPMN element id of that user task. The cockpit has to use it as the name, because a
   * task without a form key falls back to its element id.
   */
  public static final String NO_FORM_KEY_TASK_ID = "NFK_Approve";

  /** A BPMN process whose user task names its form by an expression. */
  public static final String EXPRESSION_FORM_KEY_PROCESS_ID = "ExpressionFormKeyProcess";

  /** The BPMN element id of that user task. */
  public static final String EXPRESSION_FORM_KEY_TASK_ID = "EFK_Approve";

  /**
   * The form key of that user task, as the modeller wrote it. The engine computes a different
   * value from it for each workflow instance, and the tests which use it are about that.
   */
  public static final String EXPRESSION_FORM_KEY = "${formName}";

  /** The variable that expression reads. Each workflow instance sets another value. */
  public static final String FORM_NAME_VARIABLE = "formName";

  /** What the details provider writes into the aggregate, so that a test can see it ran. */
  public static final String APPROVE_NOTE = "seen by the details provider";

  /** The detail every provider below writes, so that a test can see which of them ran. */
  public static final String SERVED_BY = "servedBy";

  /** What the provider serving the first deployed model writes into the details of its task. */
  public static final String APPROVE_OF_THE_FIRST = "the task as the first model asked for it";

  /** What the provider serving every later model writes there. */
  public static final String APPROVE_OF_THE_LATER = "the task as a later model asks for it";

  /** What the provider serving the first deployed model writes into the workflow's details. */
  public static final String WORKFLOW_OF_THE_FIRST = "the case as the first model asked for it";

  /** What the provider serving every later model writes there. */
  public static final String WORKFLOW_OF_THE_LATER = "the case as a later model asks for it";

  private final ProcessService<TestAggregate> processService;

  private final BusinessCockpitService<TestAggregate> businessCockpitService;

  public TestWorkflowService(
      final ProcessService<TestAggregate> processService,
      final BusinessCockpitService<TestAggregate> businessCockpitService) {

    this.processService = processService;
    this.businessCockpitService = businessCockpitService;

  }

  /**
   * @return The process service, so that a test can start a workflow
   */
  public ProcessService<TestAggregate> processes() {

    return processService;

  }

  /**
   * @return The cockpit service, so that a test can report a change of its own
   */
  public BusinessCockpitService<TestAggregate> businessCockpit() {

    return businessCockpitService;

  }

  /**
   * Matched by the form key of the user task. It enriches what the engine reported and writes
   * into the workflow aggregate, which is what a details provider is for.
   * <p>
   * The write has to stay, even though no test reads the note any more. It leaves the case dirty
   * in the transaction which dispatches the report. A dispatch which writes nothing is not a
   * second writer of the case, and the version attribute of TestAggregate is there for a second
   * writer.
   *
   * @param aggregate The workflow aggregate, loaded by VanillaBP
   * @param prefilled What the engine knew about the task
   * @param event What happened to the task
   * @return The very object it was given, which is the common case
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = "1")
  public UserTaskDetails approve(
      final TestAggregate aggregate,
      final PrefilledUserTaskDetails prefilled,
      @DetailsEvent final DetailsEvent.Event event) {

    aggregate.setNote(APPROVE_NOTE);
    prefilled
        .setDetails(
            Map
                .of(
                    "customer", aggregate.getCustomer(), "event", event.name(), "assignee", String
                        .valueOf(prefilled.getAssignee()),
                    SERVED_BY, APPROVE_OF_THE_FIRST));
    prefilled.setCandidateGroups(List.of("approvers"));
    if (CUSTOMER_WITH_A_COMMENT.equals(aggregate.getCustomer())) {
      prefilled.setComment(COMMENT_OF_THE_MODULE);
    }
    return prefilled;

  }

  /**
   * The same task as every model deployed after the first one asks for it. The two version
   * ranges do not overlap, so both methods may name the same task.
   *
   * @param prefilled What the engine knew about the task
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = TASK_DEFINITION, version = ">1")
  public UserTaskDetails approveOfALaterModel(
      final PrefilledUserTaskDetails prefilled) {

    prefilled.setDetails(Map.of(SERVED_BY, APPROVE_OF_THE_LATER));
    return prefilled;

  }


  /**
   * Matched by the form key of the multi-instance user task. Its parameters are the
   * multi-instance context the engine reported: the item, the index and the total.
   *
   * @param prefilled What the engine knew about the task
   * @param signer The item this instance is for
   * @param index Which instance this is
   * @param total How many there are
   * @param signerVariable The same item, read as the variable the engine set it in
   * @param orderKind A variable of the process instance, which is a scope enclosing the one
   *          this task runs in
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = SIGN_TASK_DEFINITION)
  public UserTaskDetails sign(
      final PrefilledUserTaskDetails prefilled,
      @MultiInstanceElement(MULTI_INSTANCE_ELEMENT) final String signer,
      @MultiInstanceIndex(MULTI_INSTANCE_ELEMENT) final int index,
      @MultiInstanceTotal(MULTI_INSTANCE_ELEMENT) final int total,
      @TaskParam(SIGNER_VARIABLE) final String signerVariable,
      @TaskParam(ORDER_KIND_VARIABLE) final String orderKind) {

    prefilled
        .setDetails(
            Map
                .of(
                    "signer", signer, "index", String.valueOf(index), "total", String
                        .valueOf(total),
                    "signerVariable", String.valueOf(signerVariable), "orderKind", String
                        .valueOf(orderKind)));
    return prefilled;

  }

  /**
   * The user task of the called process, as the COCKPIT shows it. Its parameters name the call
   * activity of the CALLING model, because the only loop of the two models sits there. So the
   * bridge has to cross that call activity to answer them at all. Here a level the bridge does not
   * report is not just a missing value: naming a level which was not supplied ends the report,
   * and with it the transaction which creates the task.
   *
   * @param prefilled What the engine reported about the task
   * @param signer The item of the round the caller is in
   * @param index Which round that is
   * @param total How many rounds the caller runs
   * @return The enriched details
   */
  @UserTaskDetailsProvider(taskDefinition = CALLER_SIGN_TASK_DEFINITION)
  public UserTaskDetails callerSign(
      final PrefilledUserTaskDetails prefilled,
      @MultiInstanceElement(CALL_ACTIVITY_ELEMENT) final String signer,
      @MultiInstanceIndex(CALL_ACTIVITY_ELEMENT) final int index,
      @MultiInstanceTotal(CALL_ACTIVITY_ELEMENT) final int total) {

    prefilled.setDetails(Map.of("callerRound", levelAsText(signer, index, total)));
    return prefilled;

  }

  /** One round in one string, so that two answers about it are compared as one value. */
  private static String levelAsText(
      final Object item,
      final Object index,
      final Object total) {

    return "%s#%s/%s".formatted(item, index, total);

  }

  /**
   * The workflow as the first deployed model asked for it.
   *
   * @param aggregate The workflow aggregate
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = "1")
  public WorkflowDetails workflowDetails(
      final TestAggregate aggregate,
      final PrefilledWorkflowDetails prefilled) {

    prefilled
        .setDetails(Map.of("customer", aggregate.getCustomer(), SERVED_BY, WORKFLOW_OF_THE_FIRST));
    if (CUSTOMER_WITH_A_COMMENT.equals(aggregate.getCustomer())) {
      prefilled.setComment(COMMENT_OF_THE_MODULE);
    }
    return prefilled;

  }

  /**
   * The same workflow as every model deployed after the first one asks for it. A workflow
   * provider stands for the whole BPMN process, so only the version tells these two apart.
   *
   * @param prefilled What the engine knew about the workflow
   * @return The enriched details
   */
  @WorkflowDetailsProvider(version = ">1")
  public WorkflowDetails workflowDetailsOfALaterModel(
      final PrefilledWorkflowDetails prefilled) {

    prefilled.setDetails(Map.of(SERVED_BY, WORKFLOW_OF_THE_LATER));
    return prefilled;

  }

}
