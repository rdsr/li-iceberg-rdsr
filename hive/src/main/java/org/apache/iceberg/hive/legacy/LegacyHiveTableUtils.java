/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.hive.legacy;

import com.google.common.collect.Lists;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.serde2.typeinfo.PrimitiveTypeInfo;
import org.apache.hadoop.hive.serde2.typeinfo.TypeInfoFactory;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.types.Conversions;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class LegacyHiveTableUtils {

  private LegacyHiveTableUtils() {
  }

  private static final Logger LOG = LoggerFactory.getLogger(LegacyHiveTableUtils.class);

  static Schema getSchema(org.apache.hadoop.hive.metastore.api.Table table) {
    Map<String, String> props = getTableProperties(table);
    String schemaStr = props.get("avro.schema.literal");
    Schema schema;
    if (schemaStr == null) {
      LOG.warn("Table {}.{} does not have an avro.schema.literal set; using Hive schema instead. The schema will not" +
                   " have case sensitivity and nullability information", table.getDbName(), table.getTableName());
      // TODO: Add support for tables without avro.schema.literal
      throw new UnsupportedOperationException("Reading tables without avro.schema.literal not implemented yet");
    } else {
      schema = AvroSchemaUtil.toIceberg(new org.apache.avro.Schema.Parser().parse(schemaStr));
    }

    Types.StructType dataStructType = schema.asStruct();
    List<Types.NestedField> fields = Lists.newArrayList(dataStructType.fields());

    Schema partitionSchema = partitionSchema(table.getPartitionKeys());
    Types.StructType partitionStructType = partitionSchema.asStruct();
    final Integer maxId = Collections.max(TypeUtil.indexById(dataStructType).keySet());
    final Type partitionType = TypeUtil.assignFreshIds(partitionStructType, new TypeUtil.NextID() {
      private int id = maxId;

      @Override
      public int get() {
        id += 1;
        return id;
      }
    });

    fields.addAll(partitionType.asStructType().fields());
    return new Schema(fields);
  }

  private static Schema partitionSchema(List<FieldSchema> partitionKeys) {
    AtomicInteger fieldId = new AtomicInteger(10000);
    List<Types.NestedField> partitionFields = partitionKeys
        .stream()
        .map(f -> Types.NestedField.optional(
            fieldId.incrementAndGet(), f.getName(), icebergType(f.getType()), f.getComment()))
        .collect(Collectors.toList());
    return new Schema(partitionFields);
  }

  private static Type icebergType(String hiveTypeString) {
    PrimitiveTypeInfo primitiveTypeInfo = TypeInfoFactory.getPrimitiveTypeInfo(hiveTypeString);
    return new HiveTypeToIcebergType().primitive(primitiveTypeInfo);
  }

  static Map<String, String> getTableProperties(org.apache.hadoop.hive.metastore.api.Table table) {
    Map<String, String> props = new HashMap<>();
    props.putAll(table.getSd().getSerdeInfo().getParameters());
    props.putAll(table.getSd().getParameters());
    props.putAll(table.getParameters());
    return props;
  }

  static PartitionSpec getPartitionSpec(org.apache.hadoop.hive.metastore.api.Table table, Schema schema) {
    PartitionSpec.Builder builder = PartitionSpec.builderFor(schema);
    table.getPartitionKeys().forEach(fieldSchema -> builder.identity(fieldSchema.getName()));
    return builder.build();
  }

  static DirectoryInfo toDirectoryInfo(org.apache.hadoop.hive.metastore.api.Table table) {
    return new DirectoryInfo(table.getSd().getLocation(),
                             serdeToFileFormat(table.getSd().getSerdeInfo().getSerializationLib()), null);
  }

  static List<DirectoryInfo> toDirectoryInfos(List<Partition> partitions, PartitionSpec spec) {
    return partitions.stream().map(
        p -> new DirectoryInfo(
            p.getSd().getLocation(),
            serdeToFileFormat(
                p.getSd().getSerdeInfo().getSerializationLib()),
            buildPartitionStructLike(p.getValues(), spec))
    ).collect(Collectors.toList());
  }

  private static StructLike buildPartitionStructLike(List<String> partitionValues, PartitionSpec spec) {
    List<Types.NestedField> fields = spec.partitionType().fields();
    return new StructLike() {
      @Override
      public int size() {
        return partitionValues.size();
      }

      @Override
      public <T> T get(int pos, Class<T> javaClass) {
        final Object partitionValue = Conversions.fromPartitionString(
            fields.get(pos).type(),
            partitionValues.get(pos));
        return javaClass.cast(partitionValue);
      }

      @Override
      public <T> void set(int pos, T value) {
        throw new IllegalStateException("Read-only");
      }
    };
  }

  private static FileFormat serdeToFileFormat(String serde) {
    switch (serde) {
      case "org.apache.hadoop.hive.serde2.avro.AvroSerDe":
        return FileFormat.AVRO;
      case "org.apache.hadoop.hive.ql.io.orc.OrcSerde":
        return FileFormat.ORC;
      default:
        throw new IllegalArgumentException("Unrecognized serde: " + serde);
    }
  }
}
