package com.linkedin.openhouse.internal.catalog;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Serializes OpenHouse-owned operational properties stored in the catalog rather than Iceberg. */
public final class CatalogTableProperties {
  public static final String POLICIES_KEY = "policies";

  private static final String READ_BRIDGE_PREFIX = "openhouse.read-bridge.";
  private static final Gson GSON = new Gson();
  private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();

  private CatalogTableProperties() {}

  public static boolean isCatalogProperty(String key) {
    if (key == null || key.startsWith(READ_BRIDGE_PREFIX)) {
      return false;
    }
    if (key.startsWith("openhouse.")) {
      return !HouseTableSerdeUtils.HTS_FIELD_NAMES.contains(key.substring("openhouse.".length()));
    }
    return POLICIES_KEY.equals(key)
        || CatalogConstants.RTAS_ENABLED_TABLE_PROP.equals(key)
        || CatalogConstants.LAST_UPDATED_MS.equals(key);
  }

  public static Map<String, String> extract(Map<String, String> tableProperties) {
    Map<String, String> extracted = new LinkedHashMap<>();
    if (tableProperties == null) {
      return extracted;
    }
    tableProperties.forEach(
        (key, value) -> {
          if (isCatalogProperty(key)) {
            extracted.put(key, value);
          }
        });
    return extracted;
  }

  public static Map<String, String> deserialize(String json) {
    if (json == null || json.isEmpty()) {
      return new LinkedHashMap<>();
    }
    Map<String, String> properties = GSON.fromJson(json, STRING_MAP_TYPE);
    if (properties == null) {
      throw new IllegalArgumentException("Catalog table properties must be a JSON object");
    }
    return new LinkedHashMap<>(properties);
  }

  public static String serialize(Map<String, String> properties) {
    return properties == null ? null : GSON.toJson(properties);
  }

  /**
   * Return metadata properties with catalog-owned state overlaid from the canonical catalog row.
   */
  public static Map<String, String> overlay(
      Map<String, String> metadataProperties, HouseTable houseTable) {
    return overlay(
        metadataProperties, houseTable == null ? null : houseTable.getCatalogProperties());
  }

  public static Map<String, String> overlay(
      Map<String, String> metadataProperties, String catalogPropertiesJson) {
    Map<String, String> properties = new HashMap<>(metadataProperties);
    if (catalogPropertiesJson != null) {
      properties.keySet().removeIf(CatalogTableProperties::isCatalogProperty);
      properties.putAll(deserialize(catalogPropertiesJson));
    }
    return properties;
  }
}
