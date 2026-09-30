package com.linkedin.openhouse.internal.catalog.mapper;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.IS_OH_PREFIXED;
import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.OPENHOUSE_NAMESPACE;

import com.linkedin.openhouse.common.api.spec.TableUri;
import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.io.FileIO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class HouseTableMapper {
  @Autowired FileIOManager fileIOManager;

  @Mapping(target = "lastModifiedTime", ignore = true)
  @Mapping(
      target = "storageType",
      expression = "java(fileIOManager.getStorage(fileIO).getType().getValue())")
  public abstract HouseTable toHouseTable(Map<String, String> properties, FileIO fileIO);

  public HouseTable toHouseTable(
      TableMetadata tableMetadata, FileIO fileIO, TableIdentifier tableIdentifier) {
    return toHouseTable(extractRawHTSFields(tableMetadata.properties(), tableIdentifier), fileIO);
  }

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "userTable.databaseId")
  public abstract HouseTable toHouseTableWithDatabaseId(UserTable userTable);

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "houseTable.databaseId")
  public abstract UserTable toUserTableWithDatabaseId(HouseTable houseTable);

  @Mappings({@Mapping(target = "tableLocation", source = "userTable.metadataLocation")})
  public abstract HouseTable toHouseTable(UserTable userTable);

  @Mappings({@Mapping(target = "metadataLocation", source = "houseTable.tableLocation")})
  public abstract UserTable toUserTable(HouseTable houseTable);

  private Map<String, String> extractRawHTSFields(
      Map<String, String> input, TableIdentifier tableIdentifier) {
    Map<String, String> output = new HashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String key = entry.getKey();
      String fieldName = stripOhNamespace(key);
      if (isHtsField(key)
          && !fieldName.equals("databaseId")
          && !fieldName.equals("tableId")
          && !fieldName.equals("tableUri")) {
        output.put(fieldName, entry.getValue());
      }
    }
    String clusterId = input.get(CatalogConstants.OPENHOUSE_CLUSTERID_KEY);
    output.put("databaseId", tableIdentifier.namespace().toString());
    output.put("tableId", tableIdentifier.name());
    if (clusterId != null) {
      output.put(
          "tableUri",
          TableUri.builder()
              .clusterId(clusterId)
              .databaseId(tableIdentifier.namespace().toString())
              .tableId(tableIdentifier.name())
              .build()
              .toString());
    }
    return output;
  }

  private static boolean isHtsField(String key) {
    return IS_OH_PREFIXED.test(key)
        && HouseTableSerdeUtils.HTS_FIELD_NAMES.contains(stripOhNamespace(key));
  }

  static String stripOhNamespace(String key) {
    return IS_OH_PREFIXED.test(key) ? key.substring(OPENHOUSE_NAMESPACE.length()) : key;
  }
}
