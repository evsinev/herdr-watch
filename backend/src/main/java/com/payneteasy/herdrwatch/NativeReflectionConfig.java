package com.payneteasy.herdrwatch;

import com.payneteasy.herdrwatch.http.ServersResource;
import com.payneteasy.herdrwatch.model.DataSource;
import com.payneteasy.herdrwatch.model.HostDef;
import com.payneteasy.herdrwatch.model.Model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Регистрация типов для рефлексии в native-образе. Наши record'ы сериализуются
 * Jackson'ом, причём часть — «ручным» ObjectMapper (HostStore → state-файл) и через
 * StreamEvent.data типа Object (SSE), поэтому автоматической регистрации от
 * quarkus-rest недостаточно. В JVM-режиме аннотация ни на что не влияет.
 */
@RegisterForReflection(
        targets = {
            Model.HostState.class,
            Model.WorkspaceInfo.class,
            Model.AgentInfo.class,
            Model.WorktreeInfo.class,
            Model.StreamEvent.class,
            Model.Health.class,
            DataSource.class,
            HostDef.class,
            HostStore.StateFile.class,
            ServersResource.HostRequest.class,
            ServersResource.ServerView.class,
        })
public class NativeReflectionConfig {
}
