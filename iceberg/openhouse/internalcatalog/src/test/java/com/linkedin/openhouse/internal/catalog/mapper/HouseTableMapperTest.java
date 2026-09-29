package com.linkedin.openhouse.internal.catalog.mapper;

import static org.mockito.Mockito.*;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.local.LocalStorage;
import com.linkedin.openhouse.housetables.client.api.ToggleStatusApi;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

@SpringBootTest
public class HouseTableMapperTest {

  /**
   * Tests that doesn't care on HTS server should import this test configuration as
   *
   * @import(classes = MockConfiguration.class)
   */
  @TestConfiguration
  public static class MockConfiguration {
    @Bean
    public UserTableApi provideMockHtsApiInstance() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new UserTableApi(apiClient);
    }

    @Bean
    public ToggleStatusApi provideMockHtsApiInstanceForToggle() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new ToggleStatusApi(apiClient);
    }

    @Bean
    public HouseTableRepository provideRealHtsRepository() {
      return new HouseTableRepositoryImpl();
    }
  }

  @Autowired protected HouseTableMapper houseTableMapper;

  @Autowired FileIOManager fileIOManager;

  @Test
  public void simpleMapperTest() {
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    LocalStorage localStorage = mock(LocalStorage.class);
    when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    when(localStorage.getType()).thenReturn(StorageType.LOCAL);
    HouseTable houseTable =
        houseTableMapper.toHouseTable(
            ImmutableMap.of("databaseId", "openhouse.database", "tableId", "table"), fileIO);
    Assertions.assertEquals("database", houseTable.getDatabaseId());
    Assertions.assertEquals("table", houseTable.getTableId());
    Assertions.assertEquals("local", houseTable.getStorageType());
  }

  @Test
  public void tableIdentityComesFromCatalogIdentifier() {
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    LocalStorage localStorage = mock(LocalStorage.class);
    when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    when(localStorage.getType()).thenReturn(StorageType.LOCAL);

    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.required(1, "data", Types.StringType.get())),
            PartitionSpec.unpartitioned(),
            "file:/tmp/table",
            ImmutableMap.of(
                "openhouse.databaseId", "stale_database",
                "openhouse.tableId", "stale_table",
                "openhouse.clusterId", "test_cluster",
                "openhouse.tableUri", "test_cluster.stale_database.stale_table"));

    HouseTable houseTable =
        houseTableMapper.toHouseTable(
            metadata, fileIO, TableIdentifier.of("catalog_database", "catalog_table"));

    Assertions.assertEquals("catalog_database", houseTable.getDatabaseId());
    Assertions.assertEquals("catalog_table", houseTable.getTableId());
    Assertions.assertEquals(
        "test_cluster.catalog_database.catalog_table", houseTable.getTableUri());
  }

  @Test
  public void catalogPropertiesRoundTripFromHtsResponse() {
    String catalogProperties = "{\"openhouse.tableType\":\"REPLICA_TABLE\"}";
    UserTable userTable =
        new UserTable()
            .tableId("table")
            .databaseId("database")
            .metadataLocation("metadata_location")
            .catalogProperties(catalogProperties);

    HouseTable houseTable = houseTableMapper.toHouseTable(userTable);

    Assertions.assertEquals(catalogProperties, houseTable.getCatalogProperties());
  }
}
