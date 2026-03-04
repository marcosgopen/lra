/*
   Copyright The Narayana Authors
   SPDX-License-Identifier: Apache-2.0
 */

package io.narayana.lra.coordinator.infinispan;

import org.infinispan.protostream.GeneratedSchema;
import org.infinispan.protostream.annotations.ProtoSchema;

@ProtoSchema(includeClasses = {
        InfinispanLRAState.class }, schemaFileName = "lra-state.proto", schemaFilePath = "proto", schemaPackageName = "io.narayana.lra")
public interface LRASchemaInitializer extends GeneratedSchema {
}
