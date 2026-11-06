package io.vanillabp.cockpit.operaton.migration;

import java.util.List;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import io.vanillabp.spi.cockpit.workflowmodules.WorkflowModuleDetailsProvider;

/**
 * The application of this module: both adapters and both Business Cockpit extensions on the
 * classpath. Which adapter runs a new workflow is the configuration of each boot.
 */
@SpringBootApplication
public class MigrationTestApplication {

  /** The workflow module of the test. */
  public static final String MODULE_ID = "migration";

  /** The name of the system property which names the database of the Operaton engine. */
  public static final String SECOND_DATABASE = "migration.second-database";

  /**
   * Lets the Camunda 7 engine evaluate decisions without the Scala FEEL engine. Operaton 2.2
   * brings another version of that library, and the pom.xml of this module removes Camunda's
   * copy. Without this the Camunda 7 engine does not start. See decision 7 in the repository's
   * DECISIONS.md.
   *
   * @return The customizer of every Camunda 7 engine of the application
   */
  @Bean
  public io.vanillabp.camunda7.engine.Camunda7EngineCustomizer camunda7WithoutScalaFeel() {

    return new io.vanillabp.camunda7.engine.Camunda7EngineCustomizer() {

      @Override
      public void customize(
          final String adapterId,
          final org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl configuration) {

        configuration.setDmnFeelEnableLegacyBehavior(true);

      }

    };

  }

  /**
   * A datasource of the application which only the Operaton engine uses, named by
   * <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>. Two engines on one set of
   * tables are refused. Out of injection by type, so Spring Boot's own datasource stays the
   * default one.
   *
   * @return A datasource on the database the system property {@link #SECOND_DATABASE} names
   */
  @Bean(defaultCandidate = false)
  public DataSource operatonDataSource() {

    return new SimpleDriverDataSource(
        new org.h2.Driver(), "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1"
            .formatted(System.getProperty(SECOND_DATABASE, "unused-second-engine")));

  }

  /**
   * @return What the cockpit server is told about the workflow module
   */
  @Bean
  public WorkflowModuleDetailsProvider workflowModuleDetailsProvider() {

    return new WorkflowModuleDetailsProvider() {

      @Override
      public List<String> getAccessibleToGroups() {

        return List.of("clerks");

      }

      @Override
      public String getWorkflowModuleId() {

        return MODULE_ID;

      }

    };

  }

}
